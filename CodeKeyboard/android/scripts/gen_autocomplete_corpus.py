#!/usr/bin/env python3
"""
Generates a large, real-text autocomplete eval fixture (see ADR-007) by
sampling random sentences from a genre-diverse corpus and using the actual
next word/word-in-progress as ground truth — instead of hand-labeled
"correct" answers, which don't scale and encode the labeler's own bias.

Sources (all public domain or permissively licensed, safe to derive short
fragments from and commit to the repo):
  - fiction:      Pride and Prejudice, Alice in Wonderland, Frankenstein
                   (Project Gutenberg, public domain)
  - nonfiction:    Wealth of Nations, Meditations
                   (Project Gutenberg, public domain — older register,
                   noted as a known skew in ADR-007)
  - tech:          READMEs from vscode, react, kubernetes
                   (MIT/Apache-licensed OSS; only short derived fragments
                   are stored, not the READMEs themselves)
  - news:          Wikinews article extracts (CC BY 2.5)
  - casual-chat:   NPS Chat Corpus — real anonymized chatroom posts
                   (distributed with NLTK, research-permissive license)
  - movie-dialogue: Cornell Movie-Dialogs Corpus — real movie script lines
                   (research-permissive license, cs.cornell.edu)

The last two exist specifically because fiction/nonfiction/tech/news are
all formal or literary register and don't represent how people actually
type on a phone keyboard (short, casual, contractions, fragments).

For each sentence, EVERY word boundary whose target is a content word
becomes a candidate case, not just one random cut. "Content word" is
decided by a pipeline of small, independent filter strategies (see the
TargetFilter classes below) chained with AND: minimum length, spaCy POS
class, spaCy is_stop, and a frequency-rank ceiling read directly from
en.trie (the production suggester's own dictionary) — a word ranked in
the top ~200 most frequent words in en.trie (know/get/go/well/said/make/
...) is rejected even though it's grammatically a content word, because
hitting or missing it says nothing about suggestion quality; it's
trivially predictable regardless of context. Two reasons for the
multi-cut approach itself:
  1. Grading on stopwords measures nothing real — missing "the" or "be" has
     no user-facing cost, and the base model already gets these ~100% right
     (see ADR-007 checkpoint #1). Diluting the eval with them just inflates
     the pass rate without saying anything about actual quality.
  2. One cut per sentence throws away most of a sentence's signal. "I
     gasped for breath, and throwing myself on the body, I exclaimed" has
     several genuinely different, meaningful test points in it (breath /
     myself / exclaimed), not just one.
Each qualifying boundary is either a "next-word" case (cut exactly at the
boundary, nothing typed) or a "prefix" case (1 to len-1 characters of the
target typed) — chosen at random per boundary.

Typographic artifacts from the Gutenberg source (curly quotes “ ” ‘ ’, em
dashes) are stripped before splitting/cutting — they're not something a
person would ever actually type, and shouldn't leak into context.

Output: app/src/test/resources/autocomplete_eval_cases_generated.tsv
Same 3-column format as the curated fixture (sentence, expected, category),
but here `expected` is always a single word — the one real word that
followed in the source text, not a curated list of acceptable alternatives.
"""

import random
import re
import struct
import sys
import xml.etree.ElementTree as ET
from abc import ABC, abstractmethod
from pathlib import Path

import spacy

random.seed(42)  # reproducible sampling

RAW_DIR = Path(__file__).parent / "corpus_raw"
OUT_FILE = Path(__file__).parent.parent / "app/src/test/resources/autocomplete_eval_cases_generated.tsv"
TRIE_FILE = Path(__file__).parent.parent / "app/src/main/assets/en.trie"

GUTENBERG_SOURCES = {
    "fiction": ["pride_prejudice.txt", "alice_wonderland.txt", "frankenstein.txt"],
    "nonfiction-classic": ["wealth_of_nations.txt", "meditations.txt"],
}
PLAIN_SOURCES = {
    "tech": ["readme_vscode.txt", "readme_react.txt", "readme_kubernetes.txt"],
    "news": ["wikinews_extracts.txt"],
}
# These two are the actually-conversational genres — real informal chat and
# real movie dialogue — added specifically because the Gutenberg/news/tech
# sources above are all formal/literary register and don't represent how
# people actually type on a phone keyboard (short, casual, fragments).
NPS_CHAT_DIR = RAW_DIR / "nps_chat" / "nps_chat"
CORNELL_MOVIE_LINES = RAW_DIR / "cornell_movie" / "cornell movie-dialogs corpus" / "movie_lines.txt"

