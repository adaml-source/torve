"""Cheap freshness check for the manual's recorded source review."""
from pathlib import Path
import hashlib
import json
import sys

root = Path(__file__).resolve().parents[1]
review = json.loads((root / 'docs/guide-coverage-2026-10-12.json').read_text(encoding='utf-8'))
guide = json.loads((root / 'server/app/guide/catalog.json').read_text(encoding='utf-8'))
failures = []
if hashlib.sha256((root / 'server/app/guide/catalog.json').read_bytes()).hexdigest() != review['guide_sha256']:
    failures.append('Guide content has changed since its recorded review.')
if review['guide_revision'] != guide['revision']:
    failures.append('Guide revision is not covered by the recorded review.')
if {a['id'] for a in guide['articles']} != {a['article'] for a in review['coverage']}:
    failures.append('Article inventory has changed; record its source review.')
for source in review['sources']:
    path = root / source['path']
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
        topics = [a['article'] for a in review['coverage'] if source['path'] in a['reviewed_sources']]
        failures.append(f"Review needed: {source['path']} ({len(topics)} mapped topics)")
if failures:
    print('\n'.join(failures))
    sys.exit(1)
print(f"Guide coverage current: {len(guide['articles'])} topics, {len(review['sources'])} source snapshots.")
