# Earshot

Your phone's microphone, on your Linux PC, over Wi-Fi.

The phone captures in Android's low-latency path and streams uncompressed 48 kHz audio with
forward error correction ([ROC](https://roc-streaming.org)). On the PC, PipeWire's ROC module
turns the stream into an ordinary mic called **Earshot** that any app can use. A small daemon
keeps a control channel with the phone, so the app knows which PC apps are listening and
mute and default-mic stay in sync both ways.

## Layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Compose, Oboe; native engine in `app/src/main/cpp`) |
| `native/es_link.{h,c}` | The ROC sender, shared by the app and the PC test tool |
| `native/build-roc-android.sh` | Builds roc-toolkit 0.4.0 for arm64 Android and installs it into the app |
| `pc/earshotd` | PC daemon: receiver, discovery, pairing, control channel |
| `pc/earshotd.service` | systemd user unit |
| `tools/` | Loopback test sender and recording analysis used to tune the receiver |

## PC

Needs `pipewire-roc` (pulls in `roc-toolkit`). Then run `pc/earshotd`, or install the user unit.

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

## Phone

```
native/build-roc-android.sh      # once, or after changing roc
cd android && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the key named in `android/keystore.properties` (not in git).

## Latency modes

| Mode | PC jitter buffer | Packet | FEC block |
|---|---|---|---|
| Tight | 30 ms | 2.5 ms | 6 + 3 |
| Balanced | 50 ms | 2.5 ms | 10 + 5 |
| Steady | 100 ms | 5 ms | 10 + 6 |

The receiver uses ROC's speex resampler and its `gradual` latency tuner. In loopback tests the
built-in resampler added about +1 dB of gain, and `responsive` tuning added audible steps
(windows at −37 dB THD+N, against −90 dB with `gradual`).
