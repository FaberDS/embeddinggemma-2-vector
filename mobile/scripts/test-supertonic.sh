#!/bin/sh
set -eu
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/supertonic-model-directory" >&2
    exit 2
fi
models=$(cd "$1" && pwd)
source_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
speech_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/pocketask-speech-test.XXXXXX")
trap 'rm -rf "$speech_test_dir"' EXIT HUP INT TERM
mkdir -p "$speech_test_dir/Sources/Smoke"
cp "$source_root/iosApp/SupertonicHelper.swift" "$speech_test_dir/Sources/Smoke/Helper.swift"
cp "$source_root/tests/SupertonicSmoke.swift" "$speech_test_dir/Sources/Smoke/main.swift"
cat > "$speech_test_dir/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "SpeechSmoke", platforms: [.macOS(.v14)], dependencies: [
    .package(url: "https://github.com/microsoft/onnxruntime-swift-package-manager.git", exact: "1.24.2")
], targets: [.executableTarget(name: "Smoke", dependencies: [.product(name: "onnxruntime", package: "onnxruntime-swift-package-manager")])])
SWIFT
swift run --package-path "$speech_test_dir" Smoke "$models"
