# Locune

## About

A local AI assistant for Android and iOS, built with Kotlin and Compose
Multiplatform. Import PDFs, text, Markdown, images or notes, then ask questions
about your knowledge base. Search, answers, dictation and read-aloud run on
device after downloading the required models.

The repo also includes Python tools for generating EmbeddingGemma 2 embeddings
and searching PDFs with a local Chroma index.

## Development

### Mobile app

**Android:** Open `mobile/` in Android Studio with JDK 17 and Android SDK 37.
Run `androidApp` on an arm64 device with Android 12 or newer, or build a debug APK:

```sh
cd mobile
./gradlew :androidApp:assembleDebug
```

**iOS:** On macOS, open `mobile/iosApp/PocketAsk.xcodeproj` in Xcode. Select the
`PocketAsk` scheme, set your signing team, choose an iPhone running iOS 17 or
newer, and Run. Xcode builds the shared Kotlin framework automatically.

Download the search and answer models during onboarding. See the
[mobile README](mobile/README.md) for architecture, model details and checks.

### Python tools

From the repo root, create a virtual environment and install dependencies:

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt

python embed.py "Hello world" "Hallo Welt"
python pdf_search.py ingest manual.pdf
python pdf_search.py search "How do I reset the device?" --limit 3
python -m unittest discover -s tests -v
```

The first run downloads the model; later runs use the local cache. If access is
required, accept the terms on the
[model page](https://huggingface.co/google/embeddinggemma-2) and run `hf auth login`.
PDF search stores its index in `.chroma/`; image-only PDFs need OCR first.
