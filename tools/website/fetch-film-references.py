"""Keep producer pages locally for the website artwork/source review."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import Request, urlopen
import json
import re

pages = {
    'charge': 'https://studio.blender.org/projects/charge/',
    'spring': 'https://studio.blender.org/projects/spring/',
    'wing-it': 'https://studio.blender.org/blog/creating-the-poster-for-wing-it/',
    'sprite-fright': 'https://studio.blender.org/projects/sprite-fright/',
    'sintel': 'https://durian.blender.org/news/movie-poster/',
    'tears-of-steel': 'https://mango.blender.org/production/film-poster/',
    'blender-terms': 'https://studio.blender.org/terms-and-conditions/',
    'mango-sharing': 'https://mango.blender.org/sharing/',
    'durian-sharing': 'https://durian.blender.org/sharing/',
}
output = Path(__file__).resolve().parents[2] / 'build/website-artwork'
output.mkdir(parents=True, exist_ok=True)

def fetch(item):
    name, url = item
    try:
        with urlopen(Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=35) as response:
            content = response.read().decode('utf-8')
        (output / (name + '.html')).write_text(content, encoding='utf-8')
        return {'page': name, 'bytes': len(content),
                'hero': re.findall(r'<meta[^>]*property="og:image"[^>]*>', content),
                'assets': list(dict.fromkeys(re.findall(r'(?:src|href)=["\']([^"\']+\.(?:jpg|jpeg|png|webp|zip))["\']', content)))[:16]}
    except Exception as error:
        return {'page': name, 'error': str(error)}

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=5) as pool:
        for result in pool.map(fetch, pages.items()):
            print(json.dumps(result))
