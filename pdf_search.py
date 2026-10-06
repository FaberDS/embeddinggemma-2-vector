#!/usr/bin/env python3
"""Ingest PDFs and search their text with EmbeddingGemma 2 and local Chroma."""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from embed import load_model


def chunks(text, tokenizer, size=400, overlap=50):
    """Split a page into overlapping token windows."""
    tokens = tokenizer.encode(text, add_special_tokens=False)
    for start in range(0, len(tokens), size - overlap):
        passage = tokenizer.decode(tokens[start:start + size]).strip()
        if passage:
            yield passage
        if start + size >= len(tokens):
            break


def ingest(paths, model, collection, batch_size):
    from pypdf import PdfReader

    for path in paths:
        path = path.resolve(strict=True)
        source = str(path)
        file_id = hashlib.sha256(source.encode()).hexdigest()
        texts, ids, metadata = [], [], []
        for page_number, page in enumerate(PdfReader(path).pages, start=1):
            text = page.extract_text() or ""
            if not text.strip():
                print(f"{path.name}, page {page_number}: no text; may need OCR", file=sys.stderr)
                continue
            for index, passage in enumerate(chunks(text, model.tokenizer)):
                texts.append(passage)
                ids.append(f"{file_id}:{page_number}:{index}")
                metadata.append({"source": source, "file": path.name, "page": page_number})

        if not texts:
            raise ValueError(f"{path.name}: no extractable text; existing entries kept")
        # Finish extraction and embedding before replacing this PDF's entries.
        vectors = model.encode(
            texts, prompt_name="document", normalize_embeddings=True,
            batch_size=16, show_progress_bar=False,
        ).tolist()
        collection.delete(where={"source": source})
        for start in range(0, len(texts), batch_size):
            end = start + batch_size
            collection.add(
                ids=ids[start:end], documents=texts[start:end],
                embeddings=vectors[start:end], metadatas=metadata[start:end],
            )
        print(f"Indexed {path.name}: {len(texts)} passages", file=sys.stderr)


def search(query, model, collection, limit):
    count = collection.count()
    if not count:
        raise ValueError("Database is empty. Ingest PDFs first.")
    vector = model.encode(
        [query], prompt_name="query", normalize_embeddings=True,
        show_progress_bar=False,
    ).tolist()
    results = collection.query(
        query_embeddings=vector, n_results=min(limit, count),
        include=["documents", "metadatas", "distances"],
    )
    return [
        {**meta, "text": text, "score": round(1 - distance, 4)}
        for text, meta, distance in zip(
            results["documents"][0], results["metadatas"][0], results["distances"][0]
        )
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default=".chroma", help="Database directory (default: .chroma)")
    commands = parser.add_subparsers(dest="command", required=True)
    ingest_parser = commands.add_parser("ingest", help="Index or replace PDF files")
    ingest_parser.add_argument("pdfs", type=Path, nargs="+")
    search_parser = commands.add_parser("search", help="Find matching passages")
    search_parser.add_argument("query")
    search_parser.add_argument("--limit", type=int, default=5)
    args = parser.parse_args()
    if args.command == "ingest":
        for path in args.pdfs:
            if not path.is_file() or path.suffix.lower() != ".pdf":
                parser.error(f"Expected an existing PDF file: {path}")
    elif args.limit < 1 or not args.query.strip():
        parser.error("Provide a non-empty query and a positive --limit")

    import chromadb

    client = chromadb.PersistentClient(path=args.db)
    collection = client.get_or_create_collection(
        "embeddinggemma-2-pdfs", embedding_function=None,
        configuration={"hnsw": {"space": "cosine"}},
    )
    if args.command == "search" and not collection.count():
        parser.error("Database is empty. Ingest PDFs first.")
    model = load_model()
    if args.command == "ingest":
        ingest(args.pdfs, model, collection, min(256, client.get_max_batch_size()))
    else:
        print(json.dumps(search(args.query, model, collection, args.limit), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        sys.exit(str(error))
