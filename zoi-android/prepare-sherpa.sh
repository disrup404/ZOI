#!/usr/bin/env bash
set -euo pipefail

VERSION="2.0.1"
APP_DIR="$(cd "$(dirname "$0")" && pwd)/app"
LIB_DIR="$APP_DIR/libs"
ASSET_DIR="$APP_DIR/src/main/assets/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$LIB_DIR" "$ASSET_DIR"

AAR="$LIB_DIR/sherpa-onnx-$VERSION.aar"
if [ ! -s "$AAR" ]; then
  echo "Downloading sherpa-onnx Android library $VERSION..."
  curl -fL --retry 3 \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar" \
    -o "$AAR"
fi

if [ ! -s "$ASSET_DIR/tokens.txt" ] || \
   [ ! -s "$ASSET_DIR/bpe.model" ] || \
   [ ! -s "$ASSET_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" ]; then
  echo "Downloading the English GigaSpeech wake-word model..."
  ARCHIVE="$TMP_DIR/kws-model.tar.bz2"
  curl -fL --retry 3 \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2" \
    -o "$ARCHIVE"
  tar -xjf "$ARCHIVE" -C "$TMP_DIR"

  MODEL_SOURCE="$TMP_DIR/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
  cp "$MODEL_SOURCE/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSET_DIR/"
  cp "$MODEL_SOURCE/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" "$ASSET_DIR/"
  cp "$MODEL_SOURCE/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSET_DIR/"
  cp "$MODEL_SOURCE/tokens.txt" "$ASSET_DIR/"
  cp "$MODEL_SOURCE/bpe.model" "$ASSET_DIR/"
fi

# sherpa-onnx requires BPE-tokenized keywords, not raw English phrases.
cat > "$ASSET_DIR/keywords_raw.txt" <<'PHRASES'
HELLO ZOI
HEY ZOI
HI ZOI
OKAY ZOI
PHRASES

python3 -m pip install --disable-pip-version-check --quiet sherpa-onnx
sherpa-onnx-cli text2token \
  --tokens "$ASSET_DIR/tokens.txt" \
  --tokens-type bpe \
  --bpe-model "$ASSET_DIR/bpe.model" \
  "$ASSET_DIR/keywords_raw.txt" \
  "$ASSET_DIR/keywords.txt"

test -s "$ASSET_DIR/keywords.txt"
echo "Configured wake phrases:"
cat "$ASSET_DIR/keywords_raw.txt"
echo "sherpa-onnx Android dependency and English wake-word model are ready."
du -sh "$AAR" "$ASSET_DIR"
