#include "es_link.h"

#include <netdb.h>
#include <netinet/in.h>
#include <roc/context.h>
#include <roc/log.h>
#include <roc/sender_encoder.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>

// Roc logs to stderr by default, which Android discards.
static void log_to_logcat(const roc_log_message* msg, void* arg) {
    (void)arg;
    __android_log_print(msg->level == ROC_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "earshot-roc",
                        "%s: %s", msg->module, msg->text);
}
#endif

// Packets go out marked as voice (DSCP EF). Wi-Fi then sends them from its voice/video
// queue, which waits a few slots for the medium instead of up to a thousand, and the
// access point forwards them ahead of downloads headed to the same PC.
#define ES_TOS 0xb8
#define MAX_PACKET 2048

struct es_link {
    roc_context* context;
    roc_sender_encoder* encoder;
    int fd;
    struct sockaddr_in to[3]; // source, repair, control
};

static const roc_interface ifaces[3] = { ROC_INTERFACE_AUDIO_SOURCE, ROC_INTERFACE_AUDIO_REPAIR,
                                         ROC_INTERFACE_AUDIO_CONTROL };

es_link* es_link_open(const es_link_params* p) {
    es_link* link = calloc(1, sizeof(*link));
    if (!link)
        return NULL;
    link->fd = -1;

    roc_log_set_level(ROC_LOG_ERROR);
#ifdef __ANDROID__
    roc_log_set_handler(log_to_logcat, NULL);
#endif

    struct addrinfo hints = { .ai_family = AF_INET, .ai_socktype = SOCK_DGRAM }, *ai = NULL;
    if (getaddrinfo(p->host, NULL, &hints, &ai) != 0 || !ai)
        goto fail;
    const int ports[3] = { ES_PORT_SOURCE, ES_PORT_REPAIR, ES_PORT_CONTROL };
    for (int i = 0; i < 3; i++) {
        link->to[i] = *(struct sockaddr_in*)ai->ai_addr;
        link->to[i].sin_port = htons(ports[i]);
    }
    freeaddrinfo(ai);

    // One socket for all three: the receiver answers RTCP to wherever it came from.
    link->fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (link->fd < 0)
        goto fail;
    int tos = ES_TOS;
    setsockopt(link->fd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));

    roc_context_config ctx_cfg;
    memset(&ctx_cfg, 0, sizeof(ctx_cfg));
    if (roc_context_open(&ctx_cfg, &link->context) != 0)
        goto fail;

    roc_media_encoding enc;
    memset(&enc, 0, sizeof(enc));
    enc.rate = ES_RATE;
    enc.format = ROC_FORMAT_PCM_FLOAT32;
    enc.channels = ROC_CHANNEL_LAYOUT_MULTITRACK;
    enc.tracks = 1;
    if (roc_context_register_encoding(link->context, ES_ENCODING_ID, &enc) != 0)
        goto fail;

    roc_sender_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.frame_encoding = enc;
    cfg.packet_encoding = ES_ENCODING_ID;
    cfg.packet_length = (unsigned long long)p->packet_us * 1000ull;
    cfg.fec_encoding = ROC_FEC_ENCODING_RS8M;
    cfg.fec_block_source_packets = p->fec_source;
    cfg.fec_block_repair_packets = p->fec_repair;
    cfg.clock_source = ROC_CLOCK_SOURCE_EXTERNAL;
    if (roc_sender_encoder_open(link->context, &cfg, &link->encoder) != 0)
        goto fail;

    if (roc_sender_encoder_activate(link->encoder, ROC_INTERFACE_AUDIO_SOURCE, ROC_PROTO_RTP_RS8M_SOURCE)
        || roc_sender_encoder_activate(link->encoder, ROC_INTERFACE_AUDIO_REPAIR, ROC_PROTO_RS8M_REPAIR)
        || roc_sender_encoder_activate(link->encoder, ROC_INTERFACE_AUDIO_CONTROL, ROC_PROTO_RTCP))
        goto fail;

    return link;

fail:
    es_link_close(link);
    return NULL;
}

int es_link_write(es_link* link, const float* samples, size_t count) {
    uint8_t buf[MAX_PACKET];

    // The receiver's RTCP first, so the encoder sees it before it next reports.
    for (;;) {
        ssize_t n = recv(link->fd, buf, sizeof(buf), 0);
        if (n <= 0)
            break;
        roc_packet pkt = { buf, (size_t)n };
        roc_sender_encoder_push_feedback_packet(link->encoder, ROC_INTERFACE_AUDIO_CONTROL, &pkt);
    }

    roc_frame frame;
    frame.samples = (void*)samples;
    frame.samples_size = count * sizeof(float);
    if (roc_sender_encoder_push_frame(link->encoder, &frame) != 0)
        return -1;

    // Lost sends are the network's business, as they were with Roc's own sockets.
    for (int i = 0; i < 3; i++) {
        for (;;) {
            roc_packet pkt = { buf, sizeof(buf) };
            if (roc_sender_encoder_pop_packet(link->encoder, ifaces[i], &pkt) != 0)
                break;
            sendto(link->fd, buf, pkt.bytes_size, 0, (struct sockaddr*)&link->to[i], sizeof(link->to[i]));
        }
    }
    return 0;
}

int es_link_receivers(es_link* link) {
    roc_sender_metrics m;
    roc_connection_metrics c;
    memset(&m, 0, sizeof(m));
    if (roc_sender_encoder_query(link->encoder, &m, &c) != 0)
        return -1;
    return (int)m.connection_count;
}

void es_link_close(es_link* link) {
    if (!link)
        return;
    if (link->encoder)
        roc_sender_encoder_close(link->encoder);
    if (link->context)
        roc_context_close(link->context);
    if (link->fd >= 0)
        close(link->fd);
    free(link);
}
