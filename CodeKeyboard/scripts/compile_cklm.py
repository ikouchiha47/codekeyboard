#!/usr/bin/env python3
"""
CKLM v1 binary language-pack compiler.
Converts scored trigram/bigram/unigram inputs to mmap-friendly binary format.
Two-pass streaming to keep memory low.
"""

import json
import sys
import argparse
import struct
import os
import tempfile
import math
from typing import Iterator, List, Tuple, Dict, Set, Any, IO
from dataclasses import dataclass, field


@dataclass
class NgramEntry:
    ctx: str
    followers: List[List[Any]]  # [[word, score], ...]
    support: int


def stream_trigrams(filepath: str) -> Iterator[NgramEntry]:
    """
    Stream a trigram JSON file, yielding (ctx, followers, support) tuples.
    Assumes top-level keys are lexicographically sorted.
    Trigram JSON shape: {"ctx": {"followers": [[word, score], ...], "support": N}}
    Uses chunked reads + json.JSONDecoder.raw_decode for performance.
    """
    CHUNK_SIZE = 8 * 1024 * 1024  # 8MB chunks
    decoder = json.JSONDecoder()

    with open(filepath, 'rb') as f:
        # Read first chunk
        chunk = f.read(CHUNK_SIZE)
        if not chunk:
            return
        buf = chunk.decode('utf-8')
        pos = 0
        buf_len = len(buf)

        def ensure_buffer(min_pos: int) -> None:
            """Ensure buffer has data up to min_pos by reading more if needed."""
            nonlocal buf, pos, buf_len
            if min_pos < buf_len:
                return
            # Need more data - read next chunk
            more = f.read(CHUNK_SIZE)
            if not more:
                return  # EOF
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
            """Skip whitespace and commas, reading more data if needed."""
            while True:
                ensure_buffer(p)
                if p >= buf_len:
                    return p  # EOF
                ch = buf[p]
                if ch not in ' \t\n\r,':
                    return p
                p += 1

        # Expect opening brace
        pos = skip_whitespace_and_commas(pos)
        ensure_buffer(pos)
        if pos >= buf_len or buf[pos] != '{':
            got = buf[pos] if pos < buf_len else 'EOF'
            raise ValueError(f"Expected '{{' at start of file, got {got!r}")
        pos += 1

        prev_ctx = None

        while True:
            # Skip whitespace and commas before key
            pos = skip_whitespace_and_commas(pos)
            ensure_buffer(pos)
            if pos >= buf_len:
                return  # EOF

            if buf[pos] == '}':
                return  # End of object

            # Parse key string using raw_decode
            ctx, pos = parse_token(pos, 'key')

            if not isinstance(ctx, str):
                raise ValueError(f"Expected string key, got {type(ctx).__name__}")

            if prev_ctx is not None and ctx < prev_ctx:
                raise ValueError(f"Keys not sorted: {prev_ctx!r} > {ctx!r}")
            prev_ctx = ctx

            # Skip whitespace and colon
            pos = skip_whitespace_and_commas(pos)
            ensure_buffer(pos)
            if pos >= buf_len or buf[pos] != ':':
                got = buf[pos] if pos < buf_len else 'EOF'
                raise ValueError(f"Expected ':' after key, got {got!r}")
            pos += 1

            # Skip whitespace
            pos = skip_whitespace_and_commas(pos)

            # Parse value (object with followers and support) using raw_decode
            value, pos = parse_token(pos, 'value', ctx)

            if not isinstance(value, dict):
                raise ValueError(f"Expected object value for key {ctx!r}, got {type(value).__name__}")

            followers = value.get('followers')
            support = value.get('support')
            if followers is None or support is None:
                raise ValueError(f"Value for key {ctx!r} missing 'followers' or 'support'")

            yield NgramEntry(ctx=ctx, followers=followers, support=support)


