#!/usr/bin/env python3
"""Generate text embeddings with Google's EmbeddingGemma 2."""

import argparse
import json


def load_model():
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(
        "google/embeddinggemma-2",
        config_kwargs={"vision_config": None, "audio_config": None},
        model_kwargs={"dtype": "float32"},
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("texts", nargs="+", help="One or more quoted texts")
    parser.add_argument(
        "--prompt", choices=["STS", "query", "document"], default="STS",
        help="Embedding task (default: STS for semantic similarity)",
    )
    args = parser.parse_args()

    model = load_model()
    embeddings = model.encode(
        args.texts, prompt_name=args.prompt,
        normalize_embeddings=True, show_progress_bar=False,
    )
    print(json.dumps(embeddings.tolist()))


if __name__ == "__main__":
    main()
