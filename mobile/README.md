# Pocket Ask

An Android/iOS app with a shared Compose Multiplatform UI. Import PDF, TXT and
Markdown files, images and written notes into a persistent knowledge base. Every
chat searches its indexed sources. Retrieval, generation, drafts and history run
locally after the model downloads.

## Speech

Tap the microphone beside Message to dictate locally; tap again to finish.
The transcription remains an editable draft until Send is pressed. Microphone
permission is requested on first use; iOS also requests speech recognition
permission. Dictation uses the device language and requires an installed
on-device recognizer. If it is unavailable, the app explains how to enable it
and keeps typing available; speech is never silently sent to a cloud service.

Settings → Speech offers Supertonic 3 with five female and five male preset
voices, a preview and automatic read-aloud (enabled by default). Download its
401 MB of verified model/configuration/style assets once; downloads support
background transfer, progress and time estimates. Automatic playback begins
only for a new completed answer when voices are installed. Read/Stop controls
also work on older completed replies. Voice choice and automatic playback
preferences are saved. Model loading, audio preparation and playback have
separate visible states. Starting dictation, sending another request, changing
voice or backgrounding the app stops speech; cancelled callbacks cannot edit
a new conversation. Audio is synthesized off the UI thread, and temporary
WAV files on iOS are removed after playback. ONNX sessions are released after
synthesis to avoid retaining the speech model alongside the answer model.

The official Supertonic implementation and model assets are revision-pinned.
Its upstream repository was archived in September 2026; the vendored helper
adaptations and licenses are under `third-party/supertonic`. Code is MIT;
weights use BigScience Open RAIL-M. Android uses ONNX Runtime 1.24.3, iOS
uses the official Microsoft ONNX Runtime Swift package 1.24.2. iOS targets 17+
and Android targets devices with Android 12+ on-device speech support.

## Memories

Choose **+ → Add memory** to start on-device recording and live transcription.
Tap **Stop and save**; the local answer model generates a short title, and the
transcript is saved as a Markdown memory in the shared knowledge base. The
search model indexes it through the existing passage ingestion flow. It is
available to every future chat, survives restarts and remains when chat history
is cleared. Source management labels memories separately from imported files.
No raw recording is retained. The normal message draft is left intact.

A transcript-derived title is used if the answer model is missing or cannot
generate a title. If the search model is missing, the saved memory waits for
its installation before indexing. Recording, title generation, saving and
indexing show their state. Backgrounding or pausing keeps an unfinished
transcript for resume/edit/save; Cancel discards it. Interrupted title generation
can be retried, and late callbacks cannot create duplicate memories.

## iOS Live Activities

On iOS 17+, a small native WidgetKit extension presents importing, microphone,
transcription finishing, voice preparation and read-aloud status. Supported phones
use Dynamic Island; the same extension supplies the Lock Screen presentation.
The system chooses when and where to show these presentations. Live Activities
can be disabled in iOS Settings; the existing Compose controls remain available.

Recording appears only after the native audio engine starts. Recording and
playback show elapsed time using system-rendered timers; no per-second app updates
are needed. The expanded/Lock Screen Finish action completes dictation into the
editable message or memory, and Stop cancels voice preparation/playback. Tapping
an activity returns to the app. Controls carry a unique session ID, so a stale
button cannot stop a later session. Import status yields to actual audio activity.

These activities contain only phase, timer and session metadata, with no
transcripts, answer text or filenames. Updates are local, without ActivityKit
push notifications or an App Group. Stop, interruption and the existing
background speech cleanup end voice activities. Recording and read-aloud still
stop when the app goes into the background; this feature does not enable
background audio. Relaunch clears abandoned voice/import activities, and expired
status displays a neutral prompt to open the app instead of claiming an active mic.

On iOS 26+, indexing reuses the system Live Activity from
`BGContinuedProcessingTask`, including progress, page counts, a phase estimate
when available, and system cancellation. It does not create a duplicate custom
indexing activity. The OS may deny continued processing; the app's normal progress
and durable checkpoints remain available.

## Import progress

