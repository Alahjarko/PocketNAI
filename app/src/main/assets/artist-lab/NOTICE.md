# Artist tag metadata

Tag names are derived from [HDiffusion/historical-danbooru-tag-counts](https://huggingface.co/datasets/HDiffusion/historical-danbooru-tag-counts),
published by HDiffusion with the Apache License 2.0 designation. The original
metadata originates from Danbooru. This license designation applies to the tag
metadata, not to the artwork thumbnails.

PocketNAI changes: manually curate modern, recently active illustrators from
public representative works and artist profiles; require canonical category 1 artist tags
with at least 50 posts in the pinned snapshot; normalize underscores to spaces
and deduplicate. See `curated-artists.json` for identity, activity and visual
review evidence, and `source.json` for the pinned revision and SHA-256 digests.
License for the source metadata: `LICENSE-2.0.txt`.
Rebuild: `scripts/build-artist-pool.py`.

Small public artwork previews in `previews/` are included for inspecting and
customizing the local artist pool. Copyright remains with each original artist.
`previews.json` credits the artist and links each thumbnail to its original work;
the app provides the same original-work link. Only 250px previews are requested,
without downloading original-resolution artwork or using account credentials.
Rebuild: `scripts/build-artist-previews.py`.

These are source tags, not a NovelAI compatibility list. Recognition and style
strength depend on the selected NovelAI model.
