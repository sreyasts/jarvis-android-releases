"""Extract the selected British voice without extracting unrelated archive files."""
from pathlib import Path
import hashlib
import shutil
import sys
import tarfile

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "libs/asr-download/jarvis-voice-northern.tar.bz2"
destination = root / "app/src/main/assets/models/tts"
destination.mkdir(parents=True, exist_ok=True)
selected = {"en_GB-northern_english_male-medium.onnx": "model.onnx", "tokens.txt": "tokens.txt", "MODEL_CARD": "MODEL_CARD"}
found = set()
with tarfile.open(source, "r|bz2") as archive:
    for member in archive:
        name = member.name.removeprefix("vits-piper-en_GB-northern_english_male-medium-int8/")
        if name not in selected:
            continue
        if not member.isfile() or not 0 < member.size < 100_000_000:
            raise ValueError("Invalid voice model entry")
        with archive.extractfile(member) as incoming, (destination / selected[name]).open("wb") as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        found.add(name)
if found != set(selected):
    raise ValueError("Voice archive is incomplete")
print("Extracted British voice model")