A compact header below the app title shows the current import/indexing step and
its estimated remaining time. Pull the header down or tap it to expand the import
panel; swipe up or tap again to collapse it. It stays available across all tabs.
The panel groups completed, active and upcoming stages under a single heading per
asset, with committed page counts, and opens at the current asset. Pause keeps completed
pages searchable; Resume continues from the checkpoints. Missing answer models
offer a Settings action instead of repeatedly retrying the visual pass.

The latest import's stage archive persists after completion and app relaunch.
An interrupted active stage restores as paused. The estimate applies only to the
current stage and appears after enough new pages finish; model loading and steps
without samples show an estimating state. It is not an estimate for the whole
multi-stage batch. Text/Markdown have only a text stage; PDF/images also show OCR,
image vectors, descriptions and description vectors in the actual text-first order.

## Settings and index details

Settings → Request timing enables a small local diagnostic timeline between
each new question and its reply. It is off by default and the preference survives
restarts. Turning it off hides timelines immediately and future questions do not
record them; the usual chat layout is restored. A request already being recorded
finishes its trace, which remains saved with its reply and can be viewed again by
enabling the toggle. Older replies have no invented timings.

Each timeline has a **Copy telemetry** button, available when expanded or
collapsed. It copies a plain-text snapshot with the request ID, status, model,
time zone, full dates, timestamps, durations and runtime details. For an active
request, the last step is labelled running. Question and answer text are omitted.

Each phase shows a local timestamp with milliseconds, elapsed time since the
request started, and its duration. The active phase updates once per second.
Elapsed measurements use a monotonic clock. The trace separates waiting for
indexing to pause, model loading, question embedding, passage retrieval, engine
release, waiting for first displayed text, answer generation and completion.
It reports the actual loaded CPU/GPU backend, model/context/output limits,
source counts and character counts for the prompt, instructions and history.
Character counts are not token counts. These are app-level timings: the wait for
first text includes native runtime queuing and prompt processing, not a separate
SDK prefill measurement. No extra model calls or per-token events are added.
Timings use the existing reply storage; no analytics service or network upload.

Settings is the fourth tab in the floating navigation bar. It contains the
model and speech controls, plus a Knowledge index overview:

- Actual stored vector count, text/image breakdown and currently searchable count.
- Saved JSON payload bytes and actual SQLite database files (including journal
  files when present), separately from original assets and model downloads.
- Stored embedding shape, the raw Float32 size equivalent and an optional
  16 × 16 view of one real saved 256-value vector.
- The current encoder, normalization, hybrid cosine/keyword search, chunk sizes and
  photo/PDF-page indexing behavior.

Counts come from SQLite metadata; the screen does not open original source
files or load a model. They refresh on entering Settings, after source/index
changes, and using Refresh. Database version 2 adds small metadata columns to
existing records. Legacy metadata is backfilled in cancellable 64-row batches
when the overview is first opened, preserving vectors, source IDs and history.
For an in-memory test database the UI labels its page allocation explicitly
instead of claiming an on-disk measurement. Payload bytes exclude SQLite row
and index overhead; Database on disk includes that overhead and chat/catalog
records.

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
and selected answer model: EmbeddingGemma 2 (740M, 485 MB), plus Gemma 4 E2B
IT (2.59 GB, default) or Gemma 4 E4B IT (3.66 GB). Both answer choices support
text and images on Android and iOS; E4B uses more memory and answers more slowly.
Galaxy S21/FE devices use CPU inference to avoid incompatible Vulkan shaders.
Android devices with at most 6 GB RAM, or flagged as low-RAM by Android, also use
CPU inference and a 4,096-token context instead of 8,192 tokens. Visual preparation
allocates capacity for one image at a time. CPU descriptions and answers are slower.
The selection is persisted and uses a curated, revision-pinned catalog verified
on 6 October 2026. Changing the answer model leaves the search index intact. Download on Wi-Fi by
default; cellular use requires the checkbox. Files are checked against pinned
SHA-256 hashes before installation. Download cards show transferred bytes and an estimate based on recent speed;
waiting for a network or OS scheduling has no reliable ETA. History and drafts
survive a restart.

