#!/usr/bin/env python3
"""
Task B (ADR-012): staged sample-corpus prep.

Streams OpenSubtitles (gzip) and optionally SwiftKey, applies the same cleaning
as clean_opensubtitles.py (speaker-label drop, dash-prefix strip, min-words),
then uniform-samples by stable line hash (deterministic N% slice across the
whole file), dedupes exact lines per shard, and writes staged shards for the
n-gram count stage (ADR-012 task C).

Design constraints:
- Disk is nearly full; the gz decompresses to ~14 GB. Nothing here materializes
  the full text — we stream and only write the sampled shards.
- Memory stays bounded: the exact-dedup set is per-shard and cleared at each
  shard boundary.
- Sampling is Bernoulli-over-hash so it is deterministic under reruns, unlike
  head-N which would bias to the first subtitles.

Usage:
    python3 scripts/prep_corpus.py \\
        --opensubs ~/Downloads/en.txt.gz \\
        --swiftkey android/scripts/corpus_raw/swiftkey/build/swiftkey_all.txt \\
        --out /var/.../adr012_sample/ \\
        --pct 10 --min-words 3 --shard-lines 500000
"""

import argparse
import gzip
import hashlib
import sys
from pathlib import Path

import re

SPEAKER_LABEL_RE = re.compile(r"^[A-Z][a-zA-Z' ]{0,20}:\s*$")
DASH_PREFIX_RE = re.compile(r"^-\s*")


def sample_line(line: str, pct: int) -> bool:
    """Deterministic ~pct% sample: keep if hash(line) % 100 < pct."""
    h = hashlib.blake2b(line.encode("utf-8", "ignore"), digest_size=8).digest()
    return (int.from_bytes(h, "big") % 100) < pct


def clean_line(line: str, min_words: int) -> str | None:
    """Returns cleaned line, or None if dropped."""
    line = line.strip()
    if not line:
        return None
    if SPEAKER_LABEL_RE.match(line):
        return None
    line = DASH_PREFIX_RE.sub("", line).strip()
    if len(line.split()) < min_words:
        return None
    return line


def line_key(line: str) -> bytes:
    return hashlib.blake2b(line.encode("utf-8", "ignore"), digest_size=8).digest()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--opensubs", required=True, help="OpenSubtitles .gz path")
    p.add_argument("--swiftkey", help="Optional SwiftKey .txt to merge in (full)")
    p.add_argument("--out", required=True, help="Staging dir for shards")
    p.add_argument("--pct", type=int, default=10, help="Sampling percent (default 10)")
    p.add_argument("--min-words", type=int, default=3, help="Min words to keep (default 3)")
    p.add_argument("--shard-lines", type=int, default=500_000, help="Lines per shard (default 500k)")
    args = p.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    pct = max(1, min(100, args.pct))
    stats = {"lines": 0, "kept": 0, "dropped_blank": 0, "dropped_short": 0,
             "dedup": 0, "shards": 0, "swiftkey_lines": 0}
    seen: set[bytes] = set()

    def flush_shard(batch: list[str], shard_i: int) -> int:
        if not batch:
            return shard_i
        shard_path = out / f"shard_{shard_i:04d}.txt"
        with shard_path.open("w", encoding="utf-8") as f:
            f.writelines(batch)
        stats["shards"] += 1
        return shard_i + 1

    def handle(line_str: str, stats_: dict, seen_: set[bytes], is_swift: bool = False):
        # Returns the tracked stats dict, mutating in place.
        line_norm = line_str.rstrip("\n")
        if not line_norm.strip():
            stats_["dropped_blank"] += 1
            return
        if not is_swift and not sample_line(line_norm, pct):
            return
        if is_swift:
            stats_["swiftkey_lines"] += 1
        c = clean_line(line_norm, args.min_words)
        if c is None:
            stats_["dropped_short"] += 1
            return
        k = line_key(c)
        if k in seen_:
            stats_["dedup"] += 1
            return
        seen_.add(k)
        stats_["kept"] += 1
        return (c + "\n")

    shard_i = 0
    batch: list[str] = []
    seen_set: set[bytes] = set()
    stats["lines"] = 0

    open_gz = gzip.open(args.opensubs, "rt", encoding="utf-8", errors="ignore")
    with open_gz as f:
        for raw in f:
            stats["lines"] += 1
            out_line = handle(raw, stats, seen_set)
            if out_line:
                if len(batch) >= args.shard_lines:
                    shard_i = flush_shard(batch, shard_i)
                    batch = []
                    seen_set.clear()
                batch.append(out_line)

    if args.swiftkey:
        with open(args.swiftkey, "r", encoding="utf-8", errors="ignore") as f:
            for raw in f:
                stats["lines"] += 1
                out_line = handle(raw, stats, seen_set, is_swift=True)
                if out_line:
                    if len(batch) >= args.shard_lines:
                        shard_i = flush_shard(batch, shard_i)
                        batch = []
                        seen_set.clear()
                    batch.append(out_line)

    # Final, partial shard
    shard_i = flush_shard(batch, shard_i)

    # Sizing manifest for task D
    mani = out / "sizing.txt"
    with mani.open("w") as f:
        f.write(f"pct={pct}\n")
        f.write(f"lines_processed={stats['lines']}\n")
        f.write(f"kept={stats['kept']}\n")
        f.write(f"shards={shard_i}\n")
    print(f"pct={pct}%  " + "  ".join(f"{k}={v}" for k, v in stats.items()))
    print(f"Wrote {shard_i} shards -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()