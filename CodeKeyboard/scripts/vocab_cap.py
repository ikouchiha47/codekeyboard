#!/usr/bin/env python3
"""
Measure vocabulary tail impact on n-gram model suggestion quality.
Streams the trigram model (230MB-1.9GB) without loading fully into memory.
"""

import json
import sys
import argparse
from typing import Iterator, List, Tuple, Set, Dict, Any
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


def load_unigrams(unigrams_path: str) -> List[str]:
    """
    Load unigrams TSV (word\tcount), return list of words sorted by count DESC.
    The file is already sorted by count descending.
    """
    words = []
    with open(unigrams_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split('\t')
            if len(parts) >= 1:
                words.append(parts[0])
    return words


def analyze_cap(model_path: str, vocab: Set[str], thr: int = 25) -> Dict[str, Any]:
    """
    Single-pass analysis of the model with a given vocabulary cap.
    Returns metrics dict.
    """
    stream = stream_ngrams(model_path)
    
    contexts_kept = 0
    top1_agree = 0
    top1_total = 0
    emptied = 0
    follower_entries_dropped = 0
    total_follower_entries = 0
    full_size_bytes = 2  # { }
    filtered_size_bytes = 2  # { }
    first_full = True
    first_filtered = True
    distinct_words_in_model = set()
    distinct_words_outside_vocab = set()
    
    for entry in stream:
        if entry.support < thr:
            continue
        
        contexts_kept += 1
        
        # Full model serialization size
        full_entry_bytes = serialize_entry(entry.ctx, entry.followers, entry.support)
        if not first_full:
            full_size_bytes += 1  # comma
        full_size_bytes += len(full_entry_bytes)
        first_full = False
        
        # Track all distinct words in the model
        for follower in entry.followers:
            word = follower[0]
            distinct_words_in_model.add(word)
        
        # Filter followers by vocab
        filtered = [f for f in entry.followers if f[0] in vocab]
        total_follower_entries += len(entry.followers)
        follower_entries_dropped += len(entry.followers) - len(filtered)
        
        # Track words outside vocab
        for follower in entry.followers:
            if follower[0] not in vocab:
                distinct_words_outside_vocab.add(follower[0])
        
        # Filtered model serialization size
        if filtered:
            filtered_entry_bytes = serialize_entry(entry.ctx, filtered, entry.support)
            if not first_filtered:
                filtered_size_bytes += 1  # comma
            filtered_size_bytes += len(filtered_entry_bytes)
            first_filtered = False
        else:
            emptied += 1
        
        # Top-1 agreement
        if entry.followers:
            full_top1 = entry.followers[0][0]
            top1_total += 1
            if filtered and filtered[0][0] == full_top1:
                top1_agree += 1
    
    return {
        'contexts_kept': contexts_kept,
        'top1_agree': top1_agree,
        'top1_total': top1_total,
        'emptied': emptied,
        'follower_entries_dropped': follower_entries_dropped,
        'total_follower_entries': total_follower_entries,
        'full_size_bytes': full_size_bytes,
        'filtered_size_bytes': filtered_size_bytes,
        'distinct_words_in_model': len(distinct_words_in_model),
        'distinct_words_outside_vocab': len(distinct_words_outside_vocab),
    }


def main():
    parser = argparse.ArgumentParser(description="Measure vocabulary cap impact on n-gram model quality")
    parser.add_argument('--unigrams', required=True, help='Unigrams TSV file (word\\tcount, sorted by count DESC)')
    parser.add_argument('--model', required=True, help='Trigram model JSON file (cap10 variant)')
    parser.add_argument('--caps', type=int, nargs='+', default=[16000, 32000, 64000, 128000], help='Vocabulary caps to test')
    parser.add_argument('--thr', type=int, default=25, help='Support threshold for contexts kept (default: 25)')
    args = parser.parse_args()
    
    print(f"Loading unigrams from {args.unigrams}...")
    all_words = load_unigrams(args.unigrams)
    print(f"Total distinct words in unigrams: {len(all_words):,}")
    
    print(f"Model: {args.model}")
    print(f"Support threshold: {args.thr}")
    print(f"Caps to test: {args.caps}")
    print()
    
    results = {}
    
    for cap in args.caps:
        print(f"=== vocab cap K={cap} ===")
        
        # Build vocab set (top K words)
        vocab = set(all_words[:cap])
        
        # Analyze model with this vocab
        metrics = analyze_cap(args.model, vocab, args.thr)
        
        model_distinct = metrics['distinct_words_in_model']
        words_beyond = metrics['distinct_words_outside_vocab']
        words_beyond_pct = words_beyond / model_distinct * 100 if model_distinct > 0 else 0
        
        contexts_kept = metrics['contexts_kept']
        top1_agree = metrics['top1_agree']
        top1_total = metrics['top1_total']
        top1_pct = top1_agree / top1_total * 100 if top1_total > 0 else 0
        
        emptied = metrics['emptied']
        emptied_pct = emptied / contexts_kept * 100 if contexts_kept > 0 else 0
        
        follower_dropped = metrics['follower_entries_dropped']
        follower_total = metrics['total_follower_entries']
        follower_dropped_pct = follower_dropped / follower_total * 100 if follower_total > 0 else 0
        
        full_mb = metrics['full_size_bytes'] / 1e6
        filtered_mb = metrics['filtered_size_bytes'] / 1e6
        size_reduction_pct = (metrics['full_size_bytes'] - metrics['filtered_size_bytes']) / metrics['full_size_bytes'] * 100 if metrics['full_size_bytes'] > 0 else 0
        
        print(f"model distinct words: {model_distinct:,}")
        print(f"words beyond cap: {words_beyond:,} ({words_beyond_pct:.1f}%)")
        print(f"contexts kept (thr={args.thr}): {contexts_kept:,}")
        print(f"top-1 agreement: {top1_agree:,}/{top1_total:,} = {top1_pct:.1f}%")
        print(f"emptied contexts: {emptied:,} ({emptied_pct:.1f}%)")
        print(f"follower entries dropped: {follower_dropped:,} ({follower_dropped_pct:.1f}%)")
        print(f"size: {full_mb:.1f} MB -> {filtered_mb:.1f} MB (-{size_reduction_pct:.1f}%)")
        print()
        
        results[cap] = {
            'model_distinct': model_distinct,
            'words_beyond': words_beyond,
            'words_beyond_pct': words_beyond_pct,
            'contexts_kept': contexts_kept,
            'top1_agree': top1_agree,
            'top1_total': top1_total,
            'top1_pct': top1_pct,
            'emptied': emptied,
            'emptied_pct': emptied_pct,
            'follower_dropped': follower_dropped,
            'follower_total': follower_total,
            'follower_dropped_pct': follower_dropped_pct,
            'full_mb': full_mb,
            'filtered_mb': filtered_mb,
            'size_reduction_pct': size_reduction_pct,
        }
    
    # Summary table
    print("=== SUMMARY ===")
    print(f"{'K':>8}  {'words beyond':>12}  {'ctx kept':>10}  {'top-1 agree':>10}  {'emptied':>8}  {'entries dropped':>14}  {'size MB':>12}  {'reduction':>9}")
    print(f"{'':>8}  {'(count, %)':>12}  {'':>10}  {'(%)':>10}  {'(count, %)':>8}  {'(count, %)':>14}  {'full->filt':>12}  {'(%)':>9}")
    for cap in args.caps:
        r = results[cap]
        print(f"{cap:8d}  {r['words_beyond']:>10,d} ({r['words_beyond_pct']:4.1f}%)  {r['contexts_kept']:>10,d}  {r['top1_pct']:>9.1f}%  {r['emptied']:>6,d} ({r['emptied_pct']:4.1f}%)  {r['follower_dropped']:>10,d} ({r['follower_dropped_pct']:4.1f}%)  {r['full_mb']:>5.1f}->{r['filtered_mb']:>5.1f}  {r['size_reduction_pct']:>7.1f}%")


if __name__ == '__main__':
    main()