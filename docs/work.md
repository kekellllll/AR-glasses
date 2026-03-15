# Work Log — Retail AI AR Demos

> Author: Zifeng Wang  
> Date: February–March 2026

---

## Project Overview

Built an end-to-end AR retail assistant system using RayNeo X3 Pro AR glasses and an Android phone. The glasses stream live camera feed to the phone, which runs all AI models on-device (zero cloud dependency): object detection, OCR, speech-to-text, LLM, RAG retrieval, and text-to-speech.

---

## Phase 0 — Codebase Review & Bug Fixes

### Code Review

Performed a thorough code review of both `ProductARGlasses` and `ProductARMobile` codebases, identifying **16 bugs** across 8 source files (8 more found in Phase 2, total: **24 bugs fixed**).

### Bugs Fixed (16 total)

#### High Severity (7 bugs — crash, wrong output, data corruption)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 2 | `Glasses/MainActivity.kt` | `onError` callback called from Camera2 background thread → `CalledFromWrongThreadException` crash | Wrapped with `runOnUiThread { }` |
| 4 | `Glasses/CameraController.kt` | `TEMPLATE_STILL_CAPTURE` used for repeating MJPEG stream → low FPS, pipeline stalls | Always use `TEMPLATE_PREVIEW` |
| 5 | `Glasses/CameraController.kt` | `stop()` race condition: `onOpened` fires after `imageReader = null` → NPE + camera device leak | Null-then-close pattern: save to local var, set field null, then close |
| 6 | `Glasses/FrameEncoder.kt` | YUV→NV21 ignores `rowStride` padding → garbled/green-tinted video | Row-by-row copy respecting `yRowStride` and `vRowStride` for both Y and VU planes |
| 9 | `Mobile/MjpegClient.kt` | Double-tap "Connect" races two threads (both pass `isRunning` check) → duplicate streams | Track `connectThread` reference, check `isAlive` before allowing new connection |
| 13 | `Mobile/RTMDetDetector.kt` | `ByteBuffer.get().toInt()` sign-extends UINT8 (0xFF → -1) → all dequantized detection values wrong | Added `and 0xFF` mask for unsigned interpretation |
| 15 | `Mobile/MainActivity.kt` | `lastBitmap?.recycle()` called before `ImageView` replaces bitmap → `RuntimeException: recycled bitmap` crash | Restructured: set new bitmap first, then recycle old on UI thread with `isRecycled` guard |

#### Medium Severity (7 bugs — resource leak, CPU waste, intermittent)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 1 | `Glasses/MainActivity.kt` + `CameraController.kt` | `EXPOSURE_COMPENSATION = 8` constant defined but never used; hardcoded `4` in capture request | Moved constant to `CameraController`, replaced hardcoded value |
| 3 | `Glasses/MainActivity.kt` | `frameCount`, `lastFpsTime`, `lastFrameTime` non-volatile, read/written across threads | Added `@Volatile` to all three fields |
| 7 | `Glasses/MjpegServer.kt` | `socket.isConnected` always true after connect → dead client threads loop forever on WiFi drop | Replaced with `socket.getInputStream().read()` + `soTimeout` for real disconnect detection |
| 8 | `Glasses/StreamServer.kt` | `isRunning` non-volatile; `synchronized {}` inside coroutine blocks thread | Added `@Volatile`; replaced `synchronized` + `Object()` with `Mutex.withLock` |
| 10 | `Mobile/MjpegClient.kt` | `readLine()` returns null on EOF → `Thread.sleep(10); continue` burns CPU forever | Changed to `break` on null (EOF) |
| 11 | `Mobile/RTMDetDetector.kt` | `FileInputStream` + `FileChannel` opened but never closed → FD leak | Wrapped with `.use { }` |
| 14 | `Mobile/RTMDetDetector.kt` | `ByteBuffer.allocateDirect()` called per inference frame (~12 FPS) → GC pressure | Pre-allocated `outputBuffersCache` map, reused with `clear()` each frame |

