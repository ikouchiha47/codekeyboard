#!/usr/bin/env python3
"""
Streaming comparison of n-gram trigram files (KN, Katz, SwiftKey).
Files are 231-377 MB; must stream without loading entire JSON into memory.
"""

import json
import sys
import argparse
from typing import Iterator, Tuple, List, Optional, Dict, Any
from dataclasses import dataclass


@dataclass
class NgramEntry:
    ctx: str
    followers: List[List[Any]]  # [[word, score], ...]
    support: int


def stream_ngrams(filepath: str) -> Iterator[NgramEntry]:
    """
    Stream a trigram JSON file, yielding (ctx, followers, support) tuples.
    Assumes top-level keys are lexicographically sorted.
    """
    with open(filepath, 'rb') as f:
        # Read the opening brace
        first_char = f.read(1)
        if first_char != b'{':
            raise ValueError(f"Expected '{{' at start of file, got {first_char!r}")
        
        prev_ctx = None
        buffer = bytearray()
        
        while True:
            # Skip whitespace and commas
            while True:
                ch = f.read(1)
                if not ch:
                    return  # EOF
                if ch not in b' \t\n\r,':
                    break
            
            if ch == b'}':
                return  # End of object
            
            # We're at the start of a key (should be a quoted string)
            if ch != b'"':
                raise ValueError(f"Expected quoted string key, got {ch!r}")
            
            # Read the key string (handle escapes)
            key_bytes = bytearray(b'"')
            in_escape = False
            while True:
                ch = f.read(1)
                if not ch:
                    raise ValueError("Unexpected EOF while reading key")
                key_bytes += ch
                if in_escape:
                    in_escape = False
                elif ch == b'\\':
                    in_escape = True
                elif ch == b'"' and not in_escape:
                    break
            
            ctx = key_bytes.decode('utf-8')
            ctx = json.loads(ctx)  # This handles the unescaping
            
            # Verify sorted order
            if prev_ctx is not None and ctx < prev_ctx:
                raise ValueError(f"Keys not sorted: {prev_ctx!r} > {ctx!r}")
            prev_ctx = ctx
            
            # Skip whitespace and colon
            while True:
                ch = f.read(1)
                if not ch:
                    raise ValueError("Unexpected EOF after key")
                if ch not in b' \t\n\r':
                    break
            if ch != b':':
                raise ValueError(f"Expected ':' after key, got {ch!r}")
            
            # Skip whitespace
            while True:
                ch = f.read(1)
                if not ch:
                    raise ValueError("Unexpected EOF before value")
                if ch not in b' \t\n\r':
                    break
            
            # Now read the JSON value by tracking brace/bracket depth
            # The value starts with '{' for our structure
            value_bytes = bytearray(ch)
            depth_brace = 1 if ch == b'{' else 0
            depth_bracket = 0
            in_string = False
            in_escape = False
            
            while True:
                ch = f.read(1)
                if not ch:
                    raise ValueError("Unexpected EOF while reading value")
                value_bytes += ch
                
                if in_escape:
                    in_escape = False
                elif ch == b'\\' and in_string:
                    in_escape = True
                elif ch == b'"' and not in_escape:
                    in_string = not in_string
                elif not in_string:
                    if ch == b'{':
                        depth_brace += 1
                    elif ch == b'}':
                        depth_brace -= 1
                    elif ch == b'[':
                        depth_bracket += 1
                    elif ch == b']':
                        depth_bracket -= 1
                
                if depth_brace == 0 and depth_bracket == 0 and not in_string:
                    break
            
            # Parse the value
            value = json.loads(value_bytes.decode('utf-8'))
            followers = value['followers']
            support = value['support']
            
            yield NgramEntry(ctx=ctx, followers=followers, support=support)


def serialize_entry(ctx: str, followers: List[List[Any]], support: int) -> bytes:
    """Serialize a single entry exactly as the builder does."""
    entry = {"followers": followers, "support": support}
    ctx_json = json.dumps(ctx, ensure_ascii=False)
    entry_json = json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
    return (ctx_json + ":" + entry_json).encode('utf-8')


