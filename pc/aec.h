// Earshot's echo canceller: removes what this PC's speakers put into the phone's mic, and
// nothing else. The output is the mic minus a linear prediction of the speaker sound; there is
// no suppressor, gate, noise reduction or level change, so voices, the TV and the room pass
// through untouched.
//
// The prediction is a partitioned-block frequency-domain Kalman filter (Enzner & Vary 2006;
// diagonalized as in Kuech et al. 2014) over 170 ms of echo path, from 25 ms before a bulk delay
// found by GCC-PHAT. The Kalman step is large while the echo path is uncertain and small whenever the mic
// holds something the speakers can't explain (someone talking, the TV), so it does not learn
// those or bend them.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#define AEC_N 128                  // block length, samples
#define AEC_TAPS_MS 170
#define AEC_P ((AEC_TAPS_MS * 48 + AEC_N - 1) / AEC_N) // blocks of echo path
#define AEC_MARGIN 1200            // filter starts 25 ms before the measured bulk delay: an estimate
                                   // that lands late must not leave the direct sound outside it

typedef struct aec aec;

aec* aec_new(void);
void aec_free(aec* a);
void aec_reset(aec* a);            // forget the echo path (it made the echo worse, or blew up)
// The speaker history no longer lines up (a new stream, a cut, a remapped clock): drop it, keep
// the echo path.
void aec_forget(aec* a);
// Move the filter's start `k` blocks later (earlier if negative), keeping the echo path (and the
// kept good one) where it is in absolute lag: what falls off one end is dropped, the other end
// starts empty. Also forgets.
void aec_shift(aec* a, int k);
// A new stream: go back to the last echo path that cancelled well. One that went wrong since
// (shrunk while the echo was faint or the path stale) fits the last stream, not this one.
void aec_recall(aec* a);
// Rebuild the speaker history from `hist`: the AEC_P * AEC_N samples before the next block's
// reference, oldest first, so the prediction is whole from the next block on.
void aec_refill(aec* a, const float* hist);

// One block: `ref` is the speaker signal AEC_N samples long, already delayed by the bulk delay
// (minus the margin); `pred` receives the predicted echo for the mic block that lines up with it.
void aec_predict(aec* a, const float* ref, float* pred);
// After the mic block is known: `err` = mic - pred. Learns from it unless `learn` is false (the
// filter holds still: only the share follows).
// Returns true when the prediction was making the mic louder (it then adapts quickly), or blew up
// (it then starts over).
bool aec_update(aec* a, const float* err, bool learn);
// How much of the next block's prediction to take off the mic, 0 to 16: as much as has lately
// matched the mic (least squares), so the output is never louder than the mic, even while the
// filter is wrong (a stale echo path at the start of a stream). 0 until a
// block has been judged.
float aec_share(const aec* a);

// How far the echo moved: `mic` against `pred`, the echo the current filter expects, AEC_ALIGN
// samples each. Returns the shift in whole samples (positive: the echo comes later), within
// +-AEC_ALIGN_MAX, for the caller to move the filter by; the fraction of a sample left over it
// applies to the filter itself. AEC_NO_SHIFT when the match is too weak (no echo, or a filter
// that knows nothing). Preallocated: safe for the RT thread.
#define AEC_ALIGN 4096
#define AEC_ALIGN_MAX 1024
#define AEC_NO_SHIFT (-1000000)
int aec_align(aec* a, const float* pred, const float* mic);
// The echo path, to keep across runs: AEC_PATH_FLOATS floats.
#define AEC_PATH_FLOATS (AEC_P * (AEC_N + 1) * 2)
void aec_get_path(const aec* a, float* path);
void aec_set_path(aec* a, const float* path);
bool aec_knows_path(const aec* a); // anything learned at all

// Bulk delay of `mic` behind `ref` (n samples each, n a power of two), in samples, searched up to
// max_lag. Returns -1 when the match is too weak to trust. Allocates; not for the RT thread.
int aec_find_delay(const float* ref, const float* mic, int n, int max_lag, float* confidence);