#### Low Severity (2 bugs — dead code, maintenance)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 12 | `Mobile/RTMDetDetector.kt` | Cached model not invalidated after app update → silently uses stale model | Added `$assetName.version` file with `longVersionCode`, re-copy on mismatch |
| 16 | `Mobile/MainActivity.kt` | `runDetection` always `true`, never changed → dead code + hidden bitmap bug | Removed field and dead branch; added `@Volatile` to `isDetectorReady` |

### Glasses IP Display Enhancement

- Added periodic IP refresh (every 3s until found, then every 10s) instead of one-shot `onCreate` fetch
- Enlarged IP text to 24sp bold cyan for visibility on AR glasses
- Proper cleanup in `onDestroy`

---

## Phase 1 — Full Pipeline Implementation

### Architecture Implemented

```
AR Glasses (RayNeo X3 Pro)          Android Phone
┌─────────────────────┐             ┌────────────────────────────────┐
│ Camera2 → MJPEG     │──WiFi──────→│ MjpegClient                    │
│ Server :8080        │             │   ↓                            │
│                     │             │ RTMDet Detector (TFLite)       │
│ IP display (auto-   │             │   ↓              ↓             │
│   refresh)          │             │ [OCR]         [Shelf Zone Map] │
│ Temple actions      │             │   ↓              ↓             │
└─────────────────────┘             │ RAG Pipeline   Compliance Eng  │
                                    │   ↓              ↓             │
                       Mic --------→│ STT (Whisper) → LLM (Phi-3)   │
                                    │                  ↓             │
                                    │                TTS → Speaker   │
                                    └────────────────────────────────┘
```

### New Modules Created (17 Kotlin files)

#### Speech-to-Text (`stt/`)

| File | Description |
|------|-------------|
| `WhisperJni.kt` | JNI interface to whisper.cpp native library |
| `WhisperStt.kt` | Full STT manager: model loading, AudioRecord capture (16kHz mono PCM), buffer management, transcription via JNI, 15s max recording |

#### Text-to-Speech (`tts/`)

| File | Description |
|------|-------------|
| `TtsManager.kt` | Android `TextToSpeech` API wrapper: init with US English locale, speak with completion callback, queue management |

#### OCR (`ocr/`)

| File | Description |
|------|-------------|
| `OcrProcessor.kt` | ML Kit Text Recognition v2 wrapper: full-image and cropped-region OCR, allergen keyword extraction (18 keywords), nutrition facts pattern matching (`\d+\s*(g|mg|kcal|%)`) |

#### On-Device LLM (`llm/`)

| File | Description |
|------|-------------|
| `LlamaJni.kt` | JNI interface to llama.cpp native library |
| `LlmClient.kt` | LLM manager: model loading from external storage, prompt building with standard Phi-3 chat template (`<|system|>...<|end|><|user|>...<|end|><|assistant|>`), generation with 256 max tokens, Phi-3-mini-4k-instruct system prompt for retail assistant |

#### RAG Pipeline (`rag/`)

| File | Description |
|------|-------------|
| `KnowledgeBase.kt` | Loads product and planogram JSON from assets, generates `DocumentChunk` objects with structured text. Data classes: `Product`, `PlanogramZone`, `DocumentChunk`, `ChunkType` |
| `SimpleEmbedder.kt` | Lightweight TF-IDF embedder: vocabulary building (max 2048 terms), IDF weighting, L2-normalized vectors. Upgrade path: swap to TFLite all-MiniLM-L6-v2 |
| `VectorStore.kt` | In-memory vector store with cosine similarity search, top-K retrieval |
| `RagPipeline.kt` | Orchestrator: loads KB → builds vocab → embeds all chunks → indexes in VectorStore → retrieval at query time |

#### Shopping Assist Agent (`shopping/`)

| File | Description |
|------|-------------|
| `ShoppingAssistAgent.kt` | End-to-end flow: spoken query → OCR on detected product crop → RAG retrieval → LLM generation → TTS playback. Includes fallback answer generation (allergen/nutrition/price pattern matching) when LLM is not loaded |

#### Shelf Analysis Agent (`shelf/`)

