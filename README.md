# EmbeddingGemma 2

A small Python CLI that loads `google/embeddinggemma-2` locally and prints a JSON
array containing one normalized embedding per input text, in input order.
It loads only the text encoder and uses float32 for hardware compatibility.

## Setup

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -U -r requirements.txt
```

The dependency minimums match the versions checked for EmbeddingGemma 2 support.

If Hugging Face asks for model access, accept the terms on the
[model page](https://huggingface.co/google/embeddinggemma-2) and authenticate
with `hf auth login` or set the `HF_TOKEN` environment variable.
The first run downloads the weights; subsequent runs use the local cache.

## Use

```sh
python embed.py "Hello world" "Hallo Welt"
python embed.py --prompt document "A passage to index" > embeddings.json
python embed.py --prompt query "What is an embedding?"
```

The default `STS` prompt is for semantic similarity. For search, embed corpus
texts with `document` and search texts with `query`.

## PDF search

```sh
python pdf_search.py ingest manual.pdf report.pdf
python pdf_search.py search "How do I reset the device?"
python pdf_search.py search "What were last year's sales?" --limit 3
```

Text is extracted page by page with pypdf and split into 400-token passages with
50 tokens of overlap. Passages use the model's `document` prompt; search queries
use its `query` prompt. Chroma stores the normalized vectors, passage text, and
source metadata in `.chroma/`, with no database server required.

Search prints JSON with the text, filename, absolute source path, page number
(starting at 1), and cosine similarity score for each match. Higher scores mean
closer matches; scores are not confidence probabilities. Search always returns
the closest available passages, even if none answer the query.

Re-ingesting a PDF at the same absolute path replaces its previous entries,
including passages removed from the updated file. PDFs with no extractable text
leave existing entries intact. Image-only pages need OCR first; tables and
complex layouts depend on the quality of PDF text extraction.

Use the same database directory for both commands. To choose another location:

```sh
python pdf_search.py --db ./my-index ingest manual.pdf
python pdf_search.py --db ./my-index search "How do I reset the device?"
```

Based on Google's [official usage guide](https://ai.google.dev/gemma/docs/embeddinggemma/inference-embeddinggemma-with-sentence-transformers).

## Verification

```sh
python -m unittest discover -s tests -v
```

Tests exercise PDF extraction, persistence across processes, search metadata,
and re-ingestion with deterministic vectors. They do not measure EmbeddingGemma's
retrieval quality or require downloading its weights.
# embeddinggemma-2-vector
