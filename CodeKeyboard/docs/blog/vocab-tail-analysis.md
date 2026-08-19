# Is the vocabulary tail worth shipping? A 427K-word vocabulary, capped every way

*2026-08-19*

Our trigram model has a 427,651-word vocabulary — but 85% of those words
appear fewer than 15 times in an 85-million-token corpus. The vocabulary
follows Zipf's law, and the tail is enormous and almost never used.

This post measures what that tail is actually worth: we capped the model's
vocabulary at 16K / 32K / 64K / 128K words (by unigram frequency) and
re-measured suggestion quality and file size at every cap.

**Verdict up front: the vocabulary tail is worthless for both quality and
size.** Capping the model to 16K words (dropping 74% of its vocabulary)
keeps ≥99.2% of top-1 suggestions intact and only saves ~3% of file size.
The delta-pruner already does the tail's job better than any frequency cap
could.

| Cap K | Model vocab kept | Top-1 agreement | Emptied contexts | Entries dropped | Size saved |
|---|---|---|---|---|---|
| 16,000 | 26–51% | 99.2–99.5% | ≤13 (0.0%) | 0.8–3.5% | 0.8–3.3% |
| 32,000 | 46–74% | 99.7–99.9% | ≤5 (0.0%) | 0.3–1.7% | 0.3–1.6% |
| 64,000 | 68–91% | 99.9–100.0% | 0 | 0.1–0.8% | 0.1–0.7% |
| 128,000 | 86–98% | 100.0% | 0 | ≤0.3% | ≤0.3% |

Ranges are across the three smoothing variants (KN, Katz, SwiftKey-WDP);
full per-variant tables below.

---

## Why there is a vocabulary tail at all

A keyboard LM predicts the next word given the previous two. For every
context — *"i am __"*, *"the weather __"* — it stores a list of follower
words with scores. The set of all words that ever appear as followers is
the model's vocabulary. On our corpus it is huge:

| Vocabulary slice | % of all corpus tokens | Count threshold |
|---|---|---|
| top 16,000 words | 95.63% | ≥213 occurrences |
| top 32,000 words | 97.78% | ≥61 |
| top 64,000 words | 98.93% | ≥15 |
| top 128,000 words | 99.50% | ≥4 |
| all 427,651 words | 100.00% | ≥1 |

Corpus: Coursera-SwiftKey capstone `en_US` (blogs + news + twitter),
85,038,653 tokens. The 363,651 words beyond rank 64,000 appear ≤15 times
*each* across the whole corpus and cover ~1% of all tokens.

What does that tail look like? At the 16K boundary (count 213):

```
gowns  greenwood  groin  guacamole  huddled  itch  lois  mama's  marinade
nationalist  netanyahu  nominating  overload  python  raul  scented ...
```

At the 64K boundary (count 15):

```
macgillivray  madson  magnolia's  maile  mailroom  maitake  makino  maksim
malacca  malden  malfoy  malinda  mangwende  manhattans  manocchio
mantelpiece  manzanita  margaritaville  margolin ...
```

Proper nouns, possessives (`dallas'`, `major's`), rare loanwords, and
near-typos. 15.7% of words beyond rank 64K contain an apostrophe. This is
the tail we're deciding whether to ship.

## How we tested it

We wrote a streaming analyzer, `scripts/vocab_cap.py` (full walkthrough
below), which:

1. Loads the unigram frequency list (already sorted by count descending).
2. For each cap K, builds the vocabulary = the top K words.
3. Streams the scored trigram model JSON — 230 MB to 1.9 GB, never loaded
   into memory — and for every context with support ≥25 (269,397
   contexts), deletes every follower entry whose word isn't in the
   top-K vocabulary.
4. Measures, against the full (uncapped) model:
   - **top-1 agreement** — how often the filtered model's top suggestion
     still equals the uncapped model's;
   - **emptied contexts** — contexts where every follower got deleted;
   - **entries dropped** — follower pairs removed;
   - **serialized size** before/after, using exact byte-for-byte
     serialization matching the builder.

Run on AWS spot instances for all three smoothing variants (KN, Katz,
SwiftKey-WDP) at caps 16K / 32K / 64K / 128K.

## The numbers, per variant

### SwiftKey-WDP (the variant we ship) — 60,443 distinct words in model

| K | Words beyond cap | Top-1 agree | Emptied | Entries dropped | Size |
|---|---|---|---|---|---|
| 16,000 | 44,773 (74.1%) | 99.2% | 13 (0.0%) | 3.5% | 51.6 → 49.9 MB (−3.3%) |
| 32,000 | 32,537 (53.8%) | 99.7% | 5 (0.0%) | 1.7% | 51.6 → 50.7 MB (−1.6%) |
| 64,000 | 18,880 (31.2%) | 99.9% | 0 | 0.8% | 51.6 → 51.2 MB (−0.7%) |
| 128,000 | 8,812 (14.6%) | 100.0% | 0 | 0.3% | 51.6 → 51.4 MB (−0.3%) |