| File | Description |
|------|-------------|
| `ShelfAnalysisAgent.kt` | End-to-end flow: detect all products → Y-coordinate heuristic zone mapping (top/middle/bottom thirds) → compare detected SKUs vs planogram expected SKUs → identify MISPLACED/MISSING/EXTRA items → LLM guidance or fallback summary → TTS |

### Native C++/JNI (3 files)

| File | Description |
|------|-------------|
| `cpp/CMakeLists.txt` | CMake build for llama.cpp and whisper.cpp as Android native libraries. Disables GPU/CUDA/Metal, builds static libs, links JNI wrappers |
| `cpp/llama_jni.cpp` | JNI bridge: `loadModel` (4096 ctx, 4 threads, temp=0.7, top_p=0.9), `generate` (tokenize→decode→sample loop with EOG check), `unload` |
| `cpp/whisper_jni.cpp` | JNI bridge: `initContext` (CPU mode), `transcribe` (greedy sampling, English, single segment), `freeContext` |

### Sample Data (2 JSON files)

| File | Content |
|------|---------|
| `assets/products.json` | 10 retail products: Horizon Milk, Jif Peanut Butter, Canyon Bakehouse Bread, Silk Almond Milk, Sabra Hummus, Tropicana OJ, Chobani Yogurt, Triscuit Crackers, Chips Ahoy Cookies, Planters Mixed Nuts. Each with: name, brand, SKU, ingredients, allergens, nutrition facts, price, category |
| `assets/planogram.json` | 6 shelf zones: Dairy top/bottom, Spreads, Bakery, Snacks upper/lower. Each with: zoneId, description, shelfLevel, expectedSkus |

### Infrastructure Changes

#### `build.gradle.kts` — New dependencies and build config

```
+ androidx.lifecycle:lifecycle-runtime-ktx:2.7.0
+ androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0
+ org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
+ com.google.mlkit:text-recognition:16.0.0
+ com.google.code.gson:gson:2.10.1
+ NDK abiFilters: arm64-v8a
+ CMake: src/main/cpp/CMakeLists.txt
+ noCompress: tflite, bin, gguf
```

#### `AndroidManifest.xml` — New permissions

```
+ android.permission.RECORD_AUDIO
+ android.permission.READ_EXTERNAL_STORAGE
+ android.permission.WRITE_EXTERNAL_STORAGE
```

#### `activity_main.xml` — New UI elements

- Model status bar (Det/STT/LLM/TTS indicators with ✓/✗/↓)
- Action buttons row: Ask (voice), OCR (label scan), Shelf (compliance)
- AI response text area (green text, 3 lines max)

#### `MainActivity.kt` — Complete rewrite

- Initializes all AI components on startup (parallel coroutines for model loading)
- Frame pipeline: stream → throttle → detect → draw → display
- Ask flow: record → STT → OCR → RAG → LLM → TTS
- OCR flow: crop detected product → extract text → show allergens
- Shelf flow: map detections to zones → compliance check → voice guidance
- Proper lifecycle management for all components

### Native Library Sources

| Library | Version | Purpose |
|---------|---------|---------|
| llama.cpp | latest (git clone --depth 1) | On-device LLM inference engine |
| whisper.cpp | latest (git clone --depth 1) | On-device speech recognition engine |

### Model Files

| Model | Size | Location | Source |
|-------|------|----------|--------|
| Whisper tiny (ggml-tiny.bin) | 75MB | `assets/` (bundled in APK) | HuggingFace ggerganov/whisper.cpp |
| Phi-3-mini-4k Q4_K_M (.gguf) | 2.2GB | Phone external storage (adb push) | HuggingFace bartowski/Phi-3-mini-4k-instruct-GGUF |
| RTMDet-pico fp16 (.tflite) | ~5MB | `assets/` | OpenMMLab (user-provided) |

### Scripts and Documentation

| File | Description |
|------|-------------|
| `scripts/download_models.sh` | Automated model downloader: Whisper tiny + Phi-3-mini GGUF, with adb push instructions |
| `README.md` | Complete project documentation: architecture diagram, quick start guide, module structure, model inventory, demo flows |
| `docs/code_problems.md` | Bug tracker with all 16 issues documented and marked as fixed |
| `.gitignore` | Updated to exclude model files (*.tflite, *.gguf, *.bin), native lib sources (llama.cpp/, whisper.cpp/) |

