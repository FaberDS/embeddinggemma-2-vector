"""Exercise real PDF extraction and Chroma storage without downloading a model."""

import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import chromadb
import numpy as np
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from pdf_search import ingest, search


def write_pdf(path, pages):
    writer = PdfWriter()
    for text in pages:
        page = writer.add_blank_page(width=612, height=792)
        font = DictionaryObject({
            NameObject("/Type"): NameObject("/Font"),
            NameObject("/Subtype"): NameObject("/Type1"),
            NameObject("/BaseFont"): NameObject("/Helvetica"),
        })
        page[NameObject("/Resources")] = DictionaryObject({
            NameObject("/Font"): DictionaryObject({NameObject("/F1"): writer._add_object(font)})
        })
        stream = DecodedStreamObject()
        escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        stream.set_data(f"BT /F1 12 Tf 50 700 Td ({escaped}) Tj ET".encode("ascii"))
        page[NameObject("/Contents")] = writer._add_object(stream)
    writer.write(path)


class TestModel:
    """Deterministic vectors to test plumbing, not model retrieval quality."""

    tokenizer = None

    def __init__(self):
        self.tokenizer = self
        self.prompts = []

    def encode(self, texts, **kwargs):
        if isinstance(texts, str):
            return texts.split()
        self.prompts.append(kwargs["prompt_name"])
        vectors = np.array([
            [text.lower().count("reset"), text.lower().count("sales"), 0.1]
            for text in texts
        ], dtype=np.float32)
        return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)

    def decode(self, tokens):
        return " ".join(tokens)


class PdfSearchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db = self.root / "db"
        self.collection = chromadb.PersistentClient(path=str(self.db)).create_collection(
            "test-pdfs", embedding_function=None,
            configuration={"hnsw": {"space": "cosine"}},
        )
        self.model = TestModel()
        self.pdf = self.root / "manual.pdf"

    def index(self, path=None):
        with contextlib.redirect_stderr(io.StringIO()):
            ingest([path or self.pdf], self.model, self.collection, batch_size=1)

    def test_search_returns_source_page_and_persists(self):
        write_pdf(self.pdf, ["Sales rose this year.", "Press reset to restart."])
        self.index()
        results = search("reset", self.model, self.collection, 5)
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]["page"], 2)
        self.assertEqual(results[0]["file"], "manual.pdf")
        self.assertEqual(results[0]["source"], str(self.pdf.resolve()))
        self.assertIn("reset", results[0]["text"])
        self.assertGreater(results[0]["score"], results[1]["score"])
        self.assertEqual(self.model.prompts, ["document", "query"])
        code = (
            "import chromadb, sys; "
            "c = chromadb.PersistentClient(path=sys.argv[1]).get_collection('test-pdfs', embedding_function=None); "
            "print(c.count())"
        )
        output = subprocess.check_output([sys.executable, "-c", code, str(self.db)], text=True)
        self.assertEqual(output.strip(), "2")

    def test_reingest_removes_stale_chunks_and_keeps_other_files(self):
        write_pdf(self.pdf, ["reset " * 450, "Old sales page."])
        self.index()
        other = self.root / "other.pdf"
        write_pdf(other, ["Other sales report."])
        self.index(other)
        self.assertEqual(self.collection.count(), 4)
        write_pdf(self.pdf, ["New reset instructions."])
        self.index()
        self.index()
        self.assertEqual(self.collection.count(), 2)
        rows = self.collection.get(where={"source": str(self.pdf.resolve())})
        self.assertEqual(rows["documents"], ["New reset instructions."])

    def test_empty_pdf_preserves_existing_entries(self):
        write_pdf(self.pdf, ["reset instructions"])
        self.index()
        write_pdf(self.pdf, [""])
        with self.assertRaisesRegex(ValueError, "no extractable text"):
            self.index()
        self.assertEqual(self.collection.count(), 1)

    def test_empty_database_has_actionable_error(self):
        with self.assertRaisesRegex(ValueError, "Ingest PDFs first"):
            search("reset", self.model, self.collection, 5)


if __name__ == "__main__":
    unittest.main()
