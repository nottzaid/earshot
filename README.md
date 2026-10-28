# Earshot

Your phone's microphone, on your Linux PC, over Wi-Fi.

The phone captures in Android's low-latency path and streams uncompressed 48 kHz audio with
forward error correction ([ROC](https://roc-streaming.org)). On the PC, `earshot-rx` decodes
it with ROC and turns it into an ordinary mic called **Earshot** that any app can use. A small daemon
keeps a control channel with the phone, so the app knows which PC apps are listening and
mute and default-mic stay in sync both ways.

## Layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Compose, Oboe; native engine in `app/src/main/cpp`) |
| `native/es_link.{h,c}` | The ROC sender, shared by the app and the PC test tool |
| `native/build-roc-android.sh` | Builds roc-toolkit 0.4.0 for arm64 Android and installs it into the app |
| `pc/earshotd` | PC daemon: receiver, discovery, pairing, control channel |
| `pc/earshot-rx.c` | The receiver: ROC decoding, steady clock matching, the PipeWire node |
| `pc/earshotd.service` | systemd user unit |
| `tools/` | Loopback test sender and recording analysis used to tune the receiver |

## PC

Needs `roc-toolkit` and PipeWire's headers. Build the receiver with `make -C pc`, then run
`pc/earshotd`, or install the user unit.

Open these from your LAN only: UDP 10001-10003 (audio, repair, RTCP), UDP 10005 (discovery),
TCP 10004 (control).

## Pairing and trust

Pairing is explicit: the phone lists PCs running `earshotd` and pairs only when you tap one; the
PC accepts one phone and shows a desktop notification. The 256-bit secret crosses the network
once, at pairing. After that, every connection starts with a challenge-response in both
directions (HMAC-SHA256 over fresh nonces): the PC proves itself first, so the phone never streams
to an impostor that merely claims the PC's id, and the PC never takes orders from a phone that
can't prove itself. Trust is keyed to the PC's id, not its IP address, so DHCP changes don't matter.

Forget from the phone's settings (the PC forgets too when connected), or run `earshotd unpair`.

What this does not cover: the audio itself is plain RTP. Anyone on the same LAN can listen to it,
or send audio into the Earshot mic. Keep the ports open to your LAN only, and use it on networks
you trust.

### If the stream stalls a few seconds in

Phones set up TDLS (a direct Wi-Fi link to the PC) once traffic gets heavy. Some PC drivers
accept the setup but can't install the link key (`TDLS: Failed to set TPK to the driver` in
wpa_supplicant's log), and everything the phone sends after that is lost until the link times
out, about 30 s later. Decline TDLS on the PC: `wpa_cli -i wlan0 set tdls_disabled 1`, applied on
every connect (a NetworkManager dispatcher script does it).

## Phone

```
native/build-roc-android.sh      # once, or after changing roc
cd android && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Allow "run in the background" when the app asks (or the **Run while locked** switch in its
settings): without it, Doze cuts the phone's Wi-Fi to the app a few minutes after the screen goes
off, even though a foreground service is running.

Release builds are signed with the key named in `android/keystore.properties` (not in git).

## Latency

One setting, the lowest that stays clean: 2.5 ms packets, Reed-Solomon FEC over blocks of
6 + 3 packets, and a 30 ms jitter buffer on the PC.

### Clock matching

The phone's clock and the PC's never tick at quite the same rate (tens of ppm apart), so the
receiver has to resample. ROC's own latency tuner does this by reacting to the jitter buffer,
and every Wi-Fi hiccup made it speed up or slow down: the phone-to-PC delay ramped and jumped by
milliseconds, which an echo canceller can't follow (`responsive` was worse still, with audible
steps). `earshot-rx` runs ROC with tuning off and resamples in PipeWire instead. It reads the
queue at the moment each packet lands and keeps only the peak per second; late packets only
lower readings, so jitter never moves the peak. The clock ratio is the slope of those peaks
over the last 30 s, with the receiver's own corrections added back so it never chases itself,
and the delay is left wherever the stream settled (the queue is only eased back if it wanders
a quarter of the buffer away). Once locked, the delay holds to a sample or two on real Wi-Fi.
The ratio is saved in `~/.local/state/earshot/clock`, so the next stream starts locked; without
it, locking takes about 30 s. Live numbers: `$XDG_RUNTIME_DIR/earshot/rx.stats`.

### Echo cancellation

With **Echo cancellation** on (app settings), the receiver also captures what the PC's default
output plays, on the same graph clock as the mic, and subtracts what that sound put into the
phone's mic. It is subtraction only: the output is the mic minus a linear prediction of the
speaker sound, with no suppressor, gate, noise reduction or level change, so voices, a TV and
the room come through as captured. The prediction is a frequency-domain Kalman filter over
170 ms of echo path (`pc/aec.c`), starting 25 ms before a bulk delay found by GCC-PHAT (the
earliest strong arrival, so the direct sound is never left outside the filter) and rechecked
every 5 s. It adds no latency: the speaker sound reaches the mic 40-50 ms after it is played, so the
prediction is ready before the mic samples arrive.

Measured on music through the PC speakers with a TV on in the room: 17-22 dB less speaker
sound in the mic once settled (a few seconds), the TV's sound unchanged. A voice mixed into the
mic digitally came out within 0.3 dB of itself. Sound the PC doesn't play (phone notifications,
the TV) can't be removed this way, by design. `pc/aec-test` runs the canceller offline on a
capture from `tools/aec-capture.sh`.