Android uses the system DownloadManager (with notifications, network retries
and reboot recovery). iOS uses a background URLSession with task restoration
and app-delegate completion handling. Downloads can continue while the app is
inactive; the OS controls scheduling. On iOS, force-quitting cancels transfers;
retry after reopening. iOS cancellation saves resume data when available;
Android cancellation removes the task and restarts on retry. Completed files
are only installed after pinned-size and SHA-256 verification, which may wait
until the app is reopened. Interrupted verification is recovered on launch.
Existing verified downloads from the earlier app are retained; unfinished
foreground downloads are replaced by a new native transfer on retry.

Once installed, try a question with no attachments, then with a small PDF and
an image. Model initialization is shown separately from download progress.
Ask is a conversation with a bottom composer, user bubbles on the right and
model replies on the left. The dark + button opens Add files, Add images, Write text and Add memory; the supported formats remain PDF, TXT, Markdown and images. The
knowledge-base summary opens source management without filling the conversation
with every imported image. The Assets tab shows the entire knowledge base, with All, Images, Documents and Memories filters. Images use a thumbnail gallery; documents and memory transcripts open their original files. Indexed and pending assets remain visible, and removal requires confirmation. A floating glass pill replaces the
stock bottom bar, with line icons and safe-area spacing. Taps slide a shared
selector with a spring animation; horizontal drags follow the finger and snap
to the nearest tab on release. Screens switch once on release, and interrupted
gestures restore the active tab. The gesture respects right-to-left layouts.
It hides while the keyboard is open; lists can scroll behind the glass while the
composer stays clear of it. The shared renderer uses Haze Glass 2.0.1 with its
automatic simplified rendering when advanced effects are unavailable.
Compose 1.12.1, Kotlin 2.4.20 and Coil 3.6.3 keep its UI dependencies aligned.
Android compiles against SDK 37 with AGP 9.1.1; the runtime minimum remains
Android 12 and the target SDK remains 36.

Replies show their actual model name and completion time: relative times on the
same local date, or a date/time for older replies. Source previews appear only in completed replies and only for cited evidence.
The best retrieved image is embedded; older multi-image replies keep their
thumbnail Open links. Document
links show the original filename and page, and open that reply's original file.
History groups related turns into conversations with Continue and Delete/Undo.
New chat starts a separate conversation and keeps the entire knowledge base.
Deleting or clearing history also keeps imported sources and vectors. Removing a
source requires confirmation and excludes it from future searches; earlier answers
retain linked files until those answers are deleted. Earlier turns provide bounded textual
context for follow-ups; old citation IDs are excluded from this context. Legacy
history remains readable without inventing completion times or model identities.
The app migrates sources from legacy drafts and history once, and recovers orphan
imports from private storage, reusing their existing vectors. If an orphan was
never indexed, its original display name was not saved by the old version; it is
shown as a recovered source. Explicitly removed sources are never re-added by
opening an old chat or restarting. Input
paths are stored relative to app storage and resolved at launch, including
legacy absolute paths in drafts, history and cached page images. This keeps
attachments usable when iOS relocates the container during an app update.
Stopping preserves partial output. History can reopen conversations, restore a
draft, or delete a conversation with Undo. Source links open local content.

## Design

- `shared`: UI, state, request coordination, SQLite persistence, model download
  verification, persistent source catalog, text chunking and knowledge-base vector search.
- `androidApp`: system pickers, PDFBox text extraction, PdfRenderer page images
  and the LiteRT-LM Kotlin runtime.
- `iosApp`: system pickers, PDFKit processing and the LiteRT-LM Swift runtime.

The model engines are serialized and released between search and generation.
On opening or returning to the app, it preloads the first installed engine a
question needs: search when the knowledge base contains sources, otherwise the
selected answer model. Preloading runs without blocking the chat controls. A
question sent during preload waits for that load instead of initializing a second
engine; telemetry labels both the wait and reuse of a preloaded model. Pending
indexing has priority, and the first engine is warmed again when indexing ends.

General chat keeps its answer engine ready between successful requests. Document
chat releases the answer engine and warms search again for the next question.
Backgrounding or closing the app releases idle engines; background indexing keeps
only its own engine until it finishes. Changing or removing a model invalidates
the corresponding cached engine. A failed preload is retried on the next question.

