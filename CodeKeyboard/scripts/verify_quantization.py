#!/usr/bin/env python3
"""
Verify quantization of n-gram follower scores to u8 preserves top-N ranking.
Two-pass streaming: pass 1 finds global score min/max, pass 2 quantizes and checks ranking.
"""

import json
import sys
import argparse
import math
from typing import Iterator, List, Tuple, Dict, Any
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
    Uses chunked reads + json.JSONDecoder.raw_decode for performance.
    """
    CHUNK_SIZE = 8 * 1024 * 1024  # 8MB chunks
    decoder = json.JSONDecoder()

    with open(filepath, 'rb') as f:
        chunk = f.read(CHUNK_SIZE)
        if not chunk:
            return
        buf = chunk.decode('utf-8')
        pos = 0
        buf_len = len(buf)

        def ensure_buffer(min_pos: int) -> None:
            nonlocal buf, pos, buf_len
            if min_pos < buf_len:
                return
            more = f.read(CHUNK_SIZE)
            if not more:
                return
            buf += more.decode('utf-8')
            buf_len = len(buf)

        eof = False

        def extend_buffer() -> bool:
            """Read the next chunk into buf. Returns True if more data was read."""
            nonlocal buf, buf_len, eof
            if eof:
                return False
            more = f.read(CHUNK_SIZE)
            if not more:
                eof = True
                return False
            buf += more.decode('utf-8')
            buf_len = len(buf)
            return True

        def parse_token(p: int, what: str, ctx_hint: str = '') -> Tuple[Any, int]:
            """raw_decode at p, extending the buffer across chunk boundaries."""
            while True:
                try:
                    return decoder.raw_decode(buf, p)
                except json.JSONDecodeError:
                    if not extend_buffer():
                        hint = f" for key {ctx_hint!r}" if ctx_hint else ''
                        raise ValueError(f"Failed to parse {what}{hint} at position {p}: unexpected EOF")

        def skip_whitespace_and_commas(p: int) -> int:
            while True:
                ensure_buffer(p)
                if p >= buf_len:
                    return p
                ch = buf[p]
                if ch not in ' \t\n\r,':
                    return p
                p += 1

        pos = skip_whitespace_and_commas(pos)
        ensure_buffer(pos)
        if pos >= buf_len or buf[pos] != '{':
            got = buf[pos] if pos < buf_len else 'EOF'
            raise ValueError(f"Expected '{{' at start of file, got {got!r}")
        pos += 1

        prev_ctx = None

        while True:
            pos = skip_whitespace_and_commas(pos)
            ensure_buffer(pos)
            if pos >= buf_len:
                return

            if buf[pos] == '}':
                return

            ctx, pos = parse_token(pos, 'key')

            if not isinstance(ctx, str):
                raise ValueError(f"Expected string key, got {type(ctx).__name__}")

            if prev_ctx is not None and ctx < prev_ctx:
                raise ValueError(f"Keys not sorted: {prev_ctx!r} > {ctx!r}")
            prev_ctx = ctx

            pos = skip_whitespace_and_commas(pos)
            ensure_buffer(pos)
            if pos >= buf_len or buf[pos] != ':':
                got = buf[pos] if pos < buf_len else 'EOF'
                raise ValueError(f"Expected ':' after key, got {got!r}")
            pos += 1

            pos = skip_whitespace_and_commas(pos)

            value, pos = parse_token(pos, 'value', ctx)

            if not isinstance(value, dict):
                raise ValueError(f"Expected object value for key {ctx!r}, got {type(value).__name__}")

            followers = value.get('followers')
            support = value.get('support')
            if followers is None or support is None:
                raise ValueError(f"Value for key {ctx!r} missing 'followers' or 'support'")

            yield NgramEntry(ctx=ctx, followers=followers, support=support)


def find_score_range(model_path: str, thr: int = 0, use_log10: bool = False) -> Tuple[float, float]:
    """
    Pass 1: stream the model and find global min and max of all follower scores.
    If use_log10=True, operates on log10(score) instead of raw score.
    Returns (min_score, max_score) in the appropriate space.
    """
    min_score = float('inf')
    max_score = float('-inf')
    total_followers = 0

    for entry in stream_ngrams(model_path):
        if entry.support < thr:
            continue
        for follower in entry.followers:
            score = follower[1]
            if use_log10:
                if score <= 0:
                    raise ValueError(f"log10 mode requires all scores > 0, found score={score} in context={entry.ctx!r}")
                score = math.log10(score)
            if score < min_score:
                min_score = score
            if score > max_score:
                max_score = score
            total_followers += 1

    if total_followers == 0:
        raise ValueError("No followers found in model (check --thr)")

    return min_score, max_score


def quantize_score(score: float, min_score: float, max_score: float, use_log10: bool = False) -> int:
    """Quantize a score to u8 [0, 255] using linear or log10 mapping."""
    if max_score == min_score:
        return 255
    if use_log10:
        if score <= 0:
            raise ValueError(f"log10 mode requires score > 0, got {score}")
        score = math.log10(score)
    byte_val = round((score - min_score) / (max_score - min_score) * 255)
    # Clamp to valid range
    return max(0, min(255, byte_val))


def decode_score(byte_val: int, min_score: float, max_score: float, use_log10: bool = False) -> float:
    """Decode a u8 byte back to a score."""
    if max_score == min_score:
        return 10.0 ** max_score if use_log10 else max_score
    decoded = min_score + (byte_val / 255.0) * (max_score - min_score)
    return 10.0 ** decoded if use_log10 else decoded


def check_context_ranking(entry: NgramEntry, min_score: float, max_score: float, caps: List[int], use_log10: bool = False) -> Dict[int, Dict[str, Any]]:
    """
    For a single context, quantize all scores, decode, re-sort, and compare top-N.
    Returns dict with per-cap agreement and flip info.
    """
    original_followers = entry.followers
    if not original_followers:
        return {cap: {'agree': True, 'flip': False, 'tie': False} for cap in caps}

    # Quantize and decode each follower, preserving original index for stable sort
    quantized = []
    for idx, (word, score) in enumerate(original_followers):
        byte_val = quantize_score(score, min_score, max_score, use_log10)
        decoded = decode_score(byte_val, min_score, max_score, use_log10)
        quantized.append((word, decoded, byte_val, idx))

    # Check for ties (same byte value for different followers)
    byte_vals = [q[2] for q in quantized]
    has_tie = len(byte_vals) != len(set(byte_vals))

    # Stable sort by decoded score descending, then by original index
    quantized.sort(key=lambda x: (-x[1], x[3]))

    # Extract re-sorted word lists
    original_words = [f[0] for f in original_followers]
    quantized_words = [q[0] for q in quantized]

    result = {}
    for cap in caps:
        orig_top = tuple(original_words[:cap])
        quant_top = tuple(quantized_words[:cap])
        agree = (orig_top == quant_top)
        flip = not agree
        result[cap] = {
            'agree': agree,
            'flip': flip,
            'tie': has_tie,
            'orig_top': orig_top,
            'quant_top': quant_top,
        }

    return result


def verify_quantization(model_path: str, caps: List[int], thr: int = 0, use_log10: bool = False) -> Dict[str, Any]:
    """
    Pass 2: stream the model, quantize each context, check ranking preservation.
    Returns aggregated metrics.
    """
    min_score, max_score = find_score_range(model_path, thr, use_log10)

    # Initialize accumulators
    total_contexts = 0
    contexts_with_flips = {cap: 0 for cap in caps}
    contexts_with_ties = 0
    flip_examples = {cap: [] for cap in caps}

    for entry in stream_ngrams(model_path):
        if entry.support < thr:
            continue

        total_contexts += 1
        result = check_context_ranking(entry, min_score, max_score, caps, use_log10)

        # Check ties once per context (any cap)
        any_tie = any(result[cap]['tie'] for cap in caps)
        if any_tie:
            contexts_with_ties += 1

        for cap in caps:
            if result[cap]['flip']:
                contexts_with_flips[cap] += 1
                if len(flip_examples[cap]) < 10:
                    flip_examples[cap].append({
                        'ctx': entry.ctx,
                        'orig_top': result[cap]['orig_top'],
                        'quant_top': result[cap]['quant_top'],
                    })

    # Compute agreement rates
    agreement_rates = {}
    for cap in caps:
        if total_contexts > 0:
            agreement_rates[cap] = (total_contexts - contexts_with_flips[cap]) / total_contexts * 100
        else:
            agreement_rates[cap] = 100.0

    return {
        'min_score': min_score,
        'max_score': max_score,
        'total_contexts': total_contexts,
        'agreement_rates': agreement_rates,
        'contexts_with_flips': contexts_with_flips,
        'contexts_with_ties': contexts_with_ties,
        'flip_examples': flip_examples,
    }


def format_report(metrics: Dict[str, Any], caps: List[int], use_log10: bool = False) -> str:
    """Format the verification report as a string."""
    lines = []
    mode_label = "LOG10" if use_log10 else "LINEAR"
    lines.append(f"=== Quantization Verification Report ({mode_label} mode) ===")
    if use_log10:
        lines.append(f"log10 score range: [{metrics['min_score']:.6f}, {metrics['max_score']:.6f}]")
    else:
        lines.append(f"Score range: [{metrics['min_score']:.6f}, {metrics['max_score']:.6f}]")
    lines.append(f"Total contexts evaluated: {metrics['total_contexts']:,}")
    lines.append(f"Contexts with quantization ties: {metrics['contexts_with_ties']:,}")
    lines.append("")

    for cap in caps:
        rate = metrics['agreement_rates'][cap]
        flips = metrics['contexts_with_flips'][cap]
        lines.append(f"--- Cap {cap} ---")
        lines.append(f"  Top-{cap} agreement: {rate:.2f}% ({metrics['total_contexts'] - flips:,}/{metrics['total_contexts']:,})")
        lines.append(f"  Contexts with ranking flips: {flips:,}")

        examples = metrics['flip_examples'][cap]
        if examples:
            lines.append(f"  Example flips (up to 10):")
            for ex in examples:
                lines.append(f"    ctx={ex['ctx']!r}")
                lines.append(f"      original:  {list(ex['orig_top'])}")
                lines.append(f"      quantized: {list(ex['quant_top'])}")
        else:
            lines.append(f"  No ranking flips detected.")
        lines.append("")

    return "\n".join(lines)


def generate_synthetic_model(output_path: str, num_contexts: int = 50):
    """Generate a synthetic model JSON for self-testing."""
    import random
    random.seed(42)

    # Realistic log10 score range: -8.0 to -0.1
    # Generate contexts with 3-8 followers each
    words = [f"word{i}" for i in range(200)]
    # Use zero-padded context keys for lexicographic sorting
    contexts = [f"ctx{i:04d}" for i in range(num_contexts)]

    model = {}
    for ctx in contexts:
        num_followers = random.randint(3, 8)
        # Pick random words
        selected_words = random.sample(words, num_followers)
        # Generate scores in log10 range, some near-ties
        scores = []
        for i in range(num_followers):
            # Most scores spread out, but occasionally create near-ties
            if i > 0 and random.random() < 0.15:
                # Near-tie: within 0.001 of previous
                scores.append(scores[-1] - random.uniform(0.0001, 0.001))
            else:
                scores.append(random.uniform(-8.0, -0.1))
        scores.sort(reverse=True)
        followers = [[w, s] for w, s in zip(selected_words, scores)]
        model[ctx] = {"followers": followers, "support": random.randint(1, 100)}

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(model, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Generated synthetic model: {output_path} ({num_contexts} contexts)")


def main():
    parser = argparse.ArgumentParser(description="Verify u8 quantization preserves top-N ranking in n-gram model")
    parser.add_argument('--model', help='Scored trigram model JSON file (required unless --self-test)')
    parser.add_argument('--report', help='Optional output report file path')
    parser.add_argument('--caps', type=int, nargs='+', default=[1, 5, 10], help='Top-N caps to verify (default: 1 5 10)')
    parser.add_argument('--thr', type=int, default=0, help='Support threshold (default: 0 = all contexts)')
    parser.add_argument('--self-test', action='store_true', help='Generate synthetic model and run verification on it')
    parser.add_argument('--log10', action='store_true', help='Use log10(score) quantization instead of linear')
    args = parser.parse_args()

    if not args.self_test and not args.model:
        parser.error("--model is required unless --self-test is used")

    if args.self_test:
        # Generate synthetic model in /tmp
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
            synth_path = f.name
        generate_synthetic_model(synth_path, num_contexts=50)
        args.model = synth_path

    print(f"Model: {args.model}")
    print(f"Caps: {args.caps}")
    print(f"Support threshold: {args.thr}")
    print(f"Mode: {'LOG10' if args.log10 else 'LINEAR'}")
    print()

    # Run verification
    metrics = verify_quantization(args.model, args.caps, args.thr, args.log10)

    # Format and output report
    report = format_report(metrics, args.caps, args.log10)
    print(report)

    if args.report:
        with open(args.report, 'w', encoding='utf-8') as f:
            f.write(report)
        print(f"Report written to {args.report}")


if __name__ == '__main__':
    main()