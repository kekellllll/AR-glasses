#!/bin/bash
# Download models for Retail AR Demo - ProductARMobile
# Usage: ./download_models.sh [HF_TOKEN]
#
# Models are stored in two locations:
#   - Small models (<100MB) → app/src/main/assets/ (bundled in APK)
#   - Large models (>100MB) → pushed to phone via adb

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/../ProductARMobile"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"

HF_TOKEN="${1:-$HF_TOKEN}"
if [ -z "$HF_TOKEN" ]; then
    echo "Warning: No HuggingFace token provided. Some downloads may fail."
    echo "Usage: $0 <HF_TOKEN>"
fi

AUTH_HEADER=""
if [ -n "$HF_TOKEN" ]; then
    AUTH_HEADER="-H \"Authorization: Bearer $HF_TOKEN\""
fi

mkdir -p "$ASSETS_DIR"

echo "═══════════════════════════════════════"
echo "  Retail AR Demo — Model Downloader"
echo "═══════════════════════════════════════"

# ── 1. Whisper tiny (STT) ──
WHISPER_FILE="$ASSETS_DIR/ggml-tiny.bin"
if [ -f "$WHISPER_FILE" ]; then
    echo "✓ Whisper tiny already exists ($(du -h "$WHISPER_FILE" | cut -f1))"
else
    echo "↓ Downloading Whisper tiny model (75MB)..."
    curl -L -H "Authorization: Bearer $HF_TOKEN" \
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin" \
        -o "$WHISPER_FILE"
    echo "✓ Whisper tiny downloaded"
fi

# ── 2. Phi-3-mini LLM (too large for assets, push to phone) ──
LLM_DIR="$PROJECT_DIR/models"
mkdir -p "$LLM_DIR"
LLM_FILE="$LLM_DIR/phi-3-mini-4k-instruct-q4_k_m.gguf"
if [ -f "$LLM_FILE" ]; then
    echo "✓ Phi-3-mini already exists ($(du -h "$LLM_FILE" | cut -f1))"
else
    echo "↓ Downloading Phi-3-mini-4k Q4_K_M (2.2GB)..."
    echo "  This may take several minutes..."
    curl -L -H "Authorization: Bearer $HF_TOKEN" \
        "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf" \
        -o "$LLM_FILE"
    echo "✓ Phi-3-mini downloaded"
fi

echo ""
echo "═══════════════════════════════════════"
echo "  Download complete!"
echo "═══════════════════════════════════════"
echo ""
echo "To push LLM to phone:"
echo "  adb shell mkdir -p /sdcard/Android/data/com.ultronai.productarmobile/files/models/"
echo "  adb push $LLM_FILE /sdcard/Android/data/com.ultronai.productarmobile/files/models/"
echo ""
echo "Asset files in APK:"
ls -lh "$ASSETS_DIR/"
