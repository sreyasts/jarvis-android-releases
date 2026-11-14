"""Extract only the pinned Whisper assets; never extract arbitrary archive paths."""
from __future__ import annotations
import hashlib
import sys
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
archive_path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "libs/asr-download/whisper-base.tar.bz2"
destination = root / "app/src/main/assets/models/whisper"
destination.mkdir(parents=True, exist_ok=True)
names = ("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt")
found: set[str] = set()
with tarfile.open(archive_path, "r|bz2") as archive:
    for member in archive:
        name = member.name.removeprefix("sherpa-onnx-whisper-base/")
        if name not in names and name != "test_wavs/0.wav":
            continue
        if not member.isfile() or not 0 < member.size < 200_000_000:
            raise ValueError(f"Invalid model entry: {name}")
        source = archive.extractfile(member)
        if source is None:
            raise ValueError(f"Missing model entry: {name}")
        target = destination / name if name in names else root / "app/src/androidTest/assets/english-speech.wav"
        target.parent.mkdir(parents=True, exist_ok=True)
        with source, target.open("wb") as output:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        if name in names:
            found.add(name)
if found != set(names):
    raise ValueError("The archive is missing required speech models")
lines = []
for name in names:
    with (destination / name).open("rb") as model:
        lines.append(f"{hashlib.file_digest(model, 'sha256').hexdigest()}  {name}")
(destination / "checksums.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
