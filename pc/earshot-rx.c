// earshot-rx: the PC end of the Earshot link, as one PipeWire source node.
//
// ROC decodes the phone's packets and repairs losses, with its own clock correction off:
// its tuner reacts to every network hiccup, so the phone-to-PC delay kept ramping and
// jumping, which an echo canceller can't follow. Here the phone's clock is matched by our own
// resampler, steered by a deliberately slow estimate of the clock ratio taken from how full
// the packet queue gets when packets arrive on time. Late packets never move it, so the delay
// holds still to within a sample or two once locked, and the ratio is kept across runs so the
// next stream starts locked.
//
// With echo cancellation on, the PC's speaker output is captured too (same graph, same clock)
// and what it put into the phone's mic is subtracted (aec.c); nothing else changes.
//
//   earshot-rx --latency MS [--node NAME] [--description TEXT] [--clock FILE] [--stats FILE]
//              [--port BASE] [--aec on|off] [--control]
//   --control: read commands from stdin ("aec on", "aec off"); exit when it closes.

#include "aec.h"

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <roc/config.h>
#include <roc/context.h>
#include <roc/log.h>
#include <roc/receiver_decoder.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <math.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define RATE 48000
#define ENCODING_ID 100 // the phone's ES_ENCODING_ID: mono float32 at 48 kHz
#define PORT_BASE 10001         // source, then repair, then control
#define MAX_PACKET 2048
#define MAX_POP 4096                 // samples per decoder pop
#define WINDOW RATE                  // one control update per second of output
#define SETTLE_WINDOWS 3             // windows averaged into the reference once audio starts
#define REG_WINDOWS 30               // clock ratio: slope fitted over the last 30 s
#define REG_MIN 10
#define WARMUP_WINDOWS 20           // a fresh stream's queue settles for a while; don't read a clock into that
#define STEP_MAX 5e-6                // a known ratio moves at most 5 ppm per estimate
#define RATIO_GAIN 0.03              // per window, once the ratio is known (time constant ~30 s)
#define RATIO_GAIN_COLD 0.15
#define PULL_TAU 120.0               // seconds to ease a wandering queue back into its band
#define CORR_MAX 1e-3                // the loop never asks for more than 1000 ppm
#define SKIP_FADE 256                // crossfade length of an emergency latency cut

// Resampler: Kaiser-windowed sinc, 48 taps (0.5 ms of lookahead), 256 phases interpolated.
#define RS_HALF 24
#define RS_PHASES 256
#define FIFO (1 << 16)               // phone samples held for the resampler

// Echo cancellation runs on PC-clock samples addressed by the graph's tick counter.
#define RING (1 << 18)               // 5.4 s of speaker output and resampled mic
#define ACQ_LEN (1 << 17)            // 2.7 s per delay measurement
#define MAX_LAG 12000                // search up to 250 ms
#define RECHECK_S 15
#define ZERO_RUN 32                  // this many exact zeros in a row: the mic is off or muted

enum { AEC_OFF, AEC_ACQUIRE, AEC_RUN };

// One decoder per stream from the phone (a new SSRC means the phone restarted its sender).
struct epoch {
    roc_receiver_decoder* dec;
    uint32_t ssrc;
    int64_t last_ext;               // extended RTP timestamp of the newest packet
    uint32_t last_ts;
    uint16_t last_seq;
    uint32_t pkt_samples;
    _Atomic int64_t max_end;        // newest sample the queue holds, in phone samples
    // The RT thread's consumption clock, published per cycle (seqlock), so the network thread
    // can read the queue at the instant each packet lands rather than at cycle edges.
    atomic_uint seq;
    _Atomic int64_t pub_t, pub_popped;
    _Atomic double pub_rate;        // phone samples consumed per ns
    _Atomic int64_t peak;           // highest reading since the last window, 1/256 samples
};

static struct {
    // config
    unsigned latency_ms;
    const char* node;
    const char* description;
    const char* clock_path;
    const char* stats_path;
    int port;
    int dump_fd;                    // debug: mic and output pairs, EARSHOT_RX_DUMP

    roc_context* roc;
    int fd_source, fd_repair, fd_control;
    struct sockaddr_storage peer;   // where the phone's RTCP comes from; feedback goes back
    socklen_t peer_len;
    atomic_bool running;

    // epoch hand-off: the network thread creates, the RT thread adopts, the network thread frees
    struct epoch* net_cur;
    _Atomic(struct epoch*) pending;
    _Atomic(struct epoch*) retired;

    // RT state
    struct pw_main_loop* loop;
    struct pw_stream* stream;
    struct epoch* active;
    struct epoch* to_retire;
    bool started;                   // decoder has begun producing audio
    int64_t popped;                 // phone samples taken from the active decoder
    uint32_t win_out;               // output samples into the current window
    int settle;                     // windows of reference still to gather
    double ref;                     // queue reading the loop holds
    double integ;                   // learned clock ratio offset (phone/PC - 1)
    bool have_integ;
    bool learned;                   // ratio estimate has settled (or came from the clock file)
    double corr;                    // correction in force
    double applied;                 // queue samples our corrections have removed so far
    uint64_t window;
    struct sample { double k, y; } hist[REG_WINDOWS];
    int hist_n, hist_head;
    int warmup;
    int over_windows;
    uint32_t skip;                  // samples to cut on the next cycle
    double step;                    // phone samples per output sample (1 + corr)

    // resampler: in[] holds phone samples from index in_base on; pos is the next output's
    // position in that same phone-sample count
    float in[FIFO];
    int64_t in_base, in_end;
    double pos;
    float rs_table[RS_PHASES + 1][2 * RS_HALF];

