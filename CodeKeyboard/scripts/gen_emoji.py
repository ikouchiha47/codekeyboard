#!/usr/bin/env python3
"""
Generate emoji.json from Unicode emoji-test.txt.

Usage:
    python scripts/gen_emoji.py

Downloads emoji-test.txt (Unicode 15.0) if not already cached at
scripts/emoji-test.txt. Outputs to
android/app/src/main/assets/emoji.json.

Output format:
[
  {
    "category": "Smileys & Emotion",
    "emoji": [
      { "base": "😀", "variants": [] },
      { "base": "👋", "variants": ["👋🏻","👋🏼","👋🏽","👋🏾","👋🏿"] }
    ]
  },
  ...
]

Only "fully-qualified" entries are included (filters out minimally-qualified
and unqualified duplicates). Skin-tone variants (U+1F3FB..1F3FF) are grouped
under their base codepoint.
"""

import json
import os
import re
import urllib.request
from pathlib import Path

EMOJI_TEST_URL = (
    "https://unicode.org/Public/emoji/15.0/emoji-test.txt"
)
SCRIPT_DIR = Path(__file__).parent
CACHE_FILE = SCRIPT_DIR / "emoji-test.txt"
OUTPUT_FILE = (
    SCRIPT_DIR.parent
    / "android"
    / "app"
    / "src"
    / "main"
    / "assets"
    / "emoji.json"
)

SKIN_TONES = {
    "\U0001f3fb",  # light
    "\U0001f3fc",  # medium-light
    "\U0001f3fd",  # medium
    "\U0001f3fe",  # medium-dark
    "\U0001f3ff",  # dark
}


def download_if_needed():
    if not CACHE_FILE.exists():
        print(f"Downloading {EMOJI_TEST_URL} ...")
        urllib.request.urlretrieve(EMOJI_TEST_URL, CACHE_FILE)
        print("Done.")
    else:
        print(f"Using cached {CACHE_FILE}")


def strip_skin_tone(emoji: str) -> str:
    return "".join(c for c in emoji if c not in SKIN_TONES)


def has_skin_tone(emoji: str) -> bool:
    return any(c in SKIN_TONES for c in emoji)


def parse(text: str):
    # Structure: list of {category, emoji:[{base, variants:[]}]}
    categories = []
    current_cat = None
    # base -> index in current category's emoji list
    base_index: dict[str, int] = {}

    for line in text.splitlines():
        # Comment lines carry group/subgroup markers
        if line.startswith("# group:"):
            cat_name = line[len("# group:"):].strip()
            current_cat = {"category": cat_name, "emoji": []}
            categories.append(current_cat)
            base_index = {}
            continue

        if line.startswith("#") or not line.strip():
            continue

        # Data line: <codepoints> ; <status> # <emoji> ...
        m = re.match(r"^([0-9A-F ]+)\s*;\s*(\S+)\s*#\s*(\S+)", line)
        if not m:
            continue
        status = m.group(2)
        emoji = m.group(3)

        if status != "fully-qualified":
            continue
        if current_cat is None:
            continue

        if has_skin_tone(emoji):
            base = strip_skin_tone(emoji)
            if base in base_index:
                idx = base_index[base]
                current_cat["emoji"][idx]["variants"].append(emoji)
        else:
            base_index[emoji] = len(current_cat["emoji"])
            current_cat["emoji"].append({"base": emoji, "variants": []})

    # Drop empty categories
    return [c for c in categories if c["emoji"]]


def main():
    download_if_needed()
    text = CACHE_FILE.read_text(encoding="utf-8")
    data = parse(text)
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    total = sum(len(c["emoji"]) for c in data)
    print(
        f"Wrote {len(data)} categories, {total} emoji to {OUTPUT_FILE}"
    )


if __name__ == "__main__":
    main()
