"""Apply reviewed live-source additions without rewriting the historical snapshot.

Both catalog rebuild scripts call this after rebuilding the original reviewed pool.
The checked-in manifest and bounded thumbnails make additions reproducible offline.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'app/src/main/assets/artist-lab'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def apply_live_expansion():
    manifest_path = TARGET / 'live-expansion.json'
    if not manifest_path.exists():
        return
    source = json.loads((TARGET / 'source.json').read_text(encoding='utf-8'))
    base_raw = (TARGET / 'curated-artists.json').read_bytes()
    assert digest(base_raw) == source['review_sha256'], 'Historical review digest mismatch'
    base = json.loads(base_raw)['artists']
    base_tags = ['artist: ' + a['danbooru_tag'].replace('_', ' ') for a in base]
    tag_bytes = (TARGET / 'artists.txt').read_bytes()
    preview_bytes = (TARGET / 'previews.json').read_bytes()
    assert digest(tag_bytes) == source['pool_sha256'], 'Existing tag digest mismatch'
    assert digest(preview_bytes) == source['previews_sha256'], 'Existing preview digest mismatch'
    existing_previews = json.loads(preview_bytes)
    by_tag = {a['tag']: a for a in existing_previews}
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    assert manifest['schema_version'] == 1
    additions = manifest['artists']
    added_tags = [a['entry']['tag'] for a in additions]
    final_tags = base_tags + added_tags
    assert len(set(final_tags)) == len(final_tags), 'Duplicate artist in expansion'
    assert tag_bytes.decode('utf-8').splitlines() in [base_tags, final_tags], 'Unexpected base artist list'
    assert [a['tag'] for a in existing_previews] in [base_tags, final_tags], 'Unexpected preview order'
    entries = [by_tag[t] for t in base_tags]
    for artist in additions:
        entry = artist['entry']
        record = artist['tag_record']
        assert record['name'] == entry['tag'].removeprefix('artist: ').replace(' ', '_')
        assert record['category'] == 1 and record['post_count'] >= 50 and not record['is_deprecated']
        assert artist['visual_review']['status'] == 'accepted' and artist['visual_review']['sample_urls']
        assert artist['x_profile_url'] == entry['profileUrl'] and artist['followers_approx'] >= 50_000
        assert artist['latest_account_activity']['date'][:10] >= manifest['active_since']
        assert entry['previews']
        expected_previews = {p['asset']: p for p in artist['preview_evidence']}
        for preview in entry['previews']:
            name = preview['asset']
            assert Path(name).name == name and name.endswith('.jpg')
            raw = (TARGET / 'previews' / name).read_bytes()
            assert len(raw) < 180_000 and raw[:2] == b'\xff\xd8'
            assert digest(raw) == expected_previews[name]['sha256'], 'Expansion image digest mismatch'
            assert preview['sourceUrl'] == expected_previews[name]['source_url']
        entries.append(entry)
    out = ('\n'.join(final_tags) + '\n').encode('utf-8')
    previews_out = (json.dumps(entries, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    (TARGET / 'artists.txt').write_bytes(out)
    (TARGET / 'previews.json').write_bytes(previews_out)
    source.update(pool_sha256=digest(out), count=len(final_tags), previews_sha256=digest(previews_out),
                  preview_images=sum(len(e['previews']) for e in entries))
    source['live_expansion'] = {
        'path': 'live-expansion.json', 'sha256': digest(manifest_bytes), 'count': len(additions),
        'checked_at': manifest['checked_at'], 'source': 'Danbooru tag and artist records; public X profiles',
    }
    source['verification_scope'] = ('Historical reviewed artists use the pinned Danbooru-derived snapshot; '
        'live-expansion.json records separately checked live Danbooru tags, X account activity and followers. '
        'Representative thumbnails were visually reviewed; no NovelAI generation or style-mixing evaluation.')
    (TARGET / 'source.json').write_text(json.dumps(source, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'artists': len(final_tags), 'added': len(additions), 'preview_images': source['preview_images']}))


if __name__ == '__main__':
    apply_live_expansion()