After import, images, PDFs and text are indexed once. Text/Markdown needs the
search model; visual assets also need the selected answer model. PDF text is
split into overlapping passages, and rendered page images are cached and
embedded too, including scanned PDFs. During ingestion the answer model
creates a saved description of each photo or PDF page, then the search model
embeds those descriptions as text passages linked to the original image.
Only one engine is resident at a time. "Write text" uses the same text ingestion
flow. Questions embed only the query and search saved SQLite vectors; they
never extract, re-embed or send image pixels to the answer model. Answers use
saved passages, OCR text and descriptions, with the cited photo shown only in the completed
reply. Original image/PDF links remain available for viewing.

Descriptions add preparation time during import and can omit visual details.
Answers cannot inspect details absent from the saved descriptions. Existing
visual indexes are marked pending for this one-time preparation; text-only
sources and chat history are retained. Each completed description is cached
with its generating model ID, so retries and restarts reuse it. Changing the
answer model does not regenerate already indexed descriptions. Removing an
asset removes its description cache and vectors; clearing history does not.
Failed or cancelled descriptions keep the source pending, with Stop/retry and
visible loading, description and indexing stages. Pending visual sources begin
when the required models become available. Candidates with a non-positive combined
retrieval score are omitted. Higher-ranked readable PDF text can suppress a redundant
lower-ranked description of that page.

Search combines exact cosine similarity with keyword matching over paginated
SQLite evidence from indexed knowledge-base sources. Exact digit-bearing identifiers
and quoted phrases receive stronger boosts; ordinary words receive a small boost.
Identifier matching respects boundaries, so INV-42 does not match INV-420. This
reuses the existing local store without a second database or search service. Conversation context stays separate from this library.
A larger relevant library can supply more evidence; it does not train or modify
the models, and more files alone do not guarantee better answer quality. Benchmark an ANN index such as USearch before
claiming support for a large device-wide library. You can select any number of
images. Retrieval supplies up to six sources,
with at most one matched image preview per answer. All answer-model inputs are
text. Multi-image comparisons are limited by this one-image retrieval policy.
Text inputs are limited to 20 MB. Each answer saves only matched source metadata,
not a snapshot of the whole library. Completed text pages from an unfinished PDF
are searchable; unreadable or unfinished steps stay pending. Asking during visual
indexing pauses that work, answers from saved passages, then resumes enrichment.

PDF indexing extracts and embeds text first. Pages with fewer than 40 readable
letters/digits are rendered for OCR during this pass; pages with sufficient embedded
text avoid rendering until the later visual pass. Recognized text, page images,
descriptions and description vectors have separate resumable checkpoints. SQLite
commits each page's vectors and its checkpoint together. Cached extraction and
completed descriptions survive interruptions, including process restarts. Resume
retries only the unfinished page/step. Progress shows the current phase and page
count; remaining time estimates use pages completed during the current phase.


On-device OCR runs during ingestion using Apple Vision on iOS and bundled ML Kit
Latin text recognition on Android. Photos are recognized once; sparse/scanned PDF
pages use OCR alongside their embedded text. No OCR download or cloud request is
needed. This Android recognizer covers Latin-script text; additional script models
are not included. Apple Vision detects the recognition language automatically.
PDFs with substantial embedded text use that text rather than OCR; partially scanned
content on an otherwise text-rich page is not automatically recognized.

Recognized text is cached before embedding, split into separate OCR passages and
linked to the original photo or PDF page. Duplicate embedded lines are omitted.
OCR passages count as text vectors in Settings and are labelled as OCR in the
answer context, separately from fallible AI image descriptions. Queries reuse the
stored text and vectors without rerunning OCR or loading pixels. Existing completed
visual assets gain OCR once while keeping their prior vectors/descriptions
searchable. Cancellation/restart resumes unfinished OCR embedding from the cache;
removing an asset removes its OCR passages and cache. Apple Vision uses CPU compute
for compatibility with the existing background indexing flow.

On iOS 26+, an explicit import or **Index pending sources** action requests a
`BGContinuedProcessingTask`. Its system progress interface allows cancellation.
If the request is unavailable, foreground indexing still works and pauses safely
when backgrounded. A `BGProcessingTask` requests deferred local indexing while
charging; iOS decides when and how long to run it. Both expiration paths preserve
checkpoints. Force-quitting the app stops background work.