---

## Phase 2 — Second Code Review & Bug Fixes

Performed a second comprehensive code review of all new and modified files across both apps, focusing on concurrency safety, resource lifecycle, and API correctness. Found and fixed **8 additional bugs**.

### Bugs Fixed (8 total)

#### High Severity (3 bugs — crash, memory leak, data corruption)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 17 | `Mobile/MainActivity.kt` | `currentFrame` obtained by reference via `synchronized` block, but `processFrame` recycles it on next frame → OCR/Agent uses recycled bitmap → crash | Copy `currentFrame` via `bitmap.copy()` when accessing it in `onOcrClicked` and `stopAskAndProcess`; recycle copy after use |
| 18 | `cpp/llama_jni.cpp` | Calling `loadModel` twice without `unload` leaks previous `model`, `ctx`, and `smpl` native objects → unbounded memory growth | Added cleanup of existing resources at top of `loadModel` before allocating new ones |
| 19 | `cpp/whisper_jni.cpp` | Same leak: calling `initContext` twice leaks previous `whisper_context` | Added `whisper_free(w_ctx)` at top of `initContext` if context already exists |

#### Medium Severity (4 bugs — incorrect behavior, thread safety, silent failure)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 20 | `Mobile/TtsManager.kt` | `setOnUtteranceProgressListener` called in `init` block after constructor returns, but TTS engine initializes asynchronously → listener registration silently ignored | Moved `setOnUtteranceProgressListener` inside the `onInit` callback's `SUCCESS` branch |
| 21 | `Mobile/WhisperStt.kt` | Recording thread has no try-catch; if `release()` is called while `audioRecord.read()` is blocking, the read throws → uncaught exception crashes thread | Added try-catch around entire recording loop; added `recordThread?.join(1000)` in `release()` to wait for thread exit |
| 22 | `Mobile/WhisperStt.kt` | `stopAndTranscribe` calls `audioRecord.stop()` immediately while recording thread may still be inside `audioRecord.read()` → race condition | Added `recordThread?.join(2000)` before `audioRecord.stop()` to ensure recording thread exits first |
| 23 | `Mobile/LlmClient.kt` | Prompt uses non-standard `<|context|>` and `<|ocr|>` role tags without `<|end|>` delimiters → doesn't match Phi-3 chat template → degraded LLM output quality | Restructured to standard Phi-3 format: context/OCR embedded in `<|system|>...<|end|>`, user query in `<|user|>...<|end|>`, generation starts at `<|assistant|>` |

#### Low Severity (1 bug — defensive programming)

| # | File | Problem | Fix |
|---|------|---------|-----|
| 24 | `Mobile/RagPipeline.kt` | If `SimpleEmbedder.buildVocab` hasn't completed, `embed()` returns `FloatArray(0)` → dimension mismatch with stored vectors → `cosineSimilarity` always returns 0 (silent failure) | Added early return `if (queryVec.isEmpty()) return ""` in `retrieve()` |

### Known Limitations Documented

- **`ShelfAnalysisAgent.classIdToSku` defaults to empty**: RTMDet detects generic "products" but doesn't classify into specific SKUs. Until a product classification model is integrated, shelf analysis will report all expected products as MISSING. The code structure supports plugging in a SKU mapping when ready.

---

## File Change Summary

### New Files Created (23)