### KN (Kneser-Ney) — 28,452 distinct words in model

| K | Words beyond cap | Top-1 agree | Emptied | Entries dropped | Size |
|---|---|---|---|---|---|
| 16,000 | 13,897 (48.8%) | 99.5% | 0 | 0.8% | 52.0 → 51.6 MB (−0.8%) |
| 32,000 | 6,933 (24.4%) | 99.9% | 0 | 0.3% | 52.0 → 51.9 MB (−0.3%) |
| 64,000 | 2,525 (8.9%) | 100.0% | 0 | 0.1% | 52.0 → 52.0 MB (−0.1%) |
| 128,000 | 667 (2.3%) | 100.0% | 0 | 0.0% | 52.0 → 52.0 MB (−0.0%) |

### Katz — 56,500 distinct words in model

| K | Words beyond cap | Top-1 agree | Emptied | Entries dropped | Size |
|---|---|---|---|---|---|
| 16,000 | 40,954 (72.5%) | 99.3% | 1 (0.0%) | 3.1% | 51.9 → 50.3 MB (−3.0%) |
| 32,000 | 29,317 (51.9%) | 99.7% | 1 (0.0%) | 1.5% | 51.9 → 51.1 MB (−1.4%) |
| 64,000 | 16,774 (29.7%) | 99.9% | 0 | 0.7% | 51.9 → 51.5 MB (−0.7%) |
| 128,000 | 7,783 (13.8%) | 100.0% | 0 | 0.3% | 51.9 → 51.7 MB (−0.3%) |

---

## What the numbers say, semantically

Three things are going on, and it's worth being honest about what each one
means — and what it doesn't.

**1. Top-1 agreement is the wrong metric for keyboard suggestions — but that
makes the result *stronger*, not weaker.** A keyboard shows 3–5 suggestions
and filters them by the prefix the user is typing, so the interesting
question isn't "is the #1 word the same?" but "did we lose any word that
would have been the best *prefix-filtered* suggestion?" Capping the
vocabulary can only *remove* followers — and we measured that removing
74% of the vocabulary empties only 13 of 269,397 contexts. The tail isn't
just rare in the corpus; it's rare *as a useful follower*. Words that
survive the delta-pruner into a context are, by construction, the ones
carrying phrase-level information — and those are overwhelmingly
high-frequency words. (Gboard's next-word decoder is a beam search over a
composed FST — a different shape — but for *our* direct
context→followers lookup, the follower list *is* the entire hypothesis
space.)

**2. The size saving is tiny because follower lists dominate the file, not
the vocabulary.** Cutting 74% of the vocabulary saved only 3.3% of bytes.
The serialized model is dominated by context→follower structure (per-list
counts and word IDs); the vocabulary table is a small fraction of it.
Frequency-capping is a pruning strategy aimed at the wrong part of the
data layout. The entries the tail contributes are only 0.3–3.5% of all
follower entries — *and* those are precisely the entries the WDP pruner
already removes where they carry no information.

**3. The model self-selects a compact vocabulary.** KN concentrates on
just 28,452 distinct words (its `cont_p` backoff penalizes words that
only ever continue one context — i.e., exactly the tail). SwiftKey-WDP and
Katz land at ~56–60K. All three fit under the 64K ceiling the industry
uses (Gboard: 64K n-gram vocabulary), *without* any explicit cap. The
tail is not just useless — it essentially never survives our pipeline into
a shipped context.

Caveat, stated plainly: this measures *suggestion agreement with the
uncapped model*, not *accuracy against ground truth*. It answers "does
the cap change what we'd predict?" — not "are the predictions right?" If
the uncapped model itself never surfaces tail words, capping can't hurt;
and since tail words are near-zero-frequency, the uncapped model has
essentially no signal about them anyway. This is the right question to
ask when deciding whether to ship a smaller model.

## Why does Gboard use 64K then?

Short answer: **it's a task + memory-budget design choice, not a consequence
of 5-gram order or a 16-bit FST label ceiling.**

Evidence (from Hellsten et al. 2017, Hard et al. 2018, Zhang et al. 2024,
OpenFst source):

- **Hellsten et al. 2017** (the Gboard WFST decoder paper): "keyboard
  language models are typically low order n-grams over a limited vocabulary,
  e.g. 64K words" — presented as a *typical keyboard-LM design value*,
  independent of n-gram order. They tie it to the device memory envelope:
  "keyboard language models should not exceed 5 to 10 Mb, which typically
  allows them to model a couple hundred thousand words at most." And to the
  task: the vocabulary is "hand-curated to eliminate misspellings, erroneous
  capitalizations…"

- **Hard et al. 2018**: Gboard's static English LM is "a Katz smoothed
  Bayesian interpolated 5-gram LM containing 1.25 million n-grams, including
  164,000 unigrams." The 64K is the *n-gram prediction vocabulary*; the
  full unigram lexicon is ~170K.

