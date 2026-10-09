"""Build the reviewed modern-artist whitelist. No images or NovelAI requests."""
import csv
from datetime import date, timedelta
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
rows = {row[0]: row for row in csv.reader(raw.decode("utf-8-sig").splitlines()) if len(row) >= 4}
review_bytes = (target / "curated-artists.json").read_bytes()
review = json.loads(review_bytes)
active_since = (date.fromisoformat(review["review_date"]) - timedelta(days=365)).isoformat()
selected = []
for artist in review["artists"]:
    tag = artist["danbooru_tag"]
    row = rows.get(tag)
    assert row and row[1] == "1", f"Not a canonical Danbooru artist: {tag}"
    assert int(row[2]) >= 50 and int(row[2]) == artist["danbooru_post_count"], f"Invalid post count: {tag}"
    assert artist["review_status"] == "accepted" and artist["identity_evidence"], f"Unreviewed identity: {tag}"
    activity_date = artist.get("latest_activity_date", artist.get("latest_pixiv_work_date"))
    assert date.fromisoformat(activity_date) >= date.fromisoformat(active_since) and artist["activity_evidence_url"], f"Inactive artist: {tag}"
    work_review = artist.get("work_review", artist.get("sfw_review"))
    assert work_review and work_review["sample_count"] > 0 and work_review["no_ai_generated_samples"], f"Missing artwork review: {tag}"
    assert artist["visual_review"]["status"] == "accepted" and artist["visual_review"]["sample_urls"], f"Missing visual review: {tag}"
    name = tag.replace("_", " ").strip().lower()
    assert name and len(name) <= 100 and not any(ch in name for ch in ",\n\r{}[]") and "::" not in name, f"Unsafe tag: {tag}"
    selected.append(name)
assert len(selected) >= 100 and len(set(selected)) == len(selected), "Default curated pool must contain at least 100 distinct artists"
out = "\n".join("artist: " + name for name in selected) + "\n"
(target / "artists.txt").write_text(out, encoding="utf-8", newline="\n")
source_metadata = {
    "dataset": "HDiffusion/historical-danbooru-tag-counts", "revision": REVISION,
    "snapshot_date": "2026-10-06", "url": SOURCE, "license": "Apache-2.0",
    "source_sha256": hashlib.sha256(raw).hexdigest(), "pool_sha256": hashlib.sha256(out.encode()).hexdigest(),
    "count": len(selected), "min_post_count": min(int(rows[a["danbooru_tag"]][2]) for a in review["artists"]),
    "review_date": review["review_date"], "review_sha256": hashlib.sha256(review_bytes).hexdigest(),
    "selection": "reviewed modern artists with emphasis on painterly rendering, soft colors, skin and fabric; category=1; post_count>=50; recent activity; manual artwork review; adult-oriented authors are eligible",
    "verification_scope": "Pinned Danbooru-derived metadata snapshot; live Danbooru API unavailable. Public artist profiles, representative artworks and official activity metadata; not a NovelAI output evaluation.",
}
preview_index = target / "previews.json"
if preview_index.exists():
    source_metadata["previews_sha256"] = hashlib.sha256(preview_index.read_bytes()).hexdigest()
    source_metadata["preview_images"] = sum(len(a["previews"]) for a in json.loads(preview_index.read_text(encoding="utf-8")))
(target / "source.json").write_text(json.dumps(source_metadata, indent=2), encoding="utf-8")
if not (target / "LICENSE-2.0.txt").exists():
    (target / "LICENSE-2.0.txt").write_bytes(urllib.request.urlopen("https://www.apache.org/licenses/LICENSE-2.0.txt", timeout=30).read())
print(json.dumps({"count": len(selected), "min_post_count": min(int(rows[a["danbooru_tag"]][2]) for a in review["artists"]), "pool_sha256": hashlib.sha256(out.encode()).hexdigest()}))