CASES_PER_GENRE = 200
# `tech` currently draws from just 3 READMEs (~75 usable sentences) — far
# below the other genres. Rather than keep scraping more repos for volume,
# this is intentionally left capped low and recorded as a known gap in
# ADR-007: a real fix is a purpose-built coding/technical corpus (docstrings,
# commit messages, Stack Overflow-style text), not more scraped READMEs.
WORD_RE = re.compile(r"[A-Za-z']+")

_NLP = None


def get_nlp():
    global _NLP
    if _NLP is None:
        _NLP = spacy.load("en_core_web_sm")
    return _NLP


def align_tokens(sentences: list[str]) -> list[list]:
    """For each sentence, returns a list of spaCy Tokens (or None) aligned
    to sentence.split(" ") — the token that starts at that whitespace word's
    character offset, used by the POS/is_stop filter strategies below."""
    nlp = get_nlp()
    results = []
    for doc in nlp.pipe(sentences, batch_size=64):
        words = doc.text.split(" ")
        tokens = []
        offset = 0
        for word in words:
            start, end = offset, offset + len(word)
            span = doc.char_span(start, end, alignment_mode="expand")
            tokens.append(span[0] if span is not None and len(span) > 0 else None)
            offset = end + 1  # +1 for the space
        results.append(tokens)
    return results


# ── Target filter strategies ────────────────────────────────────────────
# Whether a word makes a meaningful autocomplete eval target is decided by
# a chain of small, independent, swappable filters (strategy pattern) —
# rather than one hardcoded rule — so each concern (length, grammar,
# real-world frequency) can be reasoned about, tuned, or replaced on its
# own. All filters in the chain must accept for a word to qualify.


class TargetFilter(ABC):
    @abstractmethod
    def accept(self, word: str, token) -> bool:
        """word is the lowercased target; token is the aligned spaCy Token
        (may be None if spaCy alignment failed for that boundary)."""


class MinLengthFilter(TargetFilter):
    def __init__(self, min_len: int = 3):
        self.min_len = min_len

    def accept(self, word: str, token) -> bool:
        return len(word) >= self.min_len


class ContentPOSFilter(TargetFilter):
    """Only nouns/proper nouns/verbs/adjectives — no adverbs, which are
    disproportionately closed-class-ish intensifiers (very/well/still/
    rather) even when spaCy tags them ADV."""

    ALLOWED_POS = frozenset({"NOUN", "PROPN", "VERB", "ADJ"})

    def accept(self, word: str, token) -> bool:
        return token is not None and token.pos_ in self.ALLOWED_POS


class NotSpacyStopFilter(TargetFilter):
    def accept(self, word: str, token) -> bool:
        return token is not None and not token.is_stop


class TrieRankFilter(TargetFilter):
    """Rejects words ranked among the most frequent N words in en.trie —
    the actual production suggester's dictionary. Catches ultra-common
    words that are grammatically content words but trivially predictable
    (know/get/go/well/said/make/...), without hardcoding a word list.
    Requires the word to exist in en.trie at all: if the model can't emit
    it, grading against it as ground truth doesn't test anything real."""

    def __init__(self, ranks: dict[str, int], min_rank: int = 200):
        self.ranks = ranks
        self.min_rank = min_rank

    def accept(self, word: str, token) -> bool:
        rank = self.ranks.get(word)
        return rank is not None and rank > self.min_rank


class CompositeFilter(TargetFilter):
    def __init__(self, filters: list[TargetFilter]):
        self.filters = filters

    def accept(self, word: str, token) -> bool:
        return all(f.accept(word, token) for f in self.filters)


class TrieReader:
    """Minimal read-only parser for the TRIF binary format written by the
    production trie builder (see Trie.kt for the canonical implementation
    this mirrors) — used here only to dump a word -> frequency-rank map."""

    HEADER_SIZE = 12
    NODE_SIZE = 12  # TRIF: char(1)+flags(1)+childOff(4)+freq(4)+pad(2)

    def __init__(self, path: Path):
        self.buf = path.read_bytes()
        magic = self.buf[:4]
        if magic != b"TRIF":
            raise ValueError(f"Unsupported trie format: {magic!r} (expected TRIF)")
        self.node_count = struct.unpack_from("<i", self.buf, 8)[0]
        self.children_base = self.HEADER_SIZE + self.node_count * self.NODE_SIZE

    def _node_flags(self, idx: int) -> int:
        return self.buf[self.HEADER_SIZE + idx * self.NODE_SIZE + 1]

    def _children_block_offset(self, idx: int) -> int:
        return struct.unpack_from("<i", self.buf, self.HEADER_SIZE + idx * self.NODE_SIZE + 2)[0]

    def _node_frequency(self, idx: int) -> int:
        return struct.unpack_from("<i", self.buf, self.HEADER_SIZE + idx * self.NODE_SIZE + 6)[0]

    def word_ranks(self) -> dict[str, int]:
        """Full DFS dump of every terminal word -> frequency, then converted
        to a 1-indexed rank (1 = most frequent)."""
        freqs: dict[str, int] = {}
        stack = [(0, "")]
        while stack:
            idx, prefix = stack.pop()
            flags = self._node_flags(idx)
            if flags & 1 and prefix:
                freqs[prefix] = self._node_frequency(idx)
            if flags & 2:
                block_off = self.children_base + self._children_block_offset(idx)
                child_count = self.buf[block_off]
                for i in range(child_count):
                    base = block_off + 1 + i * 4
                    ch = chr(self.buf[base])
                    b = base + 1
                    cidx = self.buf[b] | (self.buf[b + 1] << 8) | (self.buf[b + 2] << 16)
                    stack.append((cidx, prefix + ch))
        ranked = sorted(freqs.items(), key=lambda kv: -kv[1])
        return {word: rank + 1 for rank, (word, _freq) in enumerate(ranked)}


def build_target_filter() -> TargetFilter:
    ranks = TrieReader(TRIE_FILE).word_ranks()
    return CompositeFilter([
        MinLengthFilter(min_len=3),
        ContentPOSFilter(),
        NotSpacyStopFilter(),
        TrieRankFilter(ranks, min_rank=200),
    ])


def strip_gutenberg_boilerplate(text: str) -> str:
    start = re.search(r"\*\*\* START OF.*?\*\*\*", text)
    end = re.search(r"\*\*\* END OF.*?\*\*\*", text)
    if start and end:
        return text[start.end():end.start()]
    return text


def strip_markdown(text: str) -> str:
    text = re.sub(r"```.*?```", " ", text, flags=re.DOTALL)  # code blocks
    text = re.sub(r"`[^`]*`", " ", text)                      # inline code
    text = re.sub(r"!\[.*?\]\(.*?\)", " ", text)              # images
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)      # links -> text
    text = re.sub(r"^#+\s*", "", text, flags=re.MULTILINE)    # headers
    text = re.sub(r"[*_#>`|-]", " ", text)                     # markdown punctuation
    return text


# Typographic characters a person would never actually type on a keyboard —
# normalized to their plain-ASCII equivalents (or dropped) so they don't leak
# into test-case context.
QUOTE_NORMALIZE = str.maketrans({
    "“": "", "”": "",  # curly double quotes “ ”
    "‘": "'", "’": "'",  # curly single quotes ‘ ’ (keep as apostrophe)
    "—": " ", "–": " ",  # em dash / en dash
    "…": " ",  # ellipsis character
})


def clean_typography(text: str) -> str:
    return text.translate(QUOTE_NORMALIZE)


def split_sentences(text: str) -> list[str]:
    text = clean_typography(text)
    text = re.sub(r"\s+", " ", text).strip()
    # Simple sentence splitter: sufficient for sampling, not linguistically perfect.
    raw = re.split(r"(?<=[.!?])\s+(?=[A-Z])", text)
    return [s.strip() for s in raw if len(s.split()) >= 6]


CHAT_USER_MENTION_RE = re.compile(r"\d{1,2}-\d{1,2}-(?:teens|20s|30s|40s|adults)User\d+\.*")


def load_casual_chat_sentences() -> list[str]:
    """NPS Chat Corpus — real anonymized chatroom posts. Each post is treated
    as one sentence directly (no splitting needed, they're already single
    utterances); system/emoticon-only posts are dropped."""
    sentences = []
    for path in sorted(NPS_CHAT_DIR.glob("*.xml")):
        tree = ET.parse(path)
        for post in tree.getroot().iter("Post"):
            if post.get("class") in ("System", "Emotion", "Accept", "Reject"):
                continue
            text = (post.text or "").strip()
            text = CHAT_USER_MENTION_RE.sub("", text).strip()
            text = clean_typography(text)
            if len(text.split()) >= 4:
                sentences.append(text)
    return sentences


def load_movie_dialogue_sentences() -> list[str]:
    """Cornell Movie-Dialogs Corpus — real movie script lines, one per line
    in `character +++$+++ ... +++$+++ text` format. A "line" is one full
    dialogue turn and often contains several sentences, so it's re-split on
    sentence boundaries — otherwise multi-sentence turns leak through as one
    garbled context spanning unrelated sentences."""
    sentences = []
    with CORNELL_MOVIE_LINES.open(encoding="iso-8859-1") as f:
        for line in f:
            parts = line.split(" +++$+++ ")
            if len(parts) == 5:
                sentences.extend(split_sentences(parts[4].strip()))
    return sentences