def stream_bigrams(filepath: str) -> Iterator[Tuple[str, List[List[Any]]]]:
    """
    Stream a bigram JSON file, yielding (ctx, followers) tuples.
    Assumes top-level keys are lexicographically sorted.
    Bigram JSON shape: {"ctx": [[word, score], ...]}  (flat, no nested object, no support)
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

            try:
                ctx, pos = decoder.raw_decode(buf, pos)
            except json.JSONDecodeError:
                ensure_buffer(pos + 1)
                try:
                    ctx, pos = decoder.raw_decode(buf, pos)
                except json.JSONDecodeError as e:
                    raise ValueError(f"Failed to parse key at position {pos}: {e}")

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

            followers, pos = parse_token(pos, 'value', ctx)

            if not isinstance(followers, list):
                raise ValueError(f"Expected array value for key {ctx!r}, got {type(followers).__name__}")

            yield ctx, followers


def stream_unigrams(filepath: str) -> Iterator[Tuple[str, int]]:
    """
    Stream a unigram TSV file, yielding (word, count) tuples.
    Format: word<TAB>count, sorted descending by count.
    """
    with open(filepath, 'r', encoding='utf-8') as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) != 2:
                raise ValueError(f"Unigram file line {line_num}: expected 'word\\tcount', got {line!r}")
            word, count_str = parts
            try:
                count = int(count_str)
            except ValueError:
                raise ValueError(f"Unigram file line {line_num}: invalid count {count_str!r}")
            if count <= 0:
                raise ValueError(f"Unigram file line {line_num}: count must be > 0, got {count}")
            yield word, count


@dataclass
class NodeMeta:
    """In-memory metadata for a trie node during compilation."""
    children_offset: int = 0
    child_count: int = 0
    followers_offset: int = 0
    follower_count: int = 0
    phrase_score: int = 0
    flags: int = 0
    support: int = 0
    # Temp: children being built for this node (list of (word_id, child_node_index))
    children: List[Tuple[int, int]] = field(default_factory=list)
    # Temp: followers being built for this node (list of (word_id, quantized_score))
    followers: List[Tuple[int, int]] = field(default_factory=list)


def quantize_log10(score: float, log10_min: float, log10_max: float) -> int:
    """Quantize a score to u8 [0, 255] using log10 mapping."""
    if score <= 0:
        raise ValueError(f"Score must be > 0 for log10 quantization, got {score}")
    log10_score = math.log10(score)
    if log10_max == log10_min:
        return 255
    byte_val = round((log10_score - log10_min) / (log10_max - log10_min) * 255)
    return max(0, min(255, byte_val))


def pass1_collect(
    trigram_path: str,
    bigram_path: str | None,
    unigram_path: str | None,
    thr: int,
    max_vocab: int = 65535,
    max_unigram_followers: int = 255,
) -> Tuple[List[str], Dict[str, int], float, float, List[Tuple[str, int]], Dict[str, int]]:
    """
    Pass 1: collect vocab, select top-max_vocab words by unigram count, and
    compute the global log10 score range.

    Range is computed over the trigram tier (thr-filtered) and the top-N
    unigram tier only. Bigram scores are EXCLUDED from the range: the bigram
    tail below the floor clamps to byte 0 (secondary tier).

    Returns (vocab_list, word_to_id, log10_min, log10_max, unigram_top, unigram_counts_selected).
    vocab_list is sorted lexicographically (word IDs = position in it).
    unigram_top is the top-max_unigram_followers (word, count) pairs.
    unigram_counts_selected is the unigram count for each word in the selected vocab.
    """
    vocab = set()
    log10_min = float('inf')
    log10_max = float('-inf')
    total_followers = 0

    # Trigrams: vocab + range (primary tier)
    for entry in stream_trigrams(trigram_path):
        if entry.support < thr:
            continue
        # Context words
        for word in entry.ctx.split():
            vocab.add(word)
        # Follower words and scores
        for word, score in entry.followers:
            vocab.add(word)
            if score <= 0:
                raise ValueError(f"Trigram follower score must be > 0, got {score} for word {word!r}")
            log10_score = math.log10(score)
            if log10_score < log10_min:
                log10_min = log10_score
            if log10_score > log10_max:
                log10_max = log10_score
            total_followers += 1

    # Bigrams: vocab only (range excluded — tail clamps to byte 0)
    if bigram_path:
        for ctx, followers in stream_bigrams(bigram_path):
            # Context word (single word)
            vocab.add(ctx)
            # Follower words
            for word, score in followers:
                vocab.add(word)
                if score <= 0:
                    raise ValueError(f"Bigram follower score must be > 0, got {score} for word {word!r}")
                total_followers += 1

    # Unigrams: counts for vocab ranking + top-N for range and root followers
    unigram_counts: Dict[str, int] = {}
    unigram_top: List[Tuple[str, int]] = []
    if unigram_path:
        max_count = None
        for word, count in stream_unigrams(unigram_path):
            unigram_counts[word] = count
            vocab.add(word)
            if max_count is None:
                max_count = count
            if len(unigram_top) < max_unigram_followers:
                unigram_top.append((word, count))
                # Score = count / max_count (normalized to [0, 1], top-1 = 1.0)
                score = count / max_count
                if score <= 0:
                    raise ValueError(f"Unigram score must be > 0, got {score} for word {word!r}")
                log10_score = math.log10(score)
                if log10_score < log10_min:
                    log10_min = log10_score
                if log10_score > log10_max:
                    log10_max = log10_score
                total_followers += 1

    if total_followers == 0:
        raise ValueError("No followers found (check --thr)")

    # Select vocab: rank by (unigram count desc, word asc), take top max_vocab
    ranked = sorted(vocab, key=lambda w: (-unigram_counts.get(w, 0), w))
    selected = ranked[:max_vocab]
    if len(vocab) > max_vocab:
        print(f"  Vocab capped: {len(vocab):,} -> {len(selected):,} (top {max_vocab:,} by unigram count)")
    vocab_list = sorted(selected)
    word_to_id = {w: i for i, w in enumerate(vocab_list)}
    
    # Build unigram counts for selected vocab only (for char-trie terminal freq)
    unigram_counts_selected = {w: unigram_counts.get(w, 0) for w in vocab_list}
    
    return vocab_list, word_to_id, log10_min, log10_max, unigram_top, unigram_counts_selected


def load_phrases(phrases_path: str, word_to_id: Dict[str, int]) -> Dict[str, float]:
    """
    Load phrases file: each line is "phrase words...\tphrase_score".
    Returns dict mapping phrase_text -> quantized phrase_score (placeholder, will re-quantize in pass2).
    """
    phrase_scores = {}
    with open(phrases_path, 'r', encoding='utf-8') as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) != 2:
                raise ValueError(f"Phrases file line {line_num}: expected 'phrase\\tscore', got {line!r}")
            phrase_text, score_str = parts
            try:
                score = float(score_str)
            except ValueError:
                raise ValueError(f"Phrases file line {line_num}: invalid score {score_str!r}")
            words = phrase_text.split()
            for w in words:
                if w not in word_to_id:
                    raise ValueError(f"Phrases file line {line_num}: word {w!r} not in vocab")
            phrase_scores[phrase_text] = score  # Store raw score, quantize later
    return phrase_scores


def pass2_build(
    trigram_path: str,
    bigram_path: str | None,
    thr: int,
    word_to_id: Dict[str, int],
    log10_min: float,
    log10_max: float,
    phrase_scores: Dict[str, float],
    children_temp: IO[bytes],
    followers_temp: IO[bytes],
    unigram_top: List[Tuple[str, int]],
    bigram_support_path: str | None = None,
) -> Tuple[List[NodeMeta], int, int, int]:
    """
    Pass 2: build three-tier trie, write children/followers to temp files.
    Returns (nodes_metadata, node_count, follower_count, phrase_count).
    Out-of-vocab contexts/followers are pruned (vocab cap).
    """
    nodes = [NodeMeta()]  # node 0 = root
    total_followers = 0
    phrase_count = 0

    def finalize_node(node_idx: int):
        """Write node's children and followers to temp files, update metadata."""
        nonlocal total_followers, phrase_count
        node = nodes[node_idx]
        # Write children (sorted by word_id)
        if node.children:
            node.children.sort(key=lambda x: x[0])
            node.children_offset = children_temp.tell()
            node.child_count = len(node.children)
            for word_id, child_idx in node.children:
                children_temp.write(struct.pack('<HI', word_id, child_idx))
        # Write followers (already in score-descending order from input)
        if node.followers:
            node.followers_offset = followers_temp.tell()
            node.follower_count = len(node.followers)
            total_followers += len(node.followers)
            for word_id, qscore in node.followers:
                followers_temp.write(struct.pack('<HB', word_id, qscore))

    # Fast child lookup: (parent_idx, word_id) -> child_idx
    child_map: Dict[Tuple[int, int], int] = {}

    # --- Tier 1: Unigrams at root (top-N by count) ---
    if unigram_top:
        max_count = unigram_top[0][1]
        root_followers = []
        for word, count in unigram_top:
            word_id = word_to_id.get(word)
            if word_id is None:
                continue  # out-of-vocab (shouldn't happen for top-N)
            score = count / max_count
            qscore = quantize_log10(score, log10_min, log10_max)
            root_followers.append((word_id, qscore))
        # Sort by score descending (already in count-descending order from TSV, but ensure)
        root_followers.sort(key=lambda x: x[1], reverse=True)
        nodes[0].followers = root_followers
        # Root support = total unigram count (sum of all unigram counts from unigram_top)
        nodes[0].support = sum(count for _, count in unigram_top)

    # --- Tier 2: Bigrams at depth-1 ---
    if bigram_path:
        # Load bigram support map ({"w1": N}) if provided — real counts, not normalized scores
        bigram_support: Dict[str, int] = {}
        if bigram_support_path:
            with open(bigram_support_path, 'r', encoding='utf-8') as f:
                bigram_support = json.load(f)
        for ctx, followers in stream_bigrams(bigram_path):
            # Context is a single word
            ctx_word_id = word_to_id.get(ctx)
            if ctx_word_id is None:
                continue  # out-of-vocab context
            # Find or create depth-1 node under root (dict lookup, not linear scan)
            key = (0, ctx_word_id)
            child_idx = child_map.get(key)
            if child_idx is None:
                child_idx = len(nodes)
                nodes.append(NodeMeta())
                nodes[0].children.append((ctx_word_id, child_idx))
                child_map[key] = child_idx
            # Bigram support = real observed count from bigrams_support.json (fallback: sum of follower scores)
            nodes[child_idx].support = int(bigram_support.get(ctx, sum(c for _, c in followers)))
            # Attach followers to this depth-1 node
            for word, score in followers:
                word_id = word_to_id.get(word)
                if word_id is None:
                    continue  # out-of-vocab follower
                qscore = quantize_log10(score, log10_min, log10_max)
                nodes[child_idx].followers.append((word_id, qscore))

    # --- Tier 3: Trigrams at depth-2 ---
    for entry in stream_trigrams(trigram_path):
        if entry.support < thr:
            continue

        # Split context into words, map to IDs (skip if any out-of-vocab)
        ctx_words = entry.ctx.split()
        ctx_ids = []
        skip = False
        for w in ctx_words:
            word_id = word_to_id.get(w)
            if word_id is None:
                skip = True
                break
            ctx_ids.append(word_id)
        if skip:
            continue

        # Traverse from root, finding or creating nodes
        node_idx = 0
        for word_id in ctx_ids:
            key = (node_idx, word_id)
            child_idx = child_map.get(key)
            if child_idx is None:
                child_idx = len(nodes)
                nodes.append(NodeMeta())
                nodes[node_idx].children.append((word_id, child_idx))
                child_map[key] = child_idx
            node_idx = child_idx

        # Set support for this context node (from entry.support)
        nodes[node_idx].support = entry.support

        # Attach followers to the final node
        for word, score in entry.followers:
            word_id = word_to_id.get(word)
            if word_id is None:
                continue  # out-of-vocab follower
            qscore = quantize_log10(score, log10_min, log10_max)
            nodes[node_idx].followers.append((word_id, qscore))

    # Finalize all nodes (write to temp files)
    for node_idx in range(len(nodes)):
        finalize_node(node_idx)

    # Apply phrase scores (re-quantize with log10)
    for phrase_text, raw_score in phrase_scores.items():
        words = phrase_text.split()
        node_idx = 0
        ok = True
        for w in words:
            word_id = word_to_id.get(w)
            if word_id is None:
                print(f"  Warning: skipping phrase {phrase_text!r} (word {w!r} out of vocab)")
                ok = False
                break
            # Find child
            found = False
            for child_word_id, child_idx in nodes[node_idx].children:
                if child_word_id == word_id:
                    node_idx = child_idx
                    found = True
                    break
            if not found:
                print(f"  Warning: skipping phrase {phrase_text!r} (path not in trie)")
                ok = False
                break
        if not ok:
            continue
        qscore = quantize_log10(raw_score, log10_min, log10_max)
        if nodes[node_idx].phrase_score == 0:
            phrase_count += 1
        nodes[node_idx].phrase_score = qscore

    return nodes, len(nodes), total_followers, phrase_count


