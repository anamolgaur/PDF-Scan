#!/usr/bin/env bash
# Renders the soundtrack (composed in Soundtrack, GothicClassics, GothicNocturnes, ChiptuneSynth)
# and encodes it to the MP3 files the app ships with, in app/src/main/assets/music.
# Needs a JDK, ffmpeg with libmp3lame, and junit/hamcrest jars (JUNIT=path:path).
set -euo pipefail
cd "$(dirname "$0")/.."
JUNIT=${JUNIT:?set JUNIT to the junit and hamcrest jar paths, separated by ':'}
WORK=$(mktemp -d)
SRC=app/src/main/java/com/anamol/pdfscan
TEST=desktop-tests/src/test/java/com/anamol/pdfscan
OUT=app/src/main/assets/music
javac -nowarn -encoding UTF-8 -d "$WORK/classes" -cp "$JUNIT" \
  $SRC/ChiptuneSynth.java $SRC/GothicSynth.java $SRC/Soundtrack.java $SRC/GothicClassics.java \
  $SRC/GothicNocturnes.java $TEST/ChiptuneSynthTest.java $TEST/MusicExport.java
rm -f "$OUT"/*.mp3
java -cp "$WORK/classes:$JUNIT" com.anamol.pdfscan.MusicExport "$WORK/wav" "$OUT"
n=0
for wav in "$WORK"/wav/*.wav; do
  name=$(basename "$wav" .wav)
  n=$((n + 1))
  title=$(python3 -c "import json,sys; t=json.load(open('$OUT/tracks.json'))['tracks']; print([x['title'] for x in t if x['file']=='$name.mp3'][0])")
  ffmpeg -loglevel error -y -i "$wav" -codec:a libmp3lame -b:a 80k -ac 1 -ar 22050 \
    -metadata title="$title" -metadata artist="PDF Scan" -metadata album="PDF Scan soundtrack" \
    -metadata track="$n" "$OUT/$name.mp3"
done
rm -rf "$WORK"
ls -la "$OUT"