def load_genre_sentences(genre: str) -> list[str]:
    if genre == "casual-chat":
        return load_casual_chat_sentences()
    if genre == "movie-dialogue":
        return load_movie_dialogue_sentences()

    parts = []
    for fname in GUTENBERG_SOURCES.get(genre, []):
        raw = (RAW_DIR / fname).read_text(errors="ignore")
        parts.append(strip_gutenberg_boilerplate(raw))
    for fname in PLAIN_SOURCES.get(genre, []):
        raw = (RAW_DIR / fname).read_text(errors="ignore")
        if fname.startswith("readme"):
            raw = strip_markdown(raw)
        parts.append(raw)
    return split_sentences("\n".join(parts))


def gen_cases(sentence: str, tokens: list, target_filter: TargetFilter) -> list[tuple[str, str]]:
    """Every word boundary in [sentence] whose target passes target_filter
    becomes one (sentenceSoFar, expectedWord) case — not just one random
    cut. A long sentence yields several genuinely different test points."""
    words = sentence.split(" ")
    if len(words) < 4:
        return []

    cases = []
    # Cut after at least 2 preceding words, leaving at least 1 following word,
    # so there's always meaningful context and a real ground-truth next word.
    for cut_idx in range(2, len(words) - 1):
        target_word_raw = words[cut_idx]
        m = WORD_RE.match(target_word_raw)
        if not m:
            continue
        target_word = m.group().lower()
        if not target_filter.accept(target_word, tokens[cut_idx]):
            continue

        context_words = words[:cut_idx]
        if random.random() < 0.5:
            # next-word case: nothing typed yet
            sentence_so_far = " ".join(context_words) + " "
        else:
            # prefix case: 1 to len-1 chars of the target word typed
            typed_len = random.randint(1, max(1, len(target_word) - 1))
            sentence_so_far = " ".join(context_words) + " " + target_word[:typed_len]

        cases.append((sentence_so_far, target_word))

    return cases


def main():
    target_filter = build_target_filter()
    rows = []
    all_genres = list(GUTENBERG_SOURCES) + list(PLAIN_SOURCES) + ["casual-chat", "movie-dialogue"]
    for genre in all_genres:
        sentences = load_genre_sentences(genre)
        tokens_per_sentence = align_tokens(sentences)

        # Pool every content-word case from every sentence, then sample —
        # this is what lets one sentence contribute several distinct cases
        # (e.g. "breath" / "myself" / "exclaimed" all from one sentence)
        # instead of capping at one cut per sentence.
        pool = []
        for sentence, tokens in zip(sentences, tokens_per_sentence):
            pool.extend(gen_cases(sentence, tokens, target_filter))

        random.shuffle(pool)
        sampled = pool[:CASES_PER_GENRE]
        category = f"generated-{genre}"
        for sentence_so_far, expected in sampled:
            rows.append((sentence_so_far, expected, category))

        print(f"{genre}: sampled {len(sampled)} cases from a pool of {len(pool)} "
              f"content-word candidates across {len(sentences)} sentences", file=sys.stderr)

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with OUT_FILE.open("w") as f:
        f.write("# Auto-generated by scripts/gen_autocomplete_corpus.py — see ADR-007.\n")
        f.write("# DO NOT hand-edit; re-run the script to regenerate.\n")
        f.write("# Format: sentenceSoFar <TAB> expectedWord <TAB> category\n")
        f.write("#\n")
        f.write("# Unlike the curated fixture, expectedWord here is the single real word\n")
        f.write("# that followed in the source text — not a curated list of acceptable\n")
        f.write("# alternatives. A miss here can be a legitimate synonym, not necessarily\n")
        f.write("# a wrong suggestion; see ADR-007 for how this is interpreted.\n")
        f.write("#\n")
        f.write("# expectedWord is always a content word (noun/verb/adjective/adverb/etc) —\n")
        f.write("# stopwords (a/the/to/be/of/...) are excluded as eval targets since a\n")
        f.write("# hit/miss on them doesn't reflect real suggestion quality. Multiple cases\n")
        f.write("# can come from the same source sentence, one per qualifying word boundary.\n")
        for sentence_so_far, expected, category in rows:
            f.write(f"{sentence_so_far}\t{expected}\t{category}\n")

    print(f"Wrote {len(rows)} cases to {OUT_FILE}", file=sys.stderr)


if __name__ == "__main__":
    main()