def pass3_build_char_trie(
    vocab: List[str],
    unigram_counts: Dict[str, int],
    log10_min: float,
    log10_max: float,
) -> Tuple[List[Tuple[int, int, int, int]], List[Tuple[int, int]], int]:
    """
    Pass 3: Build character trie (WORD tier) over the full vocab.
    
    Returns (char_nodes, char_children, char_trie_nodes_count).
    char_nodes: list of (char, flags, children_offset, freq) for each node
    char_children: list of (char, child_index) for all children, in node order
    char_trie_nodes_count: total number of nodes
    
    Node layout (12 bytes each):
    - char: u32 (Unicode code point; 0 = root)
    - flags: u8 (bit0 = isTerminal, bit1 = hasChildren)
    - children_offset: u32 (byte offset into char_children data, relative to char-trie section start)
    - freq: u8 (terminal = log10-encoded unigram score; 0 otherwise)
    - 2 reserved bytes
    
    Children data layout (per node, in node order):
    - childCount: u8
    - childCount entries of: char u32 + child_index u32 (8 bytes each)
    Children sorted by char for deterministic output.
    """
    # Build trie structure in memory first
    # Each node: {'char': int, 'children': dict[char->node_idx], 'terminal_word': str|None, 'word_id': int|None}
    nodes = [{'char': 0, 'children': {}, 'terminal_word': None, 'word_id': None}]  # root at index 0
    
    # Insert all vocab words
    for word_id, word in enumerate(vocab):
        node_idx = 0
        for ch in word:
            cp = ord(ch)
            if cp not in nodes[node_idx]['children']:
                new_idx = len(nodes)
                nodes[node_idx]['children'][cp] = new_idx
                nodes.append({'char': cp, 'children': {}, 'terminal_word': None, 'word_id': None})
            node_idx = nodes[node_idx]['children'][cp]
        # Mark terminal
        nodes[node_idx]['terminal_word'] = word
        nodes[node_idx]['word_id'] = word_id
    
    # Compute unigram scores for terminal nodes
    # Score = count / max_count (same as root followers), log10-encoded over header range
    max_count = max(unigram_counts.values()) if unigram_counts else 1
    
    def quantize_unigram_score(count: int) -> int:
        if count <= 0:
            return 0
        score = count / max_count
        if score <= 0:
            return 0
        log10_score = math.log10(score)
        if log10_max == log10_min:
            return 255
        byte_val = round((log10_score - log10_min) / (log10_max - log10_min) * 255)
        return max(0, min(255, byte_val))
    
    # Now do a DFS to assign node indices in pre-order (root first, then children sorted by char)
    # This gives us the final node order for the flat array
    final_nodes = []
    final_children = []  # list of (char, child_index) per node, in node order
    
    def dfs(node_idx: int) -> int:
        """Returns the assigned index in final_nodes."""
        node = nodes[node_idx]
        my_index = len(final_nodes)
        
        # Build this node FIRST (pre-order)
        flags = 0
        if node['terminal_word'] is not None:
            flags |= 1  # bit0 = isTerminal
        # We'll determine hasChildren after processing children
        
        # Children offset will be filled in later (relative to char-trie section start)
        # For now, store the child entries
        freq = 0
        if node['terminal_word'] is not None:
            count = unigram_counts.get(node['terminal_word'], 0)
            freq = quantize_unigram_score(count)
        
        # Placeholder for children - will fill in after recursive calls
        final_nodes.append({
            'char': node['char'],
            'flags': flags,
            'children': [],  # Will be filled below
            'freq': freq,
        })
        
        # Process children in sorted order by char
        sorted_children = sorted(node['children'].items())
        child_entries = []
        for ch, child_idx in sorted_children:
            child_final_idx = dfs(child_idx)
            child_entries.append((ch, child_final_idx))
        
        # Update the node with children and hasChildren flag
        final_nodes[my_index]['children'] = child_entries
        if child_entries:
            final_nodes[my_index]['flags'] |= 2  # bit1 = hasChildren
        
        return my_index
    
    dfs(0)  # Start from root
    
    # Now compute children offsets and flatten
    # Children data starts after the node array
    node_count = len(final_nodes)
    node_array_size = node_count * 12  # 12 bytes per node
    
    char_nodes = []
    char_children = []
    children_offset = node_array_size  # Start of children data
    
    for node in final_nodes:
        child_count = len(node['children'])
        char_nodes.append((node['char'], node['flags'], children_offset, node['freq']))
        
        if child_count > 0:
            char_children.append((child_count,))  # marker for childCount
            for ch, child_idx in node['children']:
                char_children.append((ch, child_idx))
            # Update children_offset for next node
            children_offset += 1 + child_count * 8  # 1 byte childCount + 8 bytes per child
    
    return char_nodes, char_children, node_count


