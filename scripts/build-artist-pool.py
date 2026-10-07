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
    assert date.fromisoformat(artist["latest_pixiv_work_date"]) >= date.fromisoformat(active_since), f"Inactive artist: {tag}"
    assert artist["sfw_review"]["sample_count"] > 0 and artist["sfw_review"]["all_x_restrict_zero"], f"Missing SFW review: {tag}"
    assert not artist["sfw_review"]["adult_profile_marker"] and artist["sfw_review"]["no_ai_generated_samples"], f"Excluded profile: {tag}"
    assert artist["visual_review"]["status"] == "accepted" and artist["visual_review"]["sample_urls"], f"Missing visual review: {tag}"
    name = tag.replace("_", " ").strip().lower()
    assert name and len(name) <= 100 and not any(ch in name for ch in ",\n\r{}[]") and "::" not in name, f"Unsafe tag: {tag}"
    selected.append(name)
assert len(selected) >= 100 and len(set(selected)) == len(selected), "Default curated pool must contain at least 100 distinct artists"
out = "\n".join("artist: " + name for name in selected) + "\n"
(target / "artists.txt").write_text(out, encoding="utf-8", newline="\n")
(target / "source.json").write_text(json.dumps({
    "dataset": "HDiffusion/historical-danbooru-tag-counts", "revision": REVISION,
    "snapshot_date": "2026-10-06", "url": SOURCE, "license": "Apache-2.0",
    "source_sha256": hashlib.sha256(raw).hexdigest(), "pool_sha256": hashlib.sha256(out.encode()).hexdigest(),
    "count": len(selected), "min_post_count": min(int(rows[a["danbooru_tag"]][2]) for a in review["artists"]),
    "review_date": review["review_date"], "review_sha256": hashlib.sha256(review_bytes).hexdigest(),
    "selection": "broad modern-artist whitelist from known artist profiles and public SFW features; category=1; post_count>=50; recent activity; manual artwork review; no follower criterion",
    "verification_scope": "Pinned Danbooru-derived metadata snapshot; live Danbooru API unavailable. Pixiv public profile/top samples and official features; not entire account history.",
}, indent=2), encoding="utf-8")
if not (target / "LICENSE-2.0.txt").exists():
    (target / "LICENSE-2.0.txt").write_bytes(urllib.request.urlopen("https://www.apache.org/licenses/LICENSE-2.0.txt", timeout=30).read())
print(json.dumps({"count": len(selected), "min_post_count": min(int(rows[a["danbooru_tag"]][2]) for a in review["artists"]), "pool_sha256": hashlib.sha256(out.encode()).hexdigest()}))
