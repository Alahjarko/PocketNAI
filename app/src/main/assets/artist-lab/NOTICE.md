# Artist tag metadata

Tag names are derived from [HDiffusion/historical-danbooru-tag-counts](https://huggingface.co/datasets/HDiffusion/historical-danbooru-tag-counts),
published by HDiffusion with the Apache License 2.0 designation. The original
metadata originates from Danbooru. No artwork or image files are included.

PocketNAI changes: select category 1 (artists), filter ambiguous/unsafe syntax,
normalize underscores to spaces, deduplicate, sort by descending post count,
and keep the first 1,000 entries. See `source.json` for the pinned revision,
date, rules, and SHA-256 digests. License: `LICENSE-2.0.txt`.
Rebuild: `scripts/build-artist-pool.py`.

These are source tags, not a NovelAI compatibility list. Recognition and style
strength depend on the selected NovelAI model.