The default Personal Team build uses CPU inference for background-capable indexing;
normal foreground answers retain GPU inference. Background descriptions are slower.
Background GPU use requires an eligible Apple Developer team, the Background GPU
Access capability/entitlement, `PocketAskBackgroundGPU = YES` in Info.plist, and a
positive runtime `BGTaskScheduler.supportedResources` GPU check. Without all of
these, CPU background processing is used. Background completion is not guaranteed:
iOS may suspend or expire work due to resource and power conditions.

Models are pinned in `Models.kt`. Changes to a model, preprocessing or vector
dimensions require a new index profile. The Swift SDK is an early preview;
real-device retrieval quality, image grounding, latency and memory still need
evaluation before production release. Generated answers can be incorrect;
citations identify the evidence supplied to the model.

## Checks

```sh
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
# macOS + Xcode: no phone or downloaded models required
./gradlew :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosArm64
# Real Apple Vision OCR on generated images, without a phone or app data
sh ./scripts/test-ocr.sh
# Optional: choose an installed simulator explicitly
./gradlew :shared:iosSimulatorArm64Test --device '<simulator UUID>'
```

The host tests cover ingestion, repeated questions without attachment reads,
PDF passages and cached page images, import-time image descriptions and their
text vectors, caption cache/retry/migration, OCR cache/retry/upgrade, identifier and
quoted-phrase ranking, native SQLite OCR persistence, text-only answer inputs, image batches, Markdown injection,
Stop/retry, cancellation with late callbacks, indexed-source filtering,
model transfers, conversation persistence/context, legacy history, timestamps
across local midnight, bubble alignment, pinned composer behavior, plus-menu
injection, opening per-reply image/document links, tab accessibility/selection in
both themes, animated selector movement, drag snapping and interruption,
right-to-left navigation, preserving drafts when switching tabs, searching across new chats,
knowledge-base persistence after deleting history, explicit source removal,
legacy/orphan migration, and independent ready/unfinished imports. The full Compose flow test
also captures a synthetic chat preview in `/private/tmp/pocketask-chat-preview.png`.
Common logic/UI tests also run on an iOS simulator, without a phone or
model downloads. Add portable UI/algorithm tests in `commonTest`, and
controller/database/whole-app scenarios in `jvmTest`.

The Xcode scheme also includes `PocketAskNativeTests`, which tests actual
ActivityKit requests, content-update streams, Stop intents, disabled activities,
import/audio priority, relaunch cleanup and status-only payloads on a simulator:

```sh
xcodebuild -project iosApp/PocketAsk.xcodeproj -scheme PocketAsk \
  -destination 'platform=iOS Simulator,id=<simulator UUID>' test
```

These tests use a deterministic runtime and do not establish real-model answer
quality. Native picker behavior, OS suspension, model latency and visual grounding
still require app/device tests. The iOS app can be compiled with Xcode's generic
iOS destination without a connected phone.

Implementation reference: [specification](https://chatgpt.com/space/page_35997e2e0b308191b1b48f1c8ae666a7).
SDK APIs: [LiteRT-LM 0.18](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.18.0),
[Compose platform integration](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html).

Model compatibility: [Google's current LiteRT-LM catalog](https://developers.google.com/edge/litert-lm/overview).
Background transfers: [Apple URLSession](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background),
[Android DownloadManager](https://developer.android.com/reference/android/app/DownloadManager).

Glass rendering: [Haze Glass](https://chrisbanes.github.io/haze/latest/effects/glass/).

For a real native speech smoke test on macOS, pass a directory containing the
verified Supertonic `onnx` and `voice_styles` folders:

```sh
./scripts/test-supertonic.sh /path/to/supertonic
```

This compiles the same Swift helper used by iOS, synthesizes a short synthetic
sentence in all ten voices, checks finite non-silent audio, and cancels a
running denoising pass. It does not read the app database or request microphone
access. Microphone permissions and recognition with a real voice still need
a device check. The regular shared suite covers partial and final dictation,
draft preservation, stale callbacks, voice settings, autoplay, read/stop UI,
background cancellation and nested voice-asset download verification.

OCR APIs: [Apple Vision](https://developer.apple.com/documentation/vision/recognizing-text-in-images),
[ML Kit bundled text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

Live Activities: [Apple ActivityKit](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities),
[interactive controls](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities).
