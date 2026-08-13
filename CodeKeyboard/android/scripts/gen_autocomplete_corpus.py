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
(not a stopword like "the"/"a"/"to"/"be" — see STOPWORDS below) becomes a
candidate case, not just one random cut. Two reasons:
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
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

random.seed(42)  # reproducible sampling

RAW_DIR = Path(__file__).parent / "corpus_raw"
OUT_FILE = Path(__file__).parent.parent / "app/src/test/resources/autocomplete_eval_cases_generated.tsv"

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

# Closed-class function words: pronouns, articles, prepositions, conjunctions,
# auxiliary/modal/copula verbs, and other high-frequency grammatical glue.
# Grading autocomplete against these is close to meaningless — they're
# extremely predictable regardless of context and carry no real information,
# so a hit or miss here doesn't reflect whether the suggester is actually
# useful. Only content words (nouns, main verbs, adjectives, adverbs, etc.)
# are used as eval targets.
STOPWORDS = frozenset("""
a an the this that these those
i you he she it we they me him her us them my your his its our their
mine yours hers ours theirs myself yourself himself herself itself
ourselves yourselves themselves
is am are was were be been being
do does did doing
have has had having
will would shall should can could may might must
to of in on at for and or but so nor yet
not no as if than then when while where because although though
with by from into onto upon about over under above below between
among through during before after up down out off again further
here there all any both each few more most other some such
own same very just also too
what which who whom whose
""".split())


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
    in `character +++$+++ ... +++$+++ text` format."""
    sentences = []
    with CORNELL_MOVIE_LINES.open(encoding="iso-8859-1") as f:
        for line in f:
            parts = line.split(" +++$+++ ")
            if len(parts) == 5:
                text = clean_typography(parts[4].strip())
                if len(text.split()) >= 4:
                    sentences.append(text)
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


def gen_cases(sentence: str) -> list[tuple[str, str]]:
    """Every word boundary in [sentence] whose target is a content word
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
        if not m or len(m.group()) < 3:
            continue
        target_word = m.group().lower()
        if target_word in STOPWORDS:
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
    rows = []
    all_genres = list(GUTENBERG_SOURCES) + list(PLAIN_SOURCES) + ["casual-chat", "movie-dialogue"]
    for genre in all_genres:
        sentences = load_genre_sentences(genre)

        # Pool every content-word case from every sentence, then sample —
        # this is what lets one sentence contribute several distinct cases
        # (e.g. "breath" / "myself" / "exclaimed" all from one sentence)
        # instead of capping at one cut per sentence.
        pool = []
        for sentence in sentences:
            pool.extend(gen_cases(sentence))

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
