# Retail AI AR Demos

AR glasses + Android phone system for retail product detection, shopping assistance, and shelf analysis. All AI models run on-device (no cloud required).

## Architecture (Setup 1)

```
AR Glasses (RayNeo X3 Pro)          Android Phone
┌─────────────────────┐             ┌──────────────────────────────┐
│ Camera2 → MJPEG     │──WiFi──────→│ MjpegClient                  │
│ Server :8080        │             │   ↓                          │
│                     │             │ RTMDet Detector (TFLite)     │
│ IP display          │             │   ↓                          │
│ Temple actions      │             │ OCR (ML Kit)                 │
└─────────────────────┘             │   ↓                          │
                                    │ RAG (TF-IDF + product DB)   │
                                    │   ↓                          │
                                    │ LLM (Phi-3 via llama.cpp)   │
                                    │   ↓                          │
                                    │ TTS (Android TTS)           │
                                    │                              │
                                    │ STT (Whisper.cpp) ← Mic     │
                                    └──────────────────────────────┘
```

## Projects

| Project | Target | Description |
|---------|--------|-------------|
| `ProductARGlasses` | RayNeo X3 Pro | Camera capture, MJPEG streaming, IP display |
| `ProductARMobile` | Android phone | Detection, OCR, STT, LLM, RAG, TTS, shopping/shelf agents |

## Quick Start

### 1. Clone and download models

```bash
git clone <repo-url>
cd AR-Demos

# Clone native lib sources
cd ProductARMobile/app/src/main/cpp
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git
git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git
cd ../../../../..

# Download models
./scripts/download_models.sh YOUR_HF_TOKEN
```

### 2. Build and install

Open `ProductARGlasses` and `ProductARMobile` in Android Studio separately.

**Requirements:**
- Android Studio Hedgehog+ with NDK 25+ and CMake 3.22.1+
- Kotlin 1.9.x, compileSdk 34
- Phone: Android 9+ (API 28), arm64-v8a

Build and install each app to its target device.

### 3. Push LLM model to phone

The Phi-3-mini GGUF (~2.2GB) is too large for the APK. Push it via adb:

```bash
adb shell mkdir -p /sdcard/Android/data/com.ultronai.productarmobile/files/models/
adb push ProductARMobile/models/phi-3-mini-4k-instruct-q4_k_m.gguf \
    /sdcard/Android/data/com.ultronai.productarmobile/files/models/
```

### 4. Run

1. Connect glasses and phone to the same WiFi (or phone hotspot)
2. Open `ProductARGlasses` app on glasses → note the IP address
3. Open `ProductARMobile` on phone → enter glasses IP → Connect
4. Use the bottom buttons:
   - **Ask**: Hold to record voice question, release to get AI answer
   - **OCR**: Scan text on the detected product label
   - **Shelf**: Run shelf compliance analysis

## Module Structure (ProductARMobile)

```
com.ultronai.productarmobile/
├── ml/               RTMDetDetector (TFLite object detection)
├── network/          MjpegClient (HTTP MJPEG stream receiver)
├── ocr/              OcrProcessor (ML Kit text recognition)
├── stt/              WhisperStt + WhisperJni (speech-to-text)
├── llm/              LlmClient + LlamaJni (on-device LLM)
├── rag/              RagPipeline, KnowledgeBase, VectorStore, SimpleEmbedder
├── tts/              TtsManager (Android TextToSpeech)
├── shopping/         ShoppingAssistAgent (voice Q&A flow)
├── shelf/            ShelfAnalysisAgent (planogram compliance)
└── MainActivity.kt   UI and pipeline orchestration
```

## Models

| Component | Model | Size | Location |
|-----------|-------|------|----------|
| Detection | RTMDet-pico fp16 | ~5MB | `assets/` |
| STT | Whisper tiny (ggml) | 75MB | `assets/` |
| LLM | Phi-3-mini-4k Q4_K_M | 2.2GB | Phone storage (adb push) |
| OCR | ML Kit v2 | built-in | Google Play Services |
| TTS | Android TTS | built-in | System |

## Demo Flows

### Shopping Assist
1. Point glasses at product → phone detects it
2. Tap "Ask" → speak question ("Does this contain nuts?")
3. STT transcribes → OCR reads label → RAG retrieves product info → LLM answers → TTS speaks

### Shelf Analysis
1. Point glasses at shelf section → phone detects all products
2. Tap "Shelf" → system compares detected items vs planogram
3. Reports misplaced/missing items with voice guidance

## Docs

- `docs/Setup1-Design-Doc.tex` — Full design document
- `docs/code_problems.md` — Bug tracker (all 16 fixed)
- `docs/Retail-AR-Demos-Roadmap.md` — Project roadmap
