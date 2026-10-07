# Artist tag metadata

Tag names are derived from [HDiffusion/historical-danbooru-tag-counts](https://huggingface.co/datasets/HDiffusion/historical-danbooru-tag-counts),
published by HDiffusion with the Apache License 2.0 designation. The original
metadata originates from Danbooru. No artwork or image files are included.

PocketNAI changes: manually curate modern, recently active illustrators from
public SFW Pixiv/pixivision features and artist profiles; require canonical category 1 artist tags
with at least 50 posts in the pinned snapshot; normalize underscores to spaces
and deduplicate. See `curated-artists.json` for identity, activity and visual
review evidence, and `source.json` for the pinned revision and SHA-256 digests.
License for the source metadata: `LICENSE-2.0.txt`.
No artwork is redistributed. Follower counts are not a selection criterion.
Public SFW samples do not certify an account's entire historical output.
Rebuild: `scripts/build-artist-pool.py`.

These are source tags, not a NovelAI compatibility list. Recognition and style
strength depend on the selected NovelAI model.