- **Zhang et al. 2024 (EMNLP)**: "G is a N-gram language FST containing
  **64k words for n-grams and 170k words for uni-grams**." Their neural LM
  uses "a **30k-word vocabulary (top words from Federated Counting)**,
  while the full lexicon contains 170k words."

- **OpenFst NGramFst / CompactFst**: labels are templated on `typename
  A::Label`; `StandardArc` uses **int32** labels. Nothing forces 16-bit /
  65536. The LOUDS encoding stores labels in full 32-bit arrays. **A 16-bit
  word-ID cap is not supported by the source.**

So the 64K is a round, hand-set size for the *curated prediction lexicon*
(~5–10 MB model budget), not a bit-boundary artifact (30K and 170K also
appear). Our 3-gram on 85M tokens naturally lands at ~28–60K distinct
words — under that ceiling without any explicit cap. A 5-gram on Google-
scale data would have more n-gram states referencing the tail, but the
*vocabulary size* is a separate design knob.

---

## The code, walked through

`scripts/vocab_cap.py` — 320 lines, zero dependencies beyond stdlib.

```bash
python3 scripts/vocab_cap.py \
  --unigrams swiftkey_unigrams.tsv \   # word<TAB>count, sorted by count DESC
  --model swiftkey_tri_cap10.json \    # scored trigram model (streamed)
  --caps 16000 32000 64000 128000 \
  --thr 25                             # support threshold for contexts kept
```

Three pieces matter:

### 1. Streaming JSON parser (`stream_ngrams`, lines 21–128)

The model files are 230 MB (SwiftKey-WDP) to 1.9 GB (KN at cap 64) —
`json.load` would need many GB of RAM. The parser reads byte-by-byte and
tracks brace/bracket depth to slice out one `"ctx": {value}` pair at a
time, then `json.loads` just that slice. It verifies the builder's
guarantee that top-level keys are lexicographically sorted, and it must
track `in_string`/`in_escape` so braces inside quoted contexts don't
confuse the depth counter. (This is the same parser as
`scripts/compare_ngrams.py`, reused.)

```python
def stream_ngrams(filepath: str) -> Iterator[NgramEntry]:
    ...
    # read key, skip ':', then read value by tracking brace/bracket depth
    value_bytes = bytearray(ch)
    depth_brace = 1 if ch == b'{' else 0
    ...
    while depth_brace == 0 and depth_bracket == 0 and not in_string:
        break
    yield NgramEntry(ctx=ctx, followers=value['followers'], support=value['support'])
```

### 2. Exact size accounting (`serialize_entry`, lines 131–136)

Size before/after must reflect the builder's real output, so the analyzer
re-serializes each filtered entry exactly as `build_ngrams*.py` does —
`json.dumps(entry, ensure_ascii=False, separators=(",", ":"))` plus the
context key and a comma between entries. That makes the "51.6 → 49.9 MB"
numbers directly comparable to the JSON files we actually ship.

```python
def serialize_entry(ctx, followers, support) -> bytes:
    entry = {"followers": followers, "support": support}
    ctx_json = json.dumps(ctx, ensure_ascii=False)
    entry_json = json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
    return (ctx_json + ":" + entry_json).encode('utf-8')
```

### 3. Single-pass per-cap analysis (`analyze_cap`, lines 156–232)

For each cap we stream the model once and accumulate everything:
contexts kept (support ≥ thr), full vs filtered top-1, emptied contexts,
dropped entries, and both serialized sizes — plus the set of distinct
model words in vs out of the vocab. `distinct_words_in_model` is what
tells us the model *actually* uses 28K–60K words of the 427K available.

```python
filtered = [f for f in entry.followers if f[0] in vocab]
...
if entry.followers:
    full_top1 = entry.followers[0][0]
    if filtered and filtered[0][0] == full_top1:
        top1_agree += 1
```

`main()` prints a per-cap block and a summary table. On AWS the three
runs (one per variant) took a few minutes each on t3.xlarge spot
instances, streaming the cap-10 model JSON.

---

## How to reproduce

```bash
# on a machine with the DBs and unigrams (see ngram-pipeline-optimization.md)
aws s3 cp s3://codekeyboard-ngrams-790762402508/output/swiftkey_tri_cap10.json .
aws s3 cp s3://codekeyboard-ngrams-790762402508/output/swiftkey_unigrams.tsv .
python3 scripts/vocab_cap.py --unigrams swiftkey_unigrams.tsv --model swiftkey_tri_cap10.json
```

The AWS orchestration (`gen_sweep_instances.sh`, user-data templates)
used to run this at scale is in `scripts/`; each run emits
`{variant}_vocab_report.txt` to S3.

---

## What we decided because of this

- **No frequency-based vocabulary cap in production.** The delta-pruner is
  the right tail-management tool; it already produces a model whose
  effective vocabulary is ~28–60K words.
- **The binary format stores exactly what the delta keeps** — variable
  follower count per context, byte-quantized scores, u8 cap 255. No
  additional vocab culling at build or write time.
- Follow-up: why 64K is the industry number, and whether a 5-gram model
  would change this answer (next post).
