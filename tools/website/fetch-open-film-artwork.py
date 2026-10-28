"""Fetch the official, attributed open-film artwork used in Torve's site previews."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import Request, urlopen
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'web/assets/open-films'
ASSETS = {
    'charge-poster.jpg': 'https://studio.blender.org/files/public/thumbnail/34/34b00ffed48256e3a873c31c574e8f08/34b00ffed48256e3a873c31c574e8f08.jpg',
    'spring-poster.jpg': 'https://studio.blender.org/files/public/thumbnail/b5/b5b96c3ca39745d4bc46b62e72c51bf4/b5b96c3ca39745d4bc46b62e72c51bf4.jpg',
    'sprite-fright-poster.jpg': 'https://studio.blender.org/files/public/thumbnail/59/5927dc487d457a39f4439d0423675c7c/5927dc487d457a39f4439d0423675c7c.jpg',
    'wing-it-poster.png': 'https://studio.blender.org/files/public/thumbnail/e8/e8d9792830d14f07d2285767ccee7e41/e8d9792830d14f07d2285767ccee7e41.png',
    'sintel-poster.jpg': 'https://durian.blender.org/wp-content/uploads/2010/09/sintel_poster.jpg',
    'tears-of-steel-poster.jpg': 'https://mango.blender.org/wp-content/uploads/2012/09/tos-poster.jpg',
    'charge-scene.png': 'https://studio.blender.org/files/public/header/project/f6/76/f67601c6f69b2d7f65a5e5d97e73c4d7.png',
    'spring-scene.png': 'https://studio.blender.org/files/public/header/project/e5/0f/e50f13c31a485f3a05d4983f1b4ab7ee.png',
    'sprite-fright-scene.jpg': 'https://studio.blender.org/files/public/header/project/ff/3d/ff3d8a8048e767cbc92d6489404924b2.jpg',
    'tears-of-steel-scene.jpg': 'https://mango.blender.org/wp-content/gallery/4k-renders/01_thom_celia_bridge.jpg',
}

def fetch(item):
    name, url = item
    target = OUTPUT / name
    if not target.exists():
        with urlopen(Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=45) as response:
            data = response.read()
            if not response.headers.get('Content-Type', '').startswith('image/'):
                raise ValueError('Expected an image for ' + name)
        target.write_bytes(data)
    data = target.read_bytes()
    return {'file': name, 'source': url, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

if __name__ == '__main__':
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        records = list(pool.map(fetch, ASSETS.items()))
    (OUTPUT / 'downloads.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
    for record in records:
        print(record['file'], record['bytes'])
