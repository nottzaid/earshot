// Network link: one ROC sender streaming mono float32 PCM to an Earshot receiver.
#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ES_RATE 48000
#define ES_ENCODING_ID 100 // must match PW_ROC_MULTITRACK_ENCODING_ID on the receiver

#define ES_PORT_SOURCE 10001
#define ES_PORT_REPAIR 10002
#define ES_PORT_CONTROL 10003

typedef struct es_link es_link;

typedef struct es_link_params {
    const char* host;
    unsigned packet_us;     // audio per packet
    unsigned fec_source;    // FEC block: source packets
    unsigned fec_repair;    // FEC block: repair packets
} es_link_params;

es_link* es_link_open(const es_link_params* params);
// Blocks never: the caller's capture clock paces the stream.
int es_link_write(es_link* link, const float* samples, size_t count);
// Number of receivers that answered over RTCP (0 or 1), or -1 on error.
int es_link_receivers(es_link* link);
void es_link_close(es_link* link);

#ifdef __cplusplus
}
#endif
