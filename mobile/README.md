# Pocket Ask

An Android/iOS app with a shared Compose Multiplatform UI. Import PDF, TXT and
Markdown files, add images, and ask a question. Retrieval, generation, drafts
and history run locally after the model downloads.

## Run

Open this directory in Android Studio, then run `androidApp` on an arm64 device
with Android 12 or newer. Or build an APK:

```sh
./gradlew :androidApp:assembleDebug
```

Open [iosApp/PocketAsk.xcodeproj](iosApp/PocketAsk.xcodeproj) in Xcode, select
the PocketAsk scheme, choose your signing team and an iPhone, and Run. The
build phase compiles and links the shared Kotlin framework automatically.
The current deployment target is iOS 17. The first Swift package resolution
downloads LiteRT-LM's native binaries.

Onboarding explains the local workflow and offers downloads for the search
and answer models: approximately 485 MB and 2.59 GB. Download on Wi-Fi by
default; cellular use requires the checkbox. Files are checked against pinned
SHA-256 hashes before installation. Cancelled downloads can resume. Keep the
app in the foreground during setup; history and drafts survive a restart.

Once installed, try a question with no attachments, then with a small PDF and
an image. Model initialization is shown separately from download progress.
Stopping preserves partial output. History can reopen answers, restore a
draft, or delete entries with Undo. Source links open local content.

## Design

- `shared`: UI, state, request coordination, SQLite persistence, model download
  verification, text chunking and selection-scoped vector search.
- `androidApp`: system pickers, PDFBox text extraction, PdfRenderer page images
  and the LiteRT-LM Kotlin runtime.
- `iosApp`: system pickers, PDFKit processing and the LiteRT-LM Swift runtime.

The model engines are serialized and released between search and generation.
Images are passed as pixels to both embedding and vision generation. PDF pages
are indexed as text and page images, including scanned PDFs.

Search initially uses exact cosine ranking over paginated SQLite records in
the selected attachments. This keeps the baseline small and prevents unrelated
history from entering results. Benchmark an ANN index such as USearch before
claiming support for a large device-wide library. Image comparisons currently
accept up to four images per request; more can be selected in a draft, with an
explicit explanation before inference. Text inputs are limited to 20 MB.

Models are pinned in `Models.kt`. Changes to a model, preprocessing or vector
dimensions require a new index profile. The Swift SDK is an early preview;
real-device retrieval quality, image grounding, latency and memory still need
evaluation before production release. Generated answers can be incorrect;
citations identify the evidence supplied to the model.

## Checks

```sh
./gradlew :shared:jvmTest
./gradlew :androidApp:assembleDebug
./gradlew :shared:linkDebugFrameworkIosArm64
```

The ten host tests cover selected-source filtering across database pages,
citations, image evidence, chunking, persisted history, shared-asset cleanup,
general questions, download resume and verification, and cancellation with late callbacks. They use a deterministic
test runtime; they do not establish real-model answer quality.

Implementation reference: [specification](https://chatgpt.com/space/page_35997e2e0b308191b1b48f1c8ae666a7).
SDK APIs: [LiteRT-LM 0.18](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.18.0),
[Compose platform integration](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html).
