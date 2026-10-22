#!/usr/bin/env bash
# Records speaker output + phone mic in one stereo capture while clicks play; prints the delay.
cd "$(dirname "$0")"
SINK=$(pactl get-default-sink)
timeout 16 pw-record --target earshot.mic /dev/null & KEEP=$!
MOD=$(pactl load-module module-null-sink sink_name=es_lat sink_properties=node.description=es_lat channel_map=front-left,front-right)
sleep 3
pw-link "$SINK:monitor_FL" es_lat:playback_FL; pw-link earshot.mic:receive_MONO es_lat:playback_FR
timeout 6 pw-record --target es_lat -P '{ stream.capture.sink = true }' --rate 48000 --channels 2 --format f32 lat.wav &
REC=$!
sleep 1; pw-play clicks.wav; wait $REC
pactl unload-module $MOD; kill $KEEP 2>/dev/null; wait 2>/dev/null
python3 -I latency.py lat.wav