```
ProductARMobile/app/src/main/java/com/ultronai/productarmobile/
├── tts/TtsManager.kt                    (NEW)
├── ocr/OcrProcessor.kt                  (NEW)
├── stt/WhisperJni.kt                    (NEW)
├── stt/WhisperStt.kt                    (NEW)
├── llm/LlamaJni.kt                      (NEW)
├── llm/LlmClient.kt                     (NEW)
├── rag/KnowledgeBase.kt                  (NEW)
├── rag/SimpleEmbedder.kt                 (NEW)
├── rag/VectorStore.kt                    (NEW)
├── rag/RagPipeline.kt                    (NEW)
├── shopping/ShoppingAssistAgent.kt       (NEW)
└── shelf/ShelfAnalysisAgent.kt           (NEW)

ProductARMobile/app/src/main/cpp/
├── CMakeLists.txt                        (NEW)
├── llama_jni.cpp                         (NEW → MODIFIED — leak prevention on re-init)
├── whisper_jni.cpp                       (NEW → MODIFIED — leak prevention on re-init)
├── llama.cpp/                            (CLONED — git clone --depth 1)
└── whisper.cpp/                          (CLONED — git clone --depth 1)

ProductARMobile/app/src/main/assets/
├── ggml-tiny.bin                         (DOWNLOADED — 75MB)
├── products.json                         (NEW — 10 sample products)
└── planogram.json                        (NEW — 6 shelf zones)

scripts/download_models.sh                (NEW)
docs/work.md                              (NEW — this file)
```

### Modified Files (14)

```
ProductARMobile/
├── app/build.gradle.kts                  (MODIFIED — +dependencies, +CMake, +NDK)
├── app/src/main/AndroidManifest.xml      (MODIFIED — +RECORD_AUDIO, +STORAGE permissions)
├── app/src/main/res/layout/activity_main.xml (MODIFIED — +buttons, +AI response, +model status)
├── app/src/main/java/.../MainActivity.kt (REWRITTEN — full AI pipeline integration + bitmap safety fix)
├── app/src/main/java/.../ml/RTMDetDetector.kt (MODIFIED — 4 bug fixes)
├── app/src/main/java/.../network/MjpegClient.kt (MODIFIED — 2 bug fixes)
├── app/src/main/java/.../tts/TtsManager.kt (MODIFIED — listener registration fix)
├── app/src/main/java/.../stt/WhisperStt.kt (MODIFIED — thread safety + lifecycle fixes)
├── app/src/main/java/.../llm/LlmClient.kt (MODIFIED — Phi-3 prompt template fix)
├── app/src/main/java/.../rag/RagPipeline.kt (MODIFIED — empty vector guard)

ProductARGlasses/
├── app/src/main/java/.../MainActivity.kt (MODIFIED — 3 bug fixes + IP refresh)
├── app/src/main/java/.../camera/CameraController.kt (MODIFIED — 3 bug fixes)
├── app/src/main/java/.../camera/FrameEncoder.kt (MODIFIED — 1 bug fix)
├── app/src/main/java/.../network/MjpegServer.kt (MODIFIED — 1 bug fix)
├── app/src/main/java/.../network/StreamServer.kt (MODIFIED — 1 bug fix)
├── app/src/main/res/layout/activity_main.xml (MODIFIED — IP display styling)

Root/
├── .gitignore                            (MODIFIED — +model exclusions)
├── README.md                             (REWRITTEN — full documentation)
└── docs/code_problems.md                 (MODIFIED — all 16 bugs marked as fixed)
```

---

## Build & Deploy Steps

1. Install NDK 25+ and CMake 3.22.1+ in Android Studio
2. Clone native sources: `git clone --depth 1` llama.cpp and whisper.cpp into `app/src/main/cpp/`
3. Add `rtmdet_pico_fp16.tflite` to `app/src/main/assets/`
4. Run `./scripts/download_models.sh <HF_TOKEN>` to download Phi-3 GGUF
5. Build both apps in Android Studio
6. `adb push` the LLM GGUF to phone external storage
7. Connect glasses + phone to same WiFi, launch apps, enter glasses IP on phone

---

## Technology Stack

| Component | Technology | License |
|-----------|-----------|---------|
| Object Detection | RTMDet-pico (TFLite) | Apache 2.0 |
| OCR | Google ML Kit Text Recognition v2 | Free use |
| Speech-to-Text | Whisper.cpp (tiny model) | MIT |
| LLM | llama.cpp + Phi-3-mini-4k-instruct | MIT / MIT |
| RAG Retrieval | TF-IDF embedder + cosine similarity | — |
| Text-to-Speech | Android TextToSpeech API | System |
| Video Streaming | MJPEG over HTTP | — |
| AR Glasses SDK | RayNeo MercurySDK | Proprietary |
| Build System | Gradle + CMake/NDK | — |