def compute_pruned_size(entries: Iterator[NgramEntry], thr: int) -> Tuple[int, int]:
    """Compute serialized size in bytes and count of kept contexts for threshold."""
    total_bytes = 2  # for { }
    count = 0
    first = True
    for e in entries:
        if e.support >= thr:
            if not first:
                total_bytes += 1  # comma
            entry_bytes = serialize_entry(e.ctx, e.followers, e.support)
            total_bytes += len(entry_bytes)
            count += 1
            first = False
    return total_bytes, count


def pair_compare(kn_path: str, katz_path: str, top_disagreements: int = 0):
    """Compare KN and Katz files (unpruned)."""
    print(f"Comparing KN vs Katz (unpruned)...")
    
    kn_stream = stream_ngrams(kn_path)
    katz_stream = stream_ngrams(katz_path)
    
    kn_entry = next(kn_stream, None)
    katz_entry = next(katz_stream, None)
    
    total_both = 0
    identical_followers = 0
    top1_agree = 0
    top1_total = 0
    disagreements = []  # (support, ctx, kn_top1, katz_top1)
    
    while kn_entry is not None and katz_entry is not None:
        if kn_entry.ctx == katz_entry.ctx:
            total_both += 1
            
            # Follower list identity
            if kn_entry.followers == katz_entry.followers:
                identical_followers += 1
            
            # Top-1 agreement
            if kn_entry.followers and katz_entry.followers:
                top1_total += 1
                kn_top1 = kn_entry.followers[0][0]
                katz_top1 = katz_entry.followers[0][0]
                if kn_top1 == katz_top1:
                    top1_agree += 1
                else:
                    disagreements.append((kn_entry.support, kn_entry.ctx, kn_top1, katz_top1))
            
            kn_entry = next(kn_stream, None)
            katz_entry = next(katz_stream, None)
        elif kn_entry.ctx < katz_entry.ctx:
            kn_entry = next(kn_stream, None)
        else:
            katz_entry = next(katz_stream, None)
    
    print(f"Contexts in both: {total_both:,}")
    print(f"Identical follower lists: {identical_followers:,} ({identical_followers/total_both*100:.1f}%)")
    print(f"Top-1 agreement: {top1_agree}/{top1_total} = {top1_agree/top1_total*100:.1f}%")
    
    if top_disagreements > 0:
        disagreements.sort(key=lambda x: -x[0])
        print(f"\nTop {top_disagreements} disagreements by support:")
        for support, ctx, kn_top1, katz_top1 in disagreements[:top_disagreements]:
            print(f"  {ctx!r}:  KN={kn_top1!r:10} Katz={katz_top1!r:10} (support={support})")


def sweep_compare(kn_path: str, katz_path: str, thresholds: List[int]):
    """Compare KN and Katz at multiple support thresholds - single pass."""
    print(f"{'thr':>4}  {'ctx_kept':>10}  {'KN MB':>8}  {'Katz MB':>8}  {'top-1 agree':>10}")
    
    # Single pass: track counts/sizes/agreement for all thresholds simultaneously
    kn_stream = stream_ngrams(kn_path)
    katz_stream = stream_ngrams(katz_path)
    
    kn_entry = next(kn_stream, None)
    katz_entry = next(katz_stream, None)
    
    # Per-threshold accumulators
    kn_bytes = {thr: 2 for thr in thresholds}  # { }
    katz_bytes = {thr: 2 for thr in thresholds}
    kn_count = {thr: 0 for thr in thresholds}
    katz_count = {thr: 0 for thr in thresholds}
    top1_agree = {thr: 0 for thr in thresholds}
    top1_total = {thr: 0 for thr in thresholds}
    
    while kn_entry is not None and katz_entry is not None:
        if kn_entry.ctx == katz_entry.ctx:
            support = kn_entry.support  # same for both
            kn_entry_bytes = serialize_entry(kn_entry.ctx, kn_entry.followers, kn_entry.support)
            katz_entry_bytes = serialize_entry(katz_entry.ctx, katz_entry.followers, katz_entry.support)
            
            for thr in thresholds:
                if support >= thr:
                    if kn_count[thr] > 0:
                        kn_bytes[thr] += 1  # comma
                    kn_bytes[thr] += len(kn_entry_bytes)
                    kn_count[thr] += 1
                    
                    if katz_count[thr] > 0:
                        katz_bytes[thr] += 1
                    katz_bytes[thr] += len(katz_entry_bytes)
                    katz_count[thr] += 1
                    
                    if kn_entry.followers and katz_entry.followers:
                        top1_total[thr] += 1
                        if kn_entry.followers[0][0] == katz_entry.followers[0][0]:
                            top1_agree[thr] += 1
            
            kn_entry = next(kn_stream, None)
            katz_entry = next(katz_stream, None)
        elif kn_entry.ctx < katz_entry.ctx:
            kn_entry = next(kn_stream, None)
        else:
            katz_entry = next(katz_stream, None)
    
    for thr in thresholds:
        kn_mb = kn_bytes[thr] / 1e6
        katz_mb = katz_bytes[thr] / 1e6
        agree_pct = top1_agree[thr] / top1_total[thr] * 100 if top1_total[thr] > 0 else 0
        print(f"{thr:4d}  {kn_count[thr]:10,d}  {kn_mb:7.1f}  {katz_mb:7.1f}  {agree_pct:9.1f}%")


