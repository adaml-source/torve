"""Private, versioned user guide. Never copy this data into the static web root."""

import json
from functools import lru_cache
from pathlib import Path


@lru_cache(maxsize=1)
def load_guide() -> dict:
    # A single reviewed edition avoids serving obsolete translated instructions.
    # Add translated editions only when their revision matches this edition.
    return json.loads((Path(__file__).parent / "guide" / "catalog.json").read_text(encoding="utf-8"))