def write_cklm(
    output_path: str,
    vocab: List[str],
    nodes: List[NodeMeta],
    children_temp: IO[bytes],
    followers_temp: IO[bytes],
    log10_min: float,
    log10_max: float,
    phrase_count: int,
    char_nodes: List[Tuple[int, int, int, int]],
    char_children: List[Tuple],
    char_trie_node_count: int,
):
    """Write the final CKLM binary file."""
    vocab_count = len(vocab)
    node_count = len(nodes)
    follower_count = sum(n.follower_count for n in nodes)

    # Build vocab section
    # Offsets relative to vocab section start
    vocab_offsets = []
    vocab_blob = bytearray()
    for word in vocab:
        vocab_offsets.append(len(vocab_blob))
        vocab_blob.extend(word.encode('utf-8'))
        vocab_blob.append(0)  # NUL terminator

    # Build char-trie section in memory
    # Node array: 12 bytes each (char u32, flags u8, children_offset u32, freq u8, 2 reserved)
    char_node_array = bytearray()
    for char, flags, children_offset, freq in char_nodes:
        char_node_array.extend(struct.pack('<IBIBBB', char, flags, children_offset, freq, 0, 0))
    
    # Children data
    char_children_data = bytearray()
    for entry in char_children:
        if len(entry) == 1:
            # childCount marker
            char_children_data.append(entry[0])
        else:
            # (char, child_index)
            char_children_data.extend(struct.pack('<II', entry[0], entry[1]))
    
    char_trie_section_size = len(char_node_array) + len(char_children_data)

    # Calculate section offsets
    header_size = 96
    vocab_section_size = 4 * vocab_count + len(vocab_blob)
    char_trie_offset = header_size + vocab_section_size
    nodes_section_size = 20 * node_count
    offset_nodes = char_trie_offset + char_trie_section_size
    offset_children = offset_nodes + nodes_section_size
    offset_followers = offset_children + children_temp.tell()
    file_size = offset_followers + followers_temp.tell()

    with open(output_path, 'wb') as f:
        # Header (updated format: char_trie_offset u64 at byte 72, char_trie_nodes u32 at byte 80, 12 bytes reserved)
        f.write(struct.pack(
            '<4sHBBffIIIIQQQQQQI12s',
            b'CKLM',           # magic
            1,                # version
            2,                # word_id_bytes (u16)
            0,                # reserved
            log10_min,        # score_min (log10)
            log10_max,        # score_max (log10)
            vocab_count,      # vocab_count
            node_count,       # node_count
            follower_count,   # follower_count
            phrase_count,     # phrase_count
            header_size,      # offset_vocab (always 96)
            offset_nodes,     # offset_nodes
            offset_children,  # offset_children
            offset_followers, # offset_followers
            file_size,        # file_size
            char_trie_offset, # char_trie_offset (NEW)
            char_trie_node_count, # char_trie_nodes (NEW)
            b'\x00' * 12      # reserved (12 bytes, was 24)
        ))

        # Vocab section: offsets then blob
        for off in vocab_offsets:
            f.write(struct.pack('<I', off))
        f.write(vocab_blob)

        # Char-trie section (WORD tier)
        f.write(char_node_array)
        f.write(char_children_data)

        # Nodes section (context trie) — 20 bytes each: children_offset u32, child_count u16,
        # followers_offset u32, follower_count u8, phrase_score u8, flags u8, support u32, 3 reserved
        for node in nodes:
            f.write(struct.pack(
                '<IHIBBBIBBB',
                int(node.children_offset),
                int(node.child_count),
                int(node.followers_offset),
                int(node.follower_count),
                int(node.phrase_score),
                int(node.flags),
                int(node.support),
                0, 0, 0  # reserved
            ))

        # Children section
        children_temp.seek(0)
        f.write(children_temp.read())

        # Followers section
        followers_temp.seek(0)
        f.write(followers_temp.read())


def verify_cklm(output_path: str, vocab: List[str], nodes: List[NodeMeta], log10_min: float, log10_max: float, char_trie_node_count: int = 0):
    """Verify the written CKLM file by reading it back."""
    with open(output_path, 'rb') as f:
        # Read header (new format with char_trie_offset and char_trie_nodes)
        header = f.read(96)
        magic, version, word_id_bytes, reserved, score_min, score_max, vocab_count, node_count, follower_count, phrase_count, offset_vocab, offset_nodes, offset_children, offset_followers, file_size, char_trie_offset, char_trie_nodes, reserved2 = struct.unpack('<4sHBBffIIIIQQQQQQI12s', header)

        assert magic == b'CKLM', f"Bad magic: {magic}"
        assert version == 1, f"Bad version: {version}"
        assert word_id_bytes == 2, f"Bad word_id_bytes: {word_id_bytes}"
        assert abs(score_min - log10_min) < 1e-5, f"score_min mismatch: {score_min} vs {log10_min}"
        assert abs(score_max - log10_max) < 1e-5, f"score_max mismatch: {score_max} vs {log10_max}"
        assert vocab_count == len(vocab), f"vocab_count mismatch: {vocab_count} vs {len(vocab)}"
        assert node_count == len(nodes), f"node_count mismatch: {node_count} vs {len(nodes)}"
        if char_trie_node_count > 0:
            assert char_trie_nodes == char_trie_node_count, f"char_trie_nodes mismatch: {char_trie_nodes} vs {char_trie_node_count}"
            assert char_trie_offset > 0 and char_trie_offset < file_size, f"char_trie_offset invalid: {char_trie_offset}"

        # Read vocab
        f.seek(offset_vocab)
        vocab_offsets = struct.unpack(f'<{vocab_count}I', f.read(4 * vocab_count))
        vocab_blob = f.read()
        # Verify words
        for i, word in enumerate(vocab):
            start = vocab_offsets[i]
            end = vocab_blob.find(b'\x00', start)
            read_word = vocab_blob[start:end].decode('utf-8')
            assert read_word == word, f"Vocab word {i} mismatch: {read_word!r} vs {word!r}"

        # Read nodes (20 bytes each: children_offset u32, child_count u16,
        # followers_offset u32, follower_count u8, phrase_score u8, flags u8, support u32, 3 reserved)
        f.seek(offset_nodes)
        for i, node in enumerate(nodes):
            children_offset, child_count, followers_offset, follower_count, phrase_score, flags, support, r1, r2, r3 = struct.unpack('<IHIBBBIBBB', f.read(20))
            assert children_offset == node.children_offset, f"Node {i} children_offset mismatch"
            assert child_count == node.child_count, f"Node {i} child_count mismatch"
            assert followers_offset == node.followers_offset, f"Node {i} followers_offset mismatch"
            assert follower_count == node.follower_count, f"Node {i} follower_count mismatch"
            assert phrase_score == node.phrase_score, f"Node {i} phrase_score mismatch"
            assert support == node.support, f"Node {i} support mismatch: {support} vs {node.support}"

        # Verify children sorted by word_id
        f.seek(offset_children)
        for node in nodes:
            if node.child_count > 0:
                f.seek(offset_children + node.children_offset)
                prev_word_id = -1
                for _ in range(node.child_count):
                    word_id, child_idx = struct.unpack('<HI', f.read(6))
                    assert word_id > prev_word_id, f"Children not sorted by word_id at node"
                    prev_word_id = word_id

        # Verify followers in score-descending order
        f.seek(offset_followers)
        for node in nodes:
            if node.follower_count > 0:
                f.seek(offset_followers + node.followers_offset)
                prev_score = 256
                for _ in range(node.follower_count):
                    word_id, score = struct.unpack('<HB', f.read(3))
                    assert score <= prev_score, f"Followers not in score-descending order at node"
                    prev_score = score

        # Verify char-trie section (basic sanity)
        if char_trie_nodes > 0:
            f.seek(char_trie_offset)
            # Check root node
            root_data = f.read(12)
            char, flags, children_offset, freq, r1, r2 = struct.unpack('<IBIBBB', root_data)
            assert char == 0, f"Root node char should be 0, got {char}"
            assert flags & 2, f"Root node should have children flag"
            
            # Check root has children
            f.seek(char_trie_offset + children_offset)
            child_count = f.read(1)[0]
            assert child_count > 0, f"Root should have children"
            
            # Walk a known word if vocab has common words
            # (Just verify structure, not specific words since vocab is synthetic)
            print(f"  Char-trie: {char_trie_nodes} nodes, root has {child_count} children")

    print("  Verification passed!")