def three_compare(kn_path: str, katz_path: str, sk_path: str, thresholds: List[int]):
    """Compare all three builds at multiple thresholds - single three-way merge pass."""
    # Per-threshold accumulators
    kn_bytes = {thr: 2 for thr in thresholds}
    katz_bytes = {thr: 2 for thr in thresholds}
    sk_bytes = {thr: 2 for thr in thresholds}
    kn_count = {thr: 0 for thr in thresholds}
    katz_count = {thr: 0 for thr in thresholds}
    sk_count = {thr: 0 for thr in thresholds}
    
    # Follower stats
    kn_follower_total = {thr: 0 for thr in thresholds}
    kn_follower_min = {thr: float('inf') for thr in thresholds}
    kn_follower_max = {thr: 0 for thr in thresholds}
    kn_follower_cap10 = {thr: 0 for thr in thresholds}
    kn_follower_count = {thr: 0 for thr in thresholds}
    
    katz_follower_total = {thr: 0 for thr in thresholds}
    katz_follower_min = {thr: float('inf') for thr in thresholds}
    katz_follower_max = {thr: 0 for thr in thresholds}
    katz_follower_cap10 = {thr: 0 for thr in thresholds}
    katz_follower_count = {thr: 0 for thr in thresholds}
    
    sk_follower_total = {thr: 0 for thr in thresholds}
    sk_follower_min = {thr: float('inf') for thr in thresholds}
    sk_follower_max = {thr: 0 for thr in thresholds}
    sk_follower_cap10 = {thr: 0 for thr in thresholds}
    sk_follower_count = {thr: 0 for thr in thresholds}
    
    # Top-1 agreement
    kn_katz_agree = {thr: 0 for thr in thresholds}
    kn_katz_total = {thr: 0 for thr in thresholds}
    kn_sk_agree = {thr: 0 for thr in thresholds}
    kn_sk_total = {thr: 0 for thr in thresholds}
    katz_sk_agree = {thr: 0 for thr in thresholds}
    katz_sk_total = {thr: 0 for thr in thresholds}
    
    # Dropped contexts (in KN not SK)
    dropped = 0
    
    # Single three-way merge
    kn_stream = stream_ngrams(kn_path)
    katz_stream = stream_ngrams(katz_path)
    sk_stream = stream_ngrams(sk_path)
    
    kn_entry = next(kn_stream, None)
    katz_entry = next(katz_stream, None)
    sk_entry = next(sk_stream, None)
    
    while kn_entry is not None or katz_entry is not None or sk_entry is not None:
        # Find minimum ctx among available entries
        ctxs = []
        if kn_entry is not None:
            ctxs.append(kn_entry.ctx)
        if katz_entry is not None:
            ctxs.append(katz_entry.ctx)
        if sk_entry is not None:
            ctxs.append(sk_entry.ctx)
        
        if not ctxs:
            break
            
        min_ctx = min(ctxs)
        
        # Process entries with this ctx
        has_kn = kn_entry is not None and kn_entry.ctx == min_ctx
        has_katz = katz_entry is not None and katz_entry.ctx == min_ctx
        has_sk = sk_entry is not None and sk_entry.ctx == min_ctx
        
        if has_kn and not has_sk:
            dropped += 1
        
        for thr in thresholds:
            support = kn_entry.support if has_kn else (katz_entry.support if has_katz else sk_entry.support)
            
            if support >= thr:
                # Size and count
                if has_kn:
                    if kn_count[thr] > 0:
                        kn_bytes[thr] += 1
                    kn_bytes[thr] += len(serialize_entry(kn_entry.ctx, kn_entry.followers, kn_entry.support))
                    kn_count[thr] += 1
                    
                    flen = len(kn_entry.followers)
                    kn_follower_total[thr] += flen
                    kn_follower_min[thr] = min(kn_follower_min[thr], flen)
                    kn_follower_max[thr] = max(kn_follower_max[thr], flen)
                    if flen == 10:
                        kn_follower_cap10[thr] += 1
                    kn_follower_count[thr] += 1
                
                if has_katz:
                    if katz_count[thr] > 0:
                        katz_bytes[thr] += 1
                    katz_bytes[thr] += len(serialize_entry(katz_entry.ctx, katz_entry.followers, katz_entry.support))
                    katz_count[thr] += 1
                    
                    flen = len(katz_entry.followers)
                    katz_follower_total[thr] += flen
                    katz_follower_min[thr] = min(katz_follower_min[thr], flen)
                    katz_follower_max[thr] = max(katz_follower_max[thr], flen)
                    if flen == 10:
                        katz_follower_cap10[thr] += 1
                    katz_follower_count[thr] += 1
                
                if has_sk:
                    if sk_count[thr] > 0:
                        sk_bytes[thr] += 1
                    sk_bytes[thr] += len(serialize_entry(sk_entry.ctx, sk_entry.followers, sk_entry.support))
                    sk_count[thr] += 1
                    
                    flen = len(sk_entry.followers)
                    sk_follower_total[thr] += flen
                    sk_follower_min[thr] = min(sk_follower_min[thr], flen)
                    sk_follower_max[thr] = max(sk_follower_max[thr], flen)
                    if flen == 10:
                        sk_follower_cap10[thr] += 1
                    sk_follower_count[thr] += 1
                
                # Top-1 agreement
                if has_kn and has_katz and kn_entry.followers and katz_entry.followers:
                    kn_katz_total[thr] += 1
                    if kn_entry.followers[0][0] == katz_entry.followers[0][0]:
                        kn_katz_agree[thr] += 1
                
                if has_kn and has_sk and kn_entry.followers and sk_entry.followers:
                    kn_sk_total[thr] += 1
                    if kn_entry.followers[0][0] == sk_entry.followers[0][0]:
                        kn_sk_agree[thr] += 1
                
                if has_katz and has_sk and katz_entry.followers and sk_entry.followers:
                    katz_sk_total[thr] += 1
                    if katz_entry.followers[0][0] == sk_entry.followers[0][0]:
                        katz_sk_agree[thr] += 1
        
        # Advance
        if has_kn:
            kn_entry = next(kn_stream, None)
        if has_katz:
            katz_entry = next(katz_stream, None)
        if has_sk:
            sk_entry = next(sk_stream, None)
    
    # Count remaining in KN (dropped)
    while kn_entry is not None:
        dropped += 1
        kn_entry = next(kn_stream, None)
    
    # Print results
    for thr in thresholds:
        print(f"\n=== thr={thr} ===")
        
        kn_mb = kn_bytes[thr] / 1e6
        katz_mb = katz_bytes[thr] / 1e6
        sk_mb = sk_bytes[thr] / 1e6
        
        print(f"KN:     {kn_count[thr]:>10,d} contexts, {kn_mb:.1f} MB ({kn_bytes[thr]:,} bytes)")
        print(f"Katz:   {katz_count[thr]:>10,d} contexts, {katz_mb:.1f} MB ({katz_bytes[thr]:,} bytes)")
        print(f"SK:     {sk_count[thr]:>10,d} contexts, {sk_mb:.1f} MB ({sk_bytes[thr]:,} bytes)")
        
        if kn_count[thr] != katz_count[thr]:
            print(f"  WARNING: ctx_kept differs: KN={kn_count[thr]} Katz={katz_count[thr]}")
        
        # Follower stats
        def fmt_stats(total, min_v, max_v, cap10, count):
            if count == 0:
                return "avg=0.00 min=0 max=0 cap10=0"
            avg = total / count
            return f"avg={avg:.2f} min={int(min_v)} max={max_v} cap10={cap10}"
        
        print(f"Follower stats (kept):")
        print(f"  KN:   {fmt_stats(kn_follower_total[thr], kn_follower_min[thr], kn_follower_max[thr], kn_follower_cap10[thr], kn_follower_count[thr])}")
        print(f"  Katz: {fmt_stats(katz_follower_total[thr], katz_follower_min[thr], katz_follower_max[thr], katz_follower_cap10[thr], katz_follower_count[thr])}")
        print(f"  SK:   {fmt_stats(sk_follower_total[thr], sk_follower_min[thr], sk_follower_max[thr], sk_follower_cap10[thr], sk_follower_count[thr])}")
        
        print(f"Dropped contexts (in KN not SK): {dropped:,}")
        
        # Top-1 agreement
        print(f"Top-1 agreement (kept):")
        if kn_katz_total[thr] > 0:
            print(f"  KN vs Katz: {kn_katz_agree[thr]}/{kn_katz_total[thr]} = {kn_katz_agree[thr]/kn_katz_total[thr]*100:.1f}%")
        if kn_sk_total[thr] > 0:
            print(f"  KN vs SK:   {kn_sk_agree[thr]}/{kn_sk_total[thr]} = {kn_sk_agree[thr]/kn_sk_total[thr]*100:.1f}%")
        if katz_sk_total[thr] > 0:
            print(f"  Katz vs SK: {katz_sk_agree[thr]}/{katz_sk_total[thr]} = {katz_sk_agree[thr]/katz_sk_total[thr]*100:.1f}%")


