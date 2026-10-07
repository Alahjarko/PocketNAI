"""Extract artist-tag metadata only. Pinned public snapshot; no images or NovelAI requests."""
import csv
import hashlib
import json
import pathlib
import urllib.request

REVISION = "0f256e51b8affc379e5181f42c9dab41ceb00e4e"
SOURCE = f"https://huggingface.co/datasets/HDiffusion/historical-danbooru-tag-counts/resolve/{REVISION}/danbooru-2026-10-06.csv"
ROOT = pathlib.Path(__file__).resolve().parents[1]
target = ROOT / "app/src/main/assets/artist-lab"
target.mkdir(parents=True, exist_ok=True)
cached = ROOT / ".tooling/artist-lab/source.csv"
raw = cached.read_bytes() if cached.exists() else urllib.request.urlopen(SOURCE, timeout=60).read()
assert hashlib.sha256(raw).hexdigest() == "516f690003c3f31c0c5647fe3f2e9216ca46bb81fc5e26d8d1a7cb5c1372a448", "Pinned source digest mismatch"
artists = {}
excluded = {"unknown artist", "anonymous artist", "artist request", "various artists"}
for row in csv.reader(raw.decode("utf-8-sig").splitlines()):
    if len(row) < 3 or row[1] != "1":
        continue
    name = row[0].replace("_", " ").strip().lower()
    if not name or name in excluded or len(name) > 100 or any(ch in name for ch in ",\n\r{}[]") or "::" in name:
        continue
    artists[name] = max(artists.get(name, 0), int(row[2]))
selected = sorted(artists, key=lambda name: (-artists[name], name))[:1000]
assert len(selected) == 1000
out = "\n".join("artist: " + name for name in selected) + "\n"
(target / "artists.txt").write_text(out, encoding="utf-8", newline="\n")
(target / "source.json").write_text(json.dumps({
    "dataset": "HDiffusion/historical-danbooru-tag-counts", "revision": REVISION,
    "snapshot_date": "2026-10-06", "url": SOURCE, "license": "Apache-2.0",
    "source_sha256": hashlib.sha256(raw).hexdigest(), "pool_sha256": hashlib.sha256(out.encode()).hexdigest(),
    "count": 1000, "eligible_artists": len(artists), "selection": "category=1; sort by post count descending; normalize underscore to space; deduplicate",
}, indent=2), encoding="utf-8")
if not (target / "LICENSE-2.0.txt").exists():
    (target / "LICENSE-2.0.txt").write_bytes(urllib.request.urlopen("https://www.apache.org/licenses/LICENSE-2.0.txt", timeout=30).read())
print(json.dumps({"count": len(selected), "eligible_artists": len(artists), "min_post_count": artists[selected[-1]], "pool_sha256": hashlib.sha256(out.encode()).hexdigest()}))
