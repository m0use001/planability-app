# ADR 0005 — On-device models via an OpenAI-compatible local endpoint

Status: accepted

## Context

The assistant should work without any cloud provider: fully offline, private,
and fast. Mobile NPU runtimes (MediaPipe LLM Inference, ExecuTorch, vendor
SDKs) each require deep integration into the model client, and most are
CPU/GPU-first with immature NPU paths.

## Options

1. **Integrate an NPU SDK directly into the model client** — rejected: locks
   the client to one vendor's API surface and duplicates the chat pipeline.
2. **Run a local OpenAI-compatible server backed by a Qualcomm NPU runtime
   (GenieX) and talk to it with the existing `AiClient`** — chosen: zero
   changes to the chat pipeline, model choice stays data.
3. **CPU-only on-device inference** — rejected: too slow for a daily driver.

## Decision

Option 2:

- The app ships a local inference entry point that exposes an
  OpenAI-compatible endpoint on-device, backed by Qualcomm GenieX
  (llama.cpp + QAIRT backends, Snapdragon Hexagon NPU). The regular
  `AiClient` treats it as just another OpenAI-compatible provider.
- Retrieval embeddings run on ONNX Runtime with two model tiers (a small
  ~24 MB model and a heavyweight ~600 MB int8 model) downloaded on demand;
  BPE tokenization operates on UTF-8 bytes (character-level tokenization
  silently produced all-UNK vectors for CJK — a real bug caught by tests).
- The NPU path is optional and device-dependent (Snapdragon 8 Elite class);
  everything degrades gracefully to cloud providers.

## Evidence

- `app/src/main/java/io/github/m0use001/planability/ai/` — model client and
  provider configuration (OpenAI-compatible endpoints)
- ONNX embedding/retrieval code and its tests under `app/src/test/.../ai/`
- 16 KB page alignment: `app/build.gradle.kts` + onnxruntime 1.22 dependency
