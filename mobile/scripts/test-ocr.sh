#!/bin/sh
set -eu
source_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ocr_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/pocketask-ocr-test.XXXXXX")
trap 'rm -rf "$ocr_test_dir"' EXIT HUP INT TERM
xcrun swiftc -target arm64-apple-macos14.0 "$source_root/iosApp/IOSOcr.swift" "$source_root/tests/OcrSmoke.swift" -o "$ocr_test_dir/ocr-smoke"
"$ocr_test_dir/ocr-smoke"
