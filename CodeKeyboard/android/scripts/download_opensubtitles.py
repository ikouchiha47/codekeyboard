#!/usr/bin/env python3
"""
Streams a sample of English OpenSubtitles monolingual text without ever
downloading the full (3.66GB gzip) file to disk.

## Why OpenSubtitles

`bigrams.json` (see docs/adr-001-bigram-prediction.md) was originally built
from Norvig's count_2w.txt, which is itself derived from the Google Books
n-gram corpus — formal, literary-register text. The ADR's own "Consequences"
section documents the resulting mismatch directly: "Norvig corpus is Google
Books (formal text) — suggestions skew literary rather than conversational
('great' -> 'deals, prices, for' rather than 'job, work, stuff')".

The production unigram dictionary (`en.trie`) does not have this problem:
per scripts/gen_wordlist.py's own docstring, its frequencies are built from
hermitdave/FrequencyWords (https://github.com/hermitdave/FrequencyWords),
which is itself derived from OpenSubtitles — confirmed directly from that
project's README ("The data originates from OpenSubtitles, a subtitle
corpus"). So `en.trie` is already casual/spoken register; `bigrams.json`
(Norvig/Google-Books) was not — a real register mismatch between the two
layers of the existing model. OpenSubtitles (real film/TV dialogue,
transcribed) is casual, spoken, contraction-heavy English — the same
register people actually type on a phone keyboard, and the same family
`en.trie` already comes from — so building bigrams/trigrams from it keeps
every n-gram level of the model in the same register instead of just the
unigram layer.

Source: https://opus.nlpl.eu/OpenSubtitles/corpus/version/OpenSubtitles
Direct file: https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz
(part of OPUS, the Open Parallel Corpus project; the mono/en.txt.gz file is
one sentence per line, already tokenized-ish, no per-line metadata to strip
beyond what clean_opensubtitles.py handles).

## Why streamed, not downloaded

The full gzip is 3.66GB compressed (uncompresses to far more). We only need
a few million lines to build reliable trigram counts, and re-downloading a
multi-GB file for every experiment is wasteful. This script opens the URL
as an HTTP stream, wraps it in gzip.GzipFile for on-the-fly decompression,
and stops reading as soon as the requested line count is hit — the
connection is closed and no more bytes are pulled over the wire after that.

Usage:
    python3 android/scripts/download_opensubtitles.py \
        --lines 3000000 \
        --output android/scripts/corpus_raw/opensubtitles/en_sample.txt

Output directory is gitignored (see android/.gitignore: corpus_raw/) since
raw downloaded corpus text should never be committed to the repo.
"""

import argparse
import gzip
import sys
import urllib.request
from pathlib import Path

OPENSUBTITLES_URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz"
DEFAULT_LINES = 3_000_000
PROGRESS_EVERY = 500_000


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default=OPENSUBTITLES_URL,
                   help="Source gzip URL (streamed, never fully downloaded).")
    p.add_argument("--lines", type=int, default=DEFAULT_LINES,
                   help="Number of lines to sample before stopping the stream.")
    p.add_argument("--output", default=str(Path(__file__).parent / "corpus_raw" / "opensubtitles" / "en_sample.txt"),
                   help="Where to write the sampled plain-text lines.")
    return p.parse_args()


def main():
    args = parse_args()
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"Streaming {args.url} (target {args.lines} lines, stopping early — "
          f"full file is 3.66GB compressed, never fully downloaded)...", file=sys.stderr)

    req = urllib.request.Request(args.url, headers={"User-Agent": "codekeyboard-corpus-fetch/1.0"})
    written = 0
    with urllib.request.urlopen(req) as resp, gzip.GzipFile(fileobj=resp) as gz, \
            out_path.open("w", encoding="utf-8", errors="ignore") as out:
        for raw_line in gz:
            if written >= args.lines:
                break
            try:
                line = raw_line.decode("utf-8", errors="ignore")
            except Exception:
                continue
            out.write(line if line.endswith("\n") else line + "\n")
            written += 1
            if written % PROGRESS_EVERY == 0:
                print(f"  ...{written} lines written", file=sys.stderr)

    print(f"Done: {written} lines written to {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