def main():
    parser = argparse.ArgumentParser(description="Compare n-gram trigram files")
    subparsers = parser.add_subparsers(dest='command', required=True)
    
    # pair subcommand
    pair_parser = subparsers.add_parser('pair', help='Compare two files (unpruned)')
    pair_parser.add_argument('kn', help='KN trigrams file')
    pair_parser.add_argument('katz', help='Katz trigrams file')
    pair_parser.add_argument('--top-disagreements', type=int, default=0, help='Show top N disagreements by support')
    
    # sweep subcommand
    sweep_parser = subparsers.add_parser('sweep', help='Compare two files at multiple thresholds')
    sweep_parser.add_argument('kn', help='KN trigrams file')
    sweep_parser.add_argument('katz', help='Katz trigrams file')
    sweep_parser.add_argument('--thr', type=int, nargs='+', default=[10, 15, 20, 25, 30, 40, 50], help='Support thresholds')
    
    # three subcommand
    three_parser = subparsers.add_parser('three', help='Compare three files at multiple thresholds')
    three_parser.add_argument('kn', help='KN trigrams file')
    three_parser.add_argument('katz', help='Katz trigrams file')
    three_parser.add_argument('sk', help='SwiftKey trigrams file')
    three_parser.add_argument('--thr', type=int, nargs='+', default=[10, 25, 40, 50], help='Support thresholds')
    
    args = parser.parse_args()
    
    if args.command == 'pair':
        pair_compare(args.kn, args.katz, args.top_disagreements)
    elif args.command == 'sweep':
        sweep_compare(args.kn, args.katz, args.thr)
    elif args.command == 'three':
        three_compare(args.kn, args.katz, args.sk, args.thr)


if __name__ == '__main__':
    main()