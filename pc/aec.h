// Earshot's echo canceller: removes what this PC's speakers put into the phone's mic, and
// nothing else. The output is the mic minus a linear prediction of the speaker sound; there is
// no suppressor, gate, noise reduction or level change, so voices, the TV and the room pass
// through untouched.
//
// The prediction is a partitioned-block frequency-domain Kalman filter (Enzner & Vary 2006;
// diagonalized as in Kuech et al. 2014) over 150 ms of echo path, after a bulk delay found by
// GCC-PHAT. The Kalman step is large while the echo path is uncertain and small whenever the mic
// holds something the speakers can't explain (someone talking, the TV), so it does not learn
// those or bend them.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#define AEC_N 128                  // block length, samples
#define AEC_TAPS_MS 150
#define AEC_MARGIN 240             // filter starts 5 ms before the measured bulk delay

typedef struct aec aec;

aec* aec_new(void);
void aec_free(aec* a);
void aec_reset(aec* a);            // forget the echo path (new bulk delay, or turned back on)

// One block: `ref` is the speaker signal AEC_N samples long, already delayed by the bulk delay
// (minus the margin); `pred` receives the predicted echo for the mic block that lines up with it.
void aec_predict(aec* a, const float* ref, float* pred);
// After the mic block is known: `err` = mic - pred. Learns from it unless `learn` is false.
void aec_update(aec* a, const float* err, bool learn);

// Bulk delay of `mic` behind `ref` (n samples each, n a power of two), in samples, searched up to
// max_lag. Returns -1 when the match is too weak to trust. Allocates; not for the RT thread.
int aec_find_delay(const float* ref, const float* mic, int n, int max_lag, float* confidence);
