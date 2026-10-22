#include "es_link.h"

#include <roc/context.h>
#include <roc/endpoint.h>
#include <roc/log.h>
#include <roc/sender.h>
#include <stdlib.h>
#include <string.h>

struct es_link {
    roc_context* context;
    roc_sender* sender;
};

static int connect_iface(roc_sender* sender, roc_interface iface, roc_protocol proto,
                         const char* host, int port) {
    roc_endpoint* ep = NULL;
    if (roc_endpoint_allocate(&ep) != 0)
        return -1;
    int err = roc_endpoint_set_protocol(ep, proto) || roc_endpoint_set_host(ep, host)
        || roc_endpoint_set_port(ep, port)
        || roc_sender_connect(sender, ROC_SLOT_DEFAULT, iface, ep);
    roc_endpoint_deallocate(ep);
    return err ? -1 : 0;
}

es_link* es_link_open(const es_link_params* p) {
    es_link* link = calloc(1, sizeof(*link));
    if (!link)
        return NULL;

    roc_log_set_level(ROC_LOG_ERROR);

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
    if (roc_sender_open(link->context, &cfg, &link->sender) != 0)
        goto fail;

    if (connect_iface(link->sender, ROC_INTERFACE_AUDIO_SOURCE, ROC_PROTO_RTP_RS8M_SOURCE,
                      p->host, ES_PORT_SOURCE)
        || connect_iface(link->sender, ROC_INTERFACE_AUDIO_REPAIR, ROC_PROTO_RS8M_REPAIR,
                         p->host, ES_PORT_REPAIR)
        || connect_iface(link->sender, ROC_INTERFACE_AUDIO_CONTROL, ROC_PROTO_RTCP, p->host,
                         ES_PORT_CONTROL))
        goto fail;

    return link;

fail:
    es_link_close(link);
    return NULL;
}

int es_link_write(es_link* link, const float* samples, size_t count) {
    roc_frame frame;
    frame.samples = (void*)samples;
    frame.samples_size = count * sizeof(float);
    return roc_sender_write(link->sender, &frame) == 0 ? 0 : -1;
}

int es_link_receivers(es_link* link) {
    roc_sender_metrics m;
    memset(&m, 0, sizeof(m));
    if (roc_sender_query(link->sender, ROC_SLOT_DEFAULT, &m, NULL, NULL) != 0)
        return -1;
    return (int)m.connection_count;
}

void es_link_close(es_link* link) {
    if (!link)
        return;
    if (link->sender)
        roc_sender_close(link->sender);
    if (link->context)
        roc_context_close(link->context);
    free(link);
}