    // echo cancellation (RT side)
    struct pw_context* context;
    struct pw_core* core;
    struct spa_hook stream_listener, ref_listener, core_listener;
    struct pw_stream* ref_stream;
    bool streaming;                 // someone is listening to the mic
    atomic_int aec_want;            // AEC_OFF or AEC_ACQUIRE (set by the main thread)
    int aec_state;                  // RT copy
    atomic_int aec_delay;           // filter's first lag, set by the main thread
    atomic_bool aec_new_delay;
    int aec_d;
    aec* aec;
    float ref_ring[RING], mic_ring[RING];
    _Atomic int64_t ref_end, mic_end; // graph tick after the last sample written (mic's count)
    // Each stream counts ticks from its own start, so the speaker stream's are mapped onto the
    // mic's through the cycle time both streams see.
    int64_t ref_now, ref_ticks;     // the speaker stream's last cycle
    int64_t tick_offset;            // mic ticks minus speaker ticks
    bool offset_known;
    int64_t blk_start;              // tick of the block in progress (-1: none)
    float pred[AEC_N], err[AEC_N];
    bool blk_learn;
    int zero_run;

    // echo cancellation (main thread side)
    float acq_ref[ACQ_LEN], acq_mic[ACQ_LEN];
    int recheck_in, recheck_miss;
    bool control;

    // published for the stats writer
    _Atomic double st_err, st_corr, st_integ;
    atomic_int st_state;            // 0 idle, 1 settling, 2 locked
    atomic_uint st_skips, st_windows, st_late, st_slow;
    _Atomic double st_erle;         // mic over output power, last second, dB
    atomic_int st_aec;              // the RT thread's aec_state
    int64_t last_cycle;
    _Atomic int64_t st_max_ns;
} g = { .fd_source = -1, .fd_repair = -1, .fd_control = -1 };

static int64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}

static void on_roc_log(const roc_log_message* m, void* arg) {
    (void)arg;
    fprintf(stderr, "roc: %s\n", m->text);
}

static int bind_udp(int port) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0)
        return -1;
    int one = 1, buf = 1 << 20;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sizeof(buf));
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(port),
                             .sin_addr.s_addr = htonl(INADDR_ANY) };
    if (bind(fd, (struct sockaddr*)&a, sizeof(a)) != 0) {
        fprintf(stderr, "bind :%d: %s\n", port, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static struct epoch* epoch_open(uint32_t ssrc) {
    roc_receiver_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.frame_encoding.rate = RATE;
    cfg.frame_encoding.format = ROC_FORMAT_PCM_FLOAT32;
    cfg.frame_encoding.channels = ROC_CHANNEL_LAYOUT_MULTITRACK;
    cfg.frame_encoding.tracks = 1;
    cfg.clock_source = ROC_CLOCK_SOURCE_EXTERNAL;
    // INTACT: no resampling and no bounds; playback starts once target_latency is queued,
    // then runs at whatever pace we pop, filling gaps with silence and dropping late packets.
    cfg.latency_tuner_profile = ROC_LATENCY_TUNER_PROFILE_INTACT;
    cfg.target_latency = (unsigned long long)g.latency_ms * 1000000ull;
    cfg.no_playback_timeout = -1;
    cfg.choppy_playback_timeout = -1;

    struct epoch* e = calloc(1, sizeof(*e));
    if (!e)
        return NULL;
    if (roc_receiver_decoder_open(g.roc, &cfg, &e->dec) != 0
        || roc_receiver_decoder_activate(e->dec, ROC_INTERFACE_AUDIO_SOURCE,
                                         ROC_PROTO_RTP_RS8M_SOURCE) != 0
        || roc_receiver_decoder_activate(e->dec, ROC_INTERFACE_AUDIO_REPAIR,
                                         ROC_PROTO_RS8M_REPAIR) != 0
        || roc_receiver_decoder_activate(e->dec, ROC_INTERFACE_AUDIO_CONTROL,
                                         ROC_PROTO_RTCP) != 0) {
        fprintf(stderr, "decoder setup failed\n");
        if (e->dec)
            roc_receiver_decoder_close(e->dec);
        free(e);
        return NULL;
    }
    e->ssrc = ssrc;
    atomic_init(&e->peak, INT64_MIN);
    return e;
}

static void epoch_close(struct epoch* e) {
    if (!e)
        return;
    roc_receiver_decoder_close(e->dec);
    free(e);
}

// Network thread: feeds packets to the current decoder and carries its RTCP feedback back.
static void on_source_packet(const uint8_t* p, size_t n) {
    if (n < 12 || (p[0] >> 6) != 2)
        return;
    uint16_t seq = (uint16_t)(p[2] << 8 | p[3]);
    uint32_t ts = (uint32_t)p[4] << 24 | (uint32_t)p[5] << 16 | (uint32_t)p[6] << 8 | p[7];
    uint32_t ssrc = (uint32_t)p[8] << 24 | (uint32_t)p[9] << 16 | (uint32_t)p[10] << 8 | p[11];

    struct epoch* e = g.net_cur;
    if (!e || e->ssrc != ssrc) {
        struct epoch* fresh = epoch_open(ssrc);
        if (!fresh)
            return;
        fresh->last_ts = ts;
        fresh->last_seq = (uint16_t)(seq - 1);
        // Whichever of us takes a pending epoch owns it; an unadopted one is ours to free.
        epoch_close(atomic_exchange(&g.pending, fresh));
        g.net_cur = e = fresh;
        fprintf(stderr, "stream %08x started\n", ssrc);
    }

    int64_t ext = e->last_ext + (int32_t)(ts - e->last_ts);
    if (seq == (uint16_t)(e->last_seq + 1) && ext > e->last_ext)
        e->pkt_samples = (uint32_t)(ext - e->last_ext);
    if (ext >= e->last_ext) {
        e->last_ext = ext;
        e->last_ts = ts;
        e->last_seq = seq;
    }
    int64_t end = ext + e->pkt_samples;
    if (end > atomic_load_explicit(&e->max_end, memory_order_relaxed))
        atomic_store_explicit(&e->max_end, end, memory_order_release);
    unsigned s0 = atomic_load_explicit(&e->seq, memory_order_acquire);
    if (s0 && !(s0 & 1)) {
        int64_t t = atomic_load_explicit(&e->pub_t, memory_order_relaxed);
        int64_t popped = atomic_load_explicit(&e->pub_popped, memory_order_relaxed);
        double rate = atomic_load_explicit(&e->pub_rate, memory_order_relaxed);
        atomic_thread_fence(memory_order_acquire);
        if (atomic_load_explicit(&e->seq, memory_order_relaxed) == s0) {
            double reading = (double)(end - popped) - (double)(now_ns() - t) * rate;
            int64_t r = (int64_t)(reading * 256.0), cur = atomic_load(&e->peak);
            while (r > cur && !atomic_compare_exchange_weak(&e->peak, &cur, r))
                ;
        }
    }

    roc_packet pkt = { (void*)p, n };
    roc_receiver_decoder_push_packet(e->dec, ROC_INTERFACE_AUDIO_SOURCE, &pkt);
}

static void* net_thread(void* arg) {
    (void)arg;
    uint8_t buf[MAX_PACKET];
    struct pollfd fds[3] = { { g.fd_source, POLLIN, 0 }, { g.fd_repair, POLLIN, 0 },
                             { g.fd_control, POLLIN, 0 } };
    while (atomic_load(&g.running)) {
        poll(fds, 3, 10);
        for (int i = 0; i < 3; i++) {
            for (;;) {
                struct sockaddr_storage from;
                socklen_t flen = sizeof(from);
                ssize_t n = recvfrom(fds[i].fd, buf, sizeof(buf), 0, (struct sockaddr*)&from, &flen);
                if (n <= 0)
                    break;
                if (i == 0) {
                    on_source_packet(buf, (size_t)n);
                } else if (g.net_cur) {
                    roc_packet pkt = { buf, (size_t)n };
                    if (i == 2) {
                        g.peer = from;
                        g.peer_len = flen;
                    }
                    roc_receiver_decoder_push_packet(
                        g.net_cur->dec, i == 1 ? ROC_INTERFACE_AUDIO_REPAIR : ROC_INTERFACE_AUDIO_CONTROL,
                        &pkt);
                }
            }
        }
        if (g.net_cur && g.peer_len) {
            for (;;) {
                roc_packet fb = { buf, sizeof(buf) };
                if (roc_receiver_decoder_pop_feedback_packet(g.net_cur->dec, ROC_INTERFACE_AUDIO_CONTROL,
                                                             &fb) != 0)
                    break;
                sendto(g.fd_control, buf, fb.bytes_size, 0, (struct sockaddr*)&g.peer, g.peer_len);
            }
        }
        epoch_close(atomic_exchange(&g.retired, NULL));
    }
    return NULL;
}

static void pop(float* dst, uint32_t n) {
    while (n) {
        uint32_t k = n < MAX_POP ? n : MAX_POP;
        roc_frame f = { dst, k * sizeof(float) };
        if (roc_receiver_decoder_pop_frame(g.active->dec, &f) != 0)
            memset(dst, 0, k * sizeof(float));
        if (!g.started) {
            for (uint32_t i = 0; i < k; i++)
                if (dst[i] != 0.0f) {
                    g.started = true;
                    atomic_store(&g.active->peak, INT64_MIN); // readings so far predate playback
                    break;
                }
        }
        dst += k;
        n -= k;
        g.popped += k;
    }
}

static double bessel_i0(double x) {
    double sum = 1, term = 1;
    for (int k = 1; k < 50; k++) {
        term *= (x / (2 * k)) * (x / (2 * k));
        sum += term;
    }
    return sum;
}

static void rs_init(void) {
    const double beta = 9.0;
    for (int p = 0; p <= RS_PHASES; p++) {
        double f = (double)p / RS_PHASES, h[2 * RS_HALF], sum = 0;
        for (int t = 0; t < 2 * RS_HALF; t++) {
            double x = (t - RS_HALF + 1) - f; // tap t multiplies in[i + t - RS_HALF + 1]
            double r = x / RS_HALF;
            double w = fabs(r) >= 1 ? 0 : bessel_i0(beta * sqrt(1 - r * r)) / bessel_i0(beta);
            h[t] = (fabs(x) < 1e-12 ? 1 : sin(M_PI * x) / (M_PI * x)) * w;
            sum += h[t];
        }
        for (int t = 0; t < 2 * RS_HALF; t++)
            g.rs_table[p][t] = (float)(h[t] / sum);
    }
}

// Make in[] hold phone samples up to `end`, dropping what lies before `keep` to make room.
static void rs_need(int64_t end, int64_t keep) {
    if (end <= g.in_end)
        return;
    if (end - g.in_base > FIFO && keep > g.in_base) {
        memmove(g.in, g.in + (keep - g.in_base), (size_t)(g.in_end - keep) * sizeof(float));
        g.in_base = keep;
    }
    if (end - g.in_base > FIFO) // can't happen with cuts capped at half a second; never overrun
        return;
    pop(g.in + (g.in_end - g.in_base), (uint32_t)(end - g.in_end));
    g.in_end = end;
}

static inline float rs_at(double c) {
    int64_t i = (int64_t)floor(c);
    float fp = (float)(c - (double)i) * RS_PHASES;
    int p = (int)fp;
    float a = fp - (float)p;
    const float *h0 = g.rs_table[p], *h1 = g.rs_table[p + 1];
    const float* x = g.in + (i - RS_HALF + 1 - g.in_base);
    float acc = 0;
    for (int t = 0; t < 2 * RS_HALF; t++)
        acc += x[t] * (h0[t] + a * (h1[t] - h0[t]));
    return acc;
}

static void reset_loop_state(void) {
    g.started = false;
    g.popped = 0;
    g.win_out = 0;
    g.settle = SETTLE_WINDOWS;
    g.ref = 0;
    g.over_windows = 0;
    g.skip = 0;
    g.hist_n = 0;
    g.hist_head = 0;
    g.warmup = g.learned ? WARMUP_WINDOWS : 0;
    g.applied = 0;
    g.corr = g.integ;
    g.step = 1.0 + g.corr;
    g.in_base = g.in_end = 0;
    g.pos = RS_HALF - 1; // the first output needs phone samples 0 .. 2 RS_HALF - 1
    atomic_store(&g.st_state, g.active ? 1 : 0);
}

// One control update per window. The reading is the queue's peak over the window, the moment
// packets arrived on time: jitter only ever makes packets late, so the peak ignores it.
static void end_window(void) {
    struct epoch* e = g.active;
    int64_t peak = atomic_exchange(&e->peak, INT64_MIN);
    atomic_fetch_add(&g.st_windows, 1);
    g.window++;
    if (g.warmup > 0 && g.started && g.settle == 0)
        g.warmup--;
    g.applied += RATE * g.corr; // what our own corrections did to the queue this window
    if (!g.started || peak == INT64_MIN)
        return; // nothing arrived to learn from: hold the current ratio
    double reading = (double)peak / 256.0;
    if (g.settle > 0) {
        g.ref += reading / SETTLE_WINDOWS;
        if (--g.settle == 0)
            atomic_store(&g.st_state, 2);
        return;
    }
    double err = reading - g.ref; // >0: queue fuller than when the stream started

    // Far over target and staying there (only after something pathological): cut back now.
    if (err > (double)(g.latency_ms * RATE / 1000) + 0.03 * RATE) {
        if (++g.over_windows >= 3) {
            g.over_windows = 0;
            uint32_t skip = (uint32_t)err;
            if (skip > RATE / 2)
                skip = RATE / 2;
            g.skip = skip;
            g.hist_n = 0; // the cut is a step in the readings, not a clock difference
            atomic_fetch_add(&g.st_skips, 1);
            return;
        }
    } else {
        g.over_windows = 0;
    }

    // The clock ratio is the slope of the readings with our own corrections added back, so
    // estimating it never fights the loop. The delay is left wherever the stream settled;
    // only a queue that wanders well away from it gets a gentle pull back.
    if (g.warmup > 0)
        goto apply;
    struct sample* h = &g.hist[g.hist_head];
    h->k = (double)g.window;
    h->y = reading + g.applied;
    g.hist_head = (g.hist_head + 1) % REG_WINDOWS;
    if (g.hist_n < REG_WINDOWS)
        g.hist_n++;
    if (g.hist_n >= REG_MIN) {
        double sk = 0, sy = 0, skk = 0, sky = 0;
        for (int i = 0; i < g.hist_n; i++) {
            sk += g.hist[i].k;
            sy += g.hist[i].y;
        }
        double mk = sk / g.hist_n, my = sy / g.hist_n;
        for (int i = 0; i < g.hist_n; i++) {
            double dk = g.hist[i].k - mk;
            skk += dk * dk;
            sky += dk * (g.hist[i].y - my);
        }
        double ratio = sky / skk / RATE; // phone samples gained per sample consumed
        double gain = g.learned ? RATIO_GAIN : RATIO_GAIN_COLD, step = gain * (ratio - g.integ);
        if (g.learned && fabs(step) > STEP_MAX * gain)
            step = copysign(STEP_MAX * gain, step);
        g.integ += step;
        if (g.integ > CORR_MAX)
            g.integ = CORR_MAX;
        if (g.integ < -CORR_MAX)
            g.integ = -CORR_MAX;
        g.have_integ = true;
        if (g.hist_n == REG_WINDOWS && fabs(ratio - g.integ) < 2e-6)
            g.learned = true;
    }
apply:;
    double band = g.latency_ms * RATE / 4000.0, pull = 0;
    if (fabs(err) > band)
        pull = (err - copysign(band, err)) / (RATE * PULL_TAU);
    g.corr = g.integ + pull;
    atomic_store(&g.st_err, err);
    atomic_store(&g.st_corr, g.corr);
    atomic_store(&g.st_integ, g.integ);
    g.step = 1.0 + g.corr;
}

// One mic sample at graph tick t: subtract the predicted echo, block by block.
static inline float cancel(int64_t t, float mic) {
    g.mic_ring[t & (RING - 1)] = mic;
    if (g.aec_state != AEC_RUN)
        return mic;
    int ph = (int)(t % AEC_N);
    if (ph == 0) {
        g.blk_start = -1;
        int64_t from = t - g.aec_d;
        if (from + AEC_N <= atomic_load_explicit(&g.ref_end, memory_order_acquire)) {
            float r[AEC_N], e = 0;
            for (int i = 0; i < AEC_N; i++) {
                r[i] = g.ref_ring[(from + i) & (RING - 1)];
                e += r[i] * r[i];
            }
            aec_predict(g.aec, r, g.pred);
            g.blk_start = t;
            g.blk_learn = e > AEC_N * 1e-7f; // nothing to learn while the speakers are silent
        }
    }
    if (g.blk_start < 0)
        return mic; // no prediction for this block: it started mid-block, or no reference yet
    float out = mic - g.pred[ph];
    g.err[ph] = out;
    if (mic == 0.0f) {
        if (++g.zero_run >= ZERO_RUN) { // the phone sends silence (muted, or its mic taken)
            g.blk_learn = false;
            out = 0.0f;
        }
    } else {
        g.zero_run = 0;
    }
    if (ph == AEC_N - 1) {
        aec_update(g.aec, g.err, g.blk_learn);
        g.blk_start = -1;
    }
    return out;
}

static void on_process(void* data) {
    (void)data;
    int64_t t_in = now_ns();
    if (g.last_cycle && t_in - g.last_cycle > 8000000)
        atomic_fetch_add(&g.st_late, 1);
    g.last_cycle = t_in;
    struct pw_buffer* b = pw_stream_dequeue_buffer(g.stream);
    if (!b)
        return;
    struct spa_data* d = &b->buffer->datas[0];
    float* dst = d->data;
    if (!dst)
        return;
    uint32_t n = b->requested ? (uint32_t)b->requested : 256;
    if (n > d->maxsize / sizeof(float))
        n = d->maxsize / sizeof(float);
    if (n > MAX_POP)
        n = MAX_POP;
    struct pw_time tm;
    bool timed = pw_stream_get_time_n(g.stream, &tm, sizeof(tm)) == 0 && tm.rate.denom == RATE;
    int64_t T = (int64_t)tm.ticks;
    if (timed && g.ref_now) {
        int64_t off = T - g.ref_ticks - llround((double)(tm.now - g.ref_now) * RATE / 1e9);
        if (!g.offset_known || off != g.tick_offset) {
            g.tick_offset = off;          // first sight, or the speaker stream restarted
            g.offset_known = true;
            atomic_store(&g.ref_end, 0);  // what was written under the old mapping is unusable
            if (g.aec_state == AEC_RUN)
                g.aec_state = AEC_ACQUIRE;
        }
    }

    struct epoch* fresh = atomic_exchange_explicit(&g.pending, NULL, memory_order_acquire);
    if (fresh) {
        if (g.active)
            g.to_retire = g.active; // a third epoch before the second is freed is not realistic
        g.active = fresh;
        reset_loop_state();
        if (g.aec_state == AEC_RUN)
            g.aec_state = AEC_ACQUIRE; // a new stream settles at a new delay
    }
    if (g.to_retire) {
        struct epoch* none = NULL;
        if (atomic_compare_exchange_strong(&g.retired, &none, g.to_retire))
            g.to_retire = NULL;
    }

    // Echo cancellation state, as the main thread asks.
    if (atomic_load(&g.aec_want) == AEC_OFF || !timed) {
        g.aec_state = AEC_OFF;
    } else {
        if (g.aec_state == AEC_OFF)
            g.aec_state = AEC_ACQUIRE;
        if (atomic_exchange(&g.aec_new_delay, false)) {
            g.aec_d = atomic_load(&g.aec_delay);
            aec_reset(g.aec);
            g.aec_state = AEC_RUN;
            g.blk_start = -1;
        }
    }
    if (timed && T != atomic_load_explicit(&g.mic_end, memory_order_relaxed))
        g.blk_start = -1; // the graph skipped or restarted: blocks realign on the next boundary

    if (!g.active) {
        memset(dst, 0, n * sizeof(float));
    } else {
        double pos0 = g.pos;
        if (g.skip) {
            // Emergency cut: jump `skip` samples ahead, crossfading from the old position.
            g.pos += g.skip;
            if (g.aec_state == AEC_RUN)
                g.aec_state = AEC_ACQUIRE;
        }
        int64_t keep = (int64_t)floor(pos0 < g.pos ? pos0 : g.pos) - RS_HALF;
        rs_need((int64_t)floor(g.pos + (n - 1) * g.step) + RS_HALF + 1, keep);
        for (uint32_t i = 0; i < n; i++) {
            float y = rs_at(g.pos + i * g.step);
            if (g.skip && i < SKIP_FADE) {
                float w = 0.5f - 0.5f * cosf((float)M_PI * (float)i / SKIP_FADE);
                y = rs_at(pos0 + i * g.step) * (1.0f - w) + y * w;
            }
            dst[i] = y;
        }
        g.pos += n * g.step;
        g.skip = 0;

        struct epoch* e = g.active;
        unsigned s0 = atomic_load_explicit(&e->seq, memory_order_relaxed);
        atomic_store_explicit(&e->seq, s0 + 1, memory_order_relaxed);
        atomic_thread_fence(memory_order_release);
        atomic_store_explicit(&e->pub_t, t_in, memory_order_relaxed);
        atomic_store_explicit(&e->pub_popped, g.popped, memory_order_relaxed);
        atomic_store_explicit(&e->pub_rate, (double)RATE * g.step * 1e-9, memory_order_relaxed);
        atomic_store_explicit(&e->seq, s0 + 2, memory_order_release);
        g.win_out += n;
        if (g.win_out >= WINDOW) {
            g.win_out = 0;
            end_window();
        }
    }

    static float raw[MAX_POP];
    if (g.dump_fd >= 0)
        memcpy(raw, dst, n * sizeof(float));
    if (timed) {
        static double in_pow, out_pow;
        static uint32_t pow_n;
        for (uint32_t i = 0; i < n; i++) {
            float m = dst[i];
            dst[i] = cancel(T + i, m);
            in_pow += (double)m * m;
            out_pow += (double)dst[i] * dst[i];
        }
        atomic_store_explicit(&g.mic_end, T + n, memory_order_release);
        if ((pow_n += n) >= RATE) {
            atomic_store(&g.st_erle, g.aec_state == AEC_RUN && out_pow > 0 ? 10 * log10(in_pow / out_pow) : 0);
            in_pow = out_pow = 0;
            pow_n = 0;
        }
    }

    atomic_store_explicit(&g.st_aec, g.aec_state, memory_order_relaxed);
    if (g.dump_fd >= 0) { // debug: (mic, output) pairs
        static float pairs[2 * MAX_POP];
        for (uint32_t i = 0; i < n; i++) {
            pairs[2 * i] = raw[i];
            pairs[2 * i + 1] = dst[i];
        }
        if (write(g.dump_fd, pairs, 2 * n * sizeof(float)) < 0)
            g.dump_fd = -1;
    }
    d->chunk->offset = 0;
    d->chunk->stride = sizeof(float);
    d->chunk->size = n * sizeof(float);
    b->size = n;
    pw_stream_queue_buffer(g.stream, b);
    int64_t took = now_ns() - t_in;
    if (took > atomic_load(&g.st_max_ns))
        atomic_store(&g.st_max_ns, took);
    if (took > 1000000)
        atomic_fetch_add(&g.st_slow, 1);
}

// The speakers: what the default output plays, on the same graph clock as the mic.
static void on_ref_process(void* data) {
    (void)data;
    struct pw_buffer* b = pw_stream_dequeue_buffer(g.ref_stream);
    if (!b)
        return;
    struct spa_data* d = &b->buffer->datas[0];
    struct pw_time tm;
    if (pw_stream_get_time_n(g.ref_stream, &tm, sizeof(tm)) == 0 && tm.rate.denom == RATE) {
        g.ref_now = tm.now;
        g.ref_ticks = (int64_t)tm.ticks;
        int64_t T = (int64_t)tm.ticks + g.tick_offset, end = atomic_load_explicit(&g.ref_end, memory_order_relaxed);
        uint32_t n = d->chunk ? d->chunk->size / sizeof(float) : 0;
        if (!g.offset_known)
            n = 0;
        if (end == 0)
            end = T;
        const float* src = d->data ? (const float*)((uint8_t*)d->data + d->chunk->offset) : NULL;
        for (int64_t t = end; t < T && T - t < RING; t++)
            g.ref_ring[t & (RING - 1)] = 0; // a gap: nothing was played
        for (uint32_t i = 0; i < n; i++)
            g.ref_ring[(T + i) & (RING - 1)] = src ? src[i] : 0;
        atomic_store_explicit(&g.ref_end, T + n, memory_order_release);
    }
    pw_stream_queue_buffer(g.ref_stream, b);
}

static void ref_update_active(void) {
    if (g.ref_stream)
        pw_stream_set_active(g.ref_stream, g.streaming && atomic_load(&g.aec_want) != AEC_OFF);
}

static void on_state(void* data, enum pw_stream_state old, enum pw_stream_state state, const char* error) {
    (void)data;
    (void)old;
    if (state == PW_STREAM_STATE_ERROR) {
        fprintf(stderr, "stream error: %s\n", error ? error : "?");
        pw_main_loop_quit(g.loop);
    }
    // Capture the speakers only while someone listens to the mic.
    g.streaming = state == PW_STREAM_STATE_STREAMING;
    ref_update_active();
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .state_changed = on_state,
    .process = on_process,
};

static const struct pw_stream_events ref_events = {
    PW_VERSION_STREAM_EVENTS,
    .process = on_ref_process,
};

static const struct spa_pod* mono_format(struct spa_pod_builder* pb) {
    struct spa_audio_info_raw info = { .format = SPA_AUDIO_FORMAT_F32, .rate = RATE, .channels = 1 };
    info.position[0] = SPA_AUDIO_CHANNEL_MONO;
    return spa_format_audio_raw_build(pb, SPA_PARAM_EnumFormat, &info);
}

static void ref_connect(void) {
    if (g.ref_stream)
        return;
    struct pw_properties* props = pw_properties_new(
        PW_KEY_NODE_NAME, "earshot.speakers", PW_KEY_NODE_DESCRIPTION, "Earshot echo reference",
        PW_KEY_MEDIA_TYPE, "Audio", PW_KEY_MEDIA_CATEGORY, "Capture", PW_KEY_MEDIA_ROLE, "DSP",
        PW_KEY_STREAM_CAPTURE_SINK, "true", // the default output, following it when it changes
        PW_KEY_NODE_GROUP, "earshot",        // scheduled with the mic, on the same clock
        PW_KEY_NODE_LATENCY, "256/48000", PW_KEY_NODE_RATE, "1/48000", NULL);
    g.ref_stream = pw_stream_new(g.core, "Earshot speakers", props);
    if (!g.ref_stream)
        return;
    pw_stream_add_listener(g.ref_stream, &g.ref_listener, &ref_events, NULL);
    uint8_t pod[1024];
    struct spa_pod_builder pb = SPA_POD_BUILDER_INIT(pod, sizeof(pod));
    const struct spa_pod* params[1] = { mono_format(&pb) };
    pw_stream_connect(g.ref_stream, PW_DIRECTION_INPUT, PW_ID_ANY,
                      PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS | PW_STREAM_FLAG_RT_PROCESS
                          | PW_STREAM_FLAG_INACTIVE,
                      params, 1);
}

static void set_aec(bool on) {
    atomic_store(&g.aec_want, on ? AEC_ACQUIRE : AEC_OFF);
    if (on)
        ref_connect();
    ref_update_active();
    fprintf(stderr, "echo cancellation %s\n", on ? "on" : "off");
}

// Bulk delay: measured from the last stretch of mic and speaker output (main thread, ~10 ms).
static int measure_delay(void) {
    int64_t end = atomic_load(&g.ref_end), m = atomic_load(&g.mic_end);
    if (m < end)
        end = m;
    end -= 4096; // well behind what the RT thread is writing
    if (end < ACQ_LEN)
        return -1;
    double e = 0;
    for (int i = 0; i < ACQ_LEN; i++) {
        g.acq_ref[i] = g.ref_ring[(end - ACQ_LEN + i) & (RING - 1)];
        g.acq_mic[i] = g.mic_ring[(end - ACQ_LEN + i) & (RING - 1)];
        e += (double)g.acq_ref[i] * g.acq_ref[i];
    }
    if (e < ACQ_LEN * 1e-6)
        return -1; // the speakers were (nearly) silent: nothing to measure against
    float q;
    int lag = aec_find_delay(g.acq_ref, g.acq_mic, ACQ_LEN, MAX_LAG, &q);
    return lag >= AEC_MARGIN ? lag : -1;
}

static void aec_tick(void) {
    if (atomic_load(&g.aec_want) == AEC_OFF || !g.streaming)
        return;
    int state = atomic_load(&g.st_aec);
    if (state == AEC_ACQUIRE) {
        int lag = measure_delay();
        if (lag >= 0) {
            atomic_store(&g.aec_delay, lag - AEC_MARGIN);
            atomic_store(&g.aec_new_delay, true);
            g.recheck_in = RECHECK_S;
            g.recheck_miss = 0;
        }
    } else if (state == AEC_RUN && --g.recheck_in <= 0) {
        // The delay holds still, but a cut or a pull of the queue can move it: follow.
        g.recheck_in = RECHECK_S;
        int lag = measure_delay();
        if (lag >= 0 && abs(lag - AEC_MARGIN - atomic_load(&g.aec_delay)) > 96) {
            if (++g.recheck_miss >= 2) {
                atomic_store(&g.aec_delay, lag - AEC_MARGIN);
                atomic_store(&g.aec_new_delay, true);
                g.recheck_miss = 0;
            }
        } else if (lag >= 0) {
            g.recheck_miss = 0;
        }
    }
}

static void on_control(void* data, int fd, uint32_t mask) {
    (void)data;
    static char line[256];
    static size_t len;
    if (mask & (SPA_IO_ERR | SPA_IO_HUP)) {
        pw_main_loop_quit(g.loop); // the daemon went away: so do we
        return;
    }
    ssize_t r = read(fd, line + len, sizeof(line) - 1 - len);
    if (r <= 0) {
        pw_main_loop_quit(g.loop);
        return;
    }
    len += (size_t)r;
    char* nl;
    while ((nl = memchr(line, '\n', len))) {
        *nl = 0;
        if (!strcmp(line, "aec on"))
            set_aec(true);
        else if (!strcmp(line, "aec off"))
            set_aec(false);
        size_t used = (size_t)(nl - line) + 1;
        memmove(line, nl + 1, len - used);
        len -= used;
    }
    if (len == sizeof(line) - 1)
        len = 0;
}

static void on_core_error(void* data, uint32_t id, int seq, int res, const char* message) {
    (void)data;
    (void)seq;
    fprintf(stderr, "pipewire: %s\n", message);
    if (id == PW_ID_CORE && res == -EPIPE)
        pw_main_loop_quit(g.loop); // PipeWire went away; the daemon restarts us
}

static const struct pw_core_events core_events = {
    PW_VERSION_CORE_EVENTS,
    .error = on_core_error,
};

static void load_clock(void) {
    FILE* f = g.clock_path ? fopen(g.clock_path, "r") : NULL;
    double ppm;
    if (f && fscanf(f, "%lf", &ppm) == 1 && fabs(ppm) < CORR_MAX * 1e6) {
        g.integ = ppm * 1e-6;
        g.have_integ = true;
    }
    if (f)
        fclose(f);
    g.learned = g.have_integ;
    g.corr = g.integ;
}

static void save_clock(void) {
    if (!g.clock_path || !g.have_integ)
        return;
    char tmp[4096];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g.clock_path);
    FILE* f = fopen(tmp, "w");
    if (!f)
        return;
    fprintf(f, "%.4f\n", atomic_load(&g.st_integ) * 1e6);
    if (fclose(f) == 0)
        rename(tmp, g.clock_path);
}

static void on_tick(void* data, uint64_t expirations) {
    (void)data;
    (void)expirations;
    static unsigned ticks;
    if (g.stats_path) {
        FILE* f = fopen(g.stats_path, ticks % 3600 == 0 ? "w" : "a"); // keeps the last hour or so
        if (f) {
            fprintf(f, "%.3f state=%d err=%.1f corr_ppm=%.3f ratio_ppm=%.3f skips=%u late=%u slow=%u max_us=%.0f"
                    " aec=%d delay=%d reduction_db=%.1f mic_ahead=%lld\n",
                    (double)now_ns() / 1e9, atomic_load(&g.st_state), atomic_load(&g.st_err),
                    atomic_load(&g.st_corr) * 1e6, atomic_load(&g.st_integ) * 1e6,
                    atomic_load(&g.st_skips),
                    atomic_load(&g.st_late), atomic_load(&g.st_slow), (double)atomic_load(&g.st_max_ns) / 1e3,
                    atomic_load(&g.st_aec), atomic_load(&g.aec_delay) + AEC_MARGIN, atomic_load(&g.st_erle),
                    (long long)(atomic_load(&g.mic_end) - atomic_load(&g.ref_end)));
            fclose(f);
        }
    }
    aec_tick();
    if (++ticks % 60 == 0)
        save_clock();
}

static void on_signal(void* data, int sig) {
    (void)data;
    (void)sig;
    pw_main_loop_quit(g.loop);
}

int main(int argc, char** argv) {
    g.latency_ms = 50;
    g.node = "earshot.mic";
    g.description = "Earshot";
    g.port = PORT_BASE;
    static const struct option opts[] = {
        { "latency", required_argument, 0, 'l' }, { "node", required_argument, 0, 'n' },
        { "description", required_argument, 0, 'd' }, { "clock", required_argument, 0, 'c' },
        { "stats", required_argument, 0, 's' }, { "port", required_argument, 0, 'p' },
        { "aec", required_argument, 0, 'a' }, { "control", no_argument, 0, 'C' }, { 0, 0, 0, 0 } };
    bool aec_on = false;
    for (int c; (c = getopt_long(argc, argv, "", opts, NULL)) != -1;) {
        switch (c) {
        case 'l': g.latency_ms = (unsigned)atoi(optarg); break;
        case 'n': g.node = optarg; break;
        case 'd': g.description = optarg; break;
        case 'c': g.clock_path = optarg; break;
        case 's': g.stats_path = optarg; break;
        case 'p': g.port = atoi(optarg); break;
        case 'a': aec_on = !strcmp(optarg, "on"); break;
        case 'C': g.control = true; break;
        default: return 2;
        }
    }
    if (g.latency_ms < 5 || g.latency_ms > 1000)
        return fprintf(stderr, "latency out of range\n"), 2;
    load_clock();
    const char* dump = getenv("EARSHOT_RX_DUMP");
    g.dump_fd = dump ? open(dump, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644) : -1;
    g.step = 1.0 + g.integ;
    rs_init();
    g.aec = aec_new();
    g.blk_start = -1;
    if (!g.aec)
        return 1;

    roc_log_set_level(ROC_LOG_ERROR);
    roc_log_set_handler(on_roc_log, NULL);
    roc_context_config ccfg;
    memset(&ccfg, 0, sizeof(ccfg));
    ccfg.max_packet_size = MAX_PACKET;
    ccfg.max_frame_size = MAX_POP * sizeof(float);
    roc_media_encoding enc = { .rate = RATE, .format = ROC_FORMAT_PCM_FLOAT32,
                               .channels = ROC_CHANNEL_LAYOUT_MULTITRACK, .tracks = 1 };
    if (roc_context_open(&ccfg, &g.roc) != 0 || roc_context_register_encoding(g.roc, ENCODING_ID, &enc) != 0)
        return fprintf(stderr, "roc context failed\n"), 1;

    g.fd_source = bind_udp(g.port);
    g.fd_repair = bind_udp(g.port + 1);
    g.fd_control = bind_udp(g.port + 2);
    if (g.fd_source < 0 || g.fd_repair < 0 || g.fd_control < 0)
        return 1;

    pw_init(&argc, &argv);
    g.loop = pw_main_loop_new(NULL);
    struct pw_loop* l = pw_main_loop_get_loop(g.loop);
    pw_loop_add_signal(l, SIGINT, on_signal, NULL);
    pw_loop_add_signal(l, SIGTERM, on_signal, NULL);
    struct spa_source* tick = pw_loop_add_timer(l, on_tick, NULL);
    struct timespec one = { 1, 0 };
    pw_loop_update_timer(l, tick, &one, &one, false);

    if (g.control)
        pw_loop_add_io(l, STDIN_FILENO, SPA_IO_IN | SPA_IO_ERR | SPA_IO_HUP, false, on_control, NULL);
    g.context = pw_context_new(l, NULL, 0);
    g.core = g.context ? pw_context_connect(g.context, NULL, 0) : NULL;
    if (!g.core)
        return fprintf(stderr, "can't connect to PipeWire\n"), 1;
    pw_core_add_listener(g.core, &g.core_listener, &core_events, NULL);

    struct pw_properties* props = pw_properties_new(
        PW_KEY_NODE_NAME, g.node, PW_KEY_NODE_DESCRIPTION, g.description,
        PW_KEY_MEDIA_CLASS, "Audio/Source", PW_KEY_NODE_LATENCY, "256/48000",
        PW_KEY_NODE_RATE, "1/48000", PW_KEY_DEVICE_ICON_NAME, "phone",
        PW_KEY_NODE_GROUP, "earshot",
        // never become the default mic on its own; only when the phone asks
        PW_KEY_PRIORITY_SESSION, "100", PW_KEY_PRIORITY_DRIVER, "100", NULL);
    g.stream = pw_stream_new(g.core, "Earshot", props);
    if (!g.stream)
        return fprintf(stderr, "stream failed\n"), 1;
    pw_stream_add_listener(g.stream, &g.stream_listener, &stream_events, NULL);

    uint8_t pod[1024];
    struct spa_pod_builder pb = SPA_POD_BUILDER_INIT(pod, sizeof(pod));
    const struct spa_pod* params[1] = { mono_format(&pb) };
    if (pw_stream_connect(g.stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
                          PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS | PW_STREAM_FLAG_RT_PROCESS,
                          params, 1) < 0)
        return fprintf(stderr, "connect failed\n"), 1;

    atomic_store(&g.running, true);
    pthread_t net;
    pthread_create(&net, NULL, net_thread, NULL);
    if (aec_on)
        set_aec(true);
    pw_main_loop_run(g.loop);

    save_clock();
    if (g.ref_stream)
        pw_stream_destroy(g.ref_stream);
    pw_stream_destroy(g.stream);
    atomic_store(&g.running, false);
    pthread_join(net, NULL);
    epoch_close(g.active);
    epoch_close(g.to_retire);
    epoch_close(atomic_exchange(&g.pending, NULL));
    epoch_close(atomic_exchange(&g.retired, NULL));
    roc_context_close(g.roc);
    aec_free(g.aec);
    pw_core_disconnect(g.core);
    pw_context_destroy(g.context);
    pw_main_loop_destroy(g.loop);
    pw_deinit();
    return 0;
}
