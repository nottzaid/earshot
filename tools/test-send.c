// PC-side check of the link: streams a 440 Hz tone for N seconds, paced like a mic.
#include "../native/es_link.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int main(int argc, char** argv) {
    const char* host = argc > 1 ? argv[1] : "127.0.0.1";
    int seconds = argc > 2 ? atoi(argv[2]) : 5;
    es_link_params p = { host, 2500, 10, 5 };
    es_link* link = es_link_open(&p);
    if (!link) { fprintf(stderr, "open failed\n"); return 1; }

    enum { CHUNK = 240 }; // 5 ms
    float buf[CHUNK];
    double phase = 0;
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    for (long n = 0; n < (long)seconds * ES_RATE / CHUNK; n++) {
        for (int i = 0; i < CHUNK; i++) {
            buf[i] = 0.5f * (float)sin(phase);
            phase += 2 * M_PI * 440.0 / ES_RATE;
        }
        if (es_link_write(link, buf, CHUNK)) { fprintf(stderr, "write failed\n"); return 1; }
        t.tv_nsec += 5000000;
        if (t.tv_nsec >= 1000000000) { t.tv_nsec -= 1000000000; t.tv_sec++; }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &t, NULL);
        if (n % 200 == 0) printf("t=%lds receivers=%d\n", n * CHUNK / ES_RATE, es_link_receivers(link));
    }
    es_link_close(link);
    return 0;
}
