#!/usr/bin/env bash
# Records what the speakers play (L, R) and what the phone hears, sample-aligned, into one
# 3-channel file for developing the echo canceller offline. Optionally plays a YouTube URL or
# search through the speakers meanwhile (audio only, no window).
#   aec-capture.sh out.wav seconds [youtube-url-or-"ytsearch1:..."] [mic-node]
set -euo pipefail
out=$1 secs=$2 media=${3:-} mic=${4:-earshot.mic}
sink=$(pactl get-default-sink)
port=$(pw-link -o | grep -m1 "^$mic:" )

timeout $((secs + 6)) pw-record --target "$mic" /dev/null & keep=$!   # keeps the phone live
mod=$(pactl load-module module-null-sink sink_name=es_aec sink_properties=node.description=es_aec \
    channels=3 channel_map=front-left,front-right,front-center)
trap 'pactl unload-module $mod; kill $keep 2>/dev/null || true' EXIT
sleep 2
pw-link "$sink:monitor_FL" es_aec:playback_FL
pw-link "$sink:monitor_FR" es_aec:playback_FR
pw-link "$port" es_aec:playback_FC
if [[ -n $media ]]; then
    mpv --no-video --really-quiet --no-terminal --length="$secs" "ytdl://$media" & player=$!
fi
timeout "$secs" pw-record --target es_aec -P '{ stream.capture.sink = true }' \
    --rate 48000 --channels 3 --channel-map FL,FR,FC --format f32 "$out" || true
[[ -n $media ]] && kill "$player" 2>/dev/null || true