def generate_synthetic_trigrams(output_path: str, num_contexts: int = 30):
    """Generate a synthetic scored trigram JSON for self-testing."""
    import random
    random.seed(42)

    shared_words = ["the", "a", "i", "you", "we", "they", "he", "she", "it"]
    other_words = [f"w{i}" for i in range(100)]
    all_words = shared_words + other_words

    contexts = []
    for i in range(num_contexts):
        if i < 10:
            ctx = f"the {random.choice(other_words)}"
        elif i < 20:
            ctx = f"a {random.choice(other_words)}"
        elif i < 25:
            ctx = f"i {random.choice(other_words)}"
        else:
            ctx = f"{random.choice(all_words)} {random.choice(other_words)}"
        contexts.append(ctx)

    contexts.sort()

    model = {}
    for ctx in contexts:
        num_followers = random.randint(3, 8)
        selected_words = random.sample(all_words, num_followers)
        scores = [random.uniform(0.0002, 1.0) for _ in range(num_followers)]
        scores.sort(reverse=True)
        followers = [[w, s] for w, s in zip(selected_words, scores)]
        model[ctx] = {"followers": followers, "support": random.randint(1, 100)}

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(model, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Generated synthetic trigrams: {output_path} ({num_contexts} contexts)")


def generate_synthetic_bigrams(output_path: str, num_contexts: int = 20):
    """Generate a synthetic scored bigram JSON for self-testing."""
    import random
    random.seed(123)

    shared_words = ["the", "a", "i", "you", "we", "they", "he", "she", "it"]
    other_words = [f"w{i}" for i in range(100)]
    all_words = shared_words + other_words

    contexts = []
    for i in range(num_contexts):
        if i < 8:
            ctx = random.choice(shared_words)
        else:
            ctx = random.choice(all_words)
        contexts.append(ctx)

    contexts.sort()

    model = {}
    for ctx in contexts:
        num_followers = random.randint(3, 6)
        selected_words = random.sample(all_words, num_followers)
        # Scores already normalized, top-1 = 1.0
        scores = [random.uniform(0.001, 1.0) for _ in range(num_followers)]
        scores.sort(reverse=True)
        followers = [[w, s] for w, s in zip(selected_words, scores)]
        model[ctx] = followers  # Flat shape

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(model, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Generated synthetic bigrams: {output_path} ({num_contexts} contexts)")


def generate_synthetic_unigrams(output_path: str, num_words: int = 50):
    """Generate a synthetic unigram TSV for self-testing."""
    import random
    random.seed(456)

    shared_words = ["the", "a", "i", "you", "we", "they", "he", "she", "it"]
    other_words = [f"w{i}" for i in range(100)]
    all_words = shared_words + other_words

    selected = random.sample(all_words, num_words)
    # Generate counts in descending order
    counts = [random.randint(1000, 1000000) for _ in range(num_words)]
    counts.sort(reverse=True)

    with open(output_path, 'w', encoding='utf-8') as f:
        for word, count in zip(selected, counts):
            f.write(f"{word}\t{count}\n")

    print(f"Generated synthetic unigrams: {output_path} ({num_words} words)")


def generate_synthetic_phrases(output_path: str, trigram_path: str, bigram_path: str | None, num_phrases: int = 10):
    """Generate a synthetic phrases file for self-testing using existing context paths."""
    import random
    import json
    random.seed(789)

    # Collect all context paths from trigrams and bigrams
    paths = []

    # Trigram contexts (2-word paths)
    with open(trigram_path, 'r') as f:
        trigram_data = json.load(f)
    for ctx in trigram_data.keys():
        words = ctx.split()
        if len(words) == 2:
            paths.append(words)

    # Bigram contexts (1-word paths)
    if bigram_path:
        with open(bigram_path, 'r') as f:
            bigram_data = json.load(f)
        for ctx in bigram_data.keys():
            paths.append([ctx])

    if not paths:
        # Fallback: just write empty phrases file
        with open(output_path, 'w', encoding='utf-8') as f:
            pass
        print(f"Generated synthetic phrases: {output_path} (0 phrases - no contexts)")
        return

    with open(output_path, 'w', encoding='utf-8') as f:
        for _ in range(min(num_phrases, len(paths))):
            phrase_words = random.choice(paths)
            phrase_text = ' '.join(phrase_words)
            score = random.uniform(0.01, 1.0)
            f.write(f"{phrase_text}\t{score:.6f}\n")

    print(f"Generated synthetic phrases: {output_path} ({min(num_phrases, len(paths))} phrases)")


def main():
    parser = argparse.ArgumentParser(description="Compile scored n-gram inputs to CKLM v1 binary")
    parser.add_argument('--model', help='Scored trigram model JSON file (required)')
    parser.add_argument('--output', help='Output CKLM file path (required)')
    parser.add_argument('--bigrams', help='Optional scored bigram JSON file (flat shape)')
    parser.add_argument('--bigrams-support', help='Optional bigram support JSON file ({"w1": N} shape)')
    parser.add_argument('--unigrams', help='Optional unigram TSV file (word\\tcount)')
    parser.add_argument('--phrases', help='Optional phrases file (phrase\\tscore per line)')
    parser.add_argument('--thr', type=int, default=0, help='Support threshold for trigrams (default: 0)')
    parser.add_argument('--max-vocab', type=int, default=65535, help='Max vocab words (u16 word ID ceiling, default: 65535)')
    parser.add_argument('--max-unigram-followers', type=int, default=255, help='Max unigram followers at root (u8 ceiling, default: 255)')
    parser.add_argument('--self-test', action='store_true', help='Generate synthetic data, compile, and verify')
    args = parser.parse_args()

    if args.self_test:
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
            synth_trigrams = f.name
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
            synth_bigrams = f.name
        with tempfile.NamedTemporaryFile(mode='w', suffix='.tsv', delete=False) as f:
            synth_unigrams = f.name
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as f:
            synth_phrases = f.name
        with tempfile.NamedTemporaryFile(mode='w', suffix='.cklm', delete=False) as f:
            synth_output = f.name

        generate_synthetic_trigrams(synth_trigrams, num_contexts=30)
        generate_synthetic_bigrams(synth_bigrams, num_contexts=20)
        generate_synthetic_unigrams(synth_unigrams, num_words=50)

        # Need vocab first to generate phrases, so do a quick pass1
        vocab, word_to_id, _, _, _, _ = pass1_collect(synth_trigrams, synth_bigrams, synth_unigrams, args.thr)
        generate_synthetic_phrases(synth_phrases, synth_trigrams, synth_bigrams, num_phrases=10)

        args.model = synth_trigrams
        args.bigrams = synth_bigrams
        args.unigrams = synth_unigrams
        args.phrases = synth_phrases
        args.output = synth_output

    if not args.model or not args.output:
        parser.error("--model and --output are required unless --self-test is used")

    print(f"Model (trigrams): {args.model}")
    if args.bigrams:
        print(f"Bigrams: {args.bigrams}")
    if args.unigrams:
        print(f"Unigrams: {args.unigrams}")
    print(f"Output: {args.output}")
    print(f"Support threshold: {args.thr}")
    if args.phrases:
        print(f"Phrases: {args.phrases}")
    print()

    # Pass 1
    print("Pass 1: collecting vocab and log10 score range...")
    vocab, word_to_id, log10_min, log10_max, unigram_top, unigram_counts = pass1_collect(
        args.model, args.bigrams, args.unigrams, args.thr,
        args.max_vocab, args.max_unigram_followers
    )
    print(f"  Vocab size: {len(vocab):,}")
    print(f"  Log10 score range: [{log10_min:.6f}, {log10_max:.6f}]")

    # Load phrases if provided
    phrase_scores = {}
    if args.phrases:
        print("Loading phrases...")
        phrase_scores = load_phrases(args.phrases, word_to_id)
        print(f"  Loaded {len(phrase_scores)} phrases")

    # Pass 2
    print("Pass 2: building trie...")
    import tempfile as _tempfile
    with _tempfile.TemporaryFile() as children_temp, _tempfile.TemporaryFile() as followers_temp:
        nodes, node_count, follower_count, phrase_count = pass2_build(
            args.model, args.bigrams, args.thr,
            word_to_id, log10_min, log10_max,
            phrase_scores, children_temp, followers_temp,
            unigram_top, args.bigrams_support
        )
        print(f"  Nodes: {node_count:,}")
        print(f"  Followers: {follower_count:,}")
        print(f"  Phrases: {phrase_count:,}")

        # Pass 3: Build character trie (WORD tier)
        print("Pass 3: building character trie...")
        char_nodes, char_children, char_trie_node_count = pass3_build_char_trie(
            vocab, unigram_counts, log10_min, log10_max
        )
        print(f"  Char-trie nodes: {char_trie_node_count:,}")

        # Write output
        print("Writing CKLM file...")
        write_cklm(args.output, vocab, nodes, children_temp, followers_temp,
                   log10_min, log10_max, phrase_count,
                   char_nodes, char_children, char_trie_node_count)

    # Stats
    file_size = os.path.getsize(args.output)
    print(f"\n=== Compilation Complete ===")
    print(f"Output: {args.output}")
    print(f"File size: {file_size:,} bytes ({file_size / 1e6:.2f} MB)")
    print(f"Vocab: {len(vocab):,}")
    print(f"Context-trie nodes: {node_count:,}")
    print(f"Char-trie nodes: {char_trie_node_count:,}")
    print(f"Followers: {follower_count:,}")
    print(f"Phrases: {phrase_count:,}")
    print(f"Log10 score range: [{log10_min:.6f}, {log10_max:.6f}]")

    if args.self_test:
        print("\nRunning verification...")
        verify_cklm(args.output, vocab, nodes, log10_min, log10_max, char_trie_node_count)
        print("Self-test PASSED")

        # Vocab cap test: 30 unigram words capped at 20
        import tempfile as _tf
        with _tf.NamedTemporaryFile(mode='w', suffix='.tsv', delete=False) as f:
            cap_tsv = f.name
        generate_synthetic_unigrams(cap_tsv, num_words=30)
        cap_vocab, _, _, _, _, _ = pass1_collect(synth_trigrams, synth_bigrams, cap_tsv, args.thr, max_vocab=20)
        assert len(cap_vocab) <= 20, f"Vocab cap failed: {len(cap_vocab)} > 20"
        print(f"Vocab cap test: {len(cap_vocab)} words (<= 20) OK")


if __name__ == '__main__':
    main()