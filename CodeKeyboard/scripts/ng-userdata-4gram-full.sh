#!/bin/bash
# Full 4-gram build on combined OpenSubtitles + SwiftKey corpus.
# Input:  s3://BUCKET/input/en.txt.gz   (OpenSubtitles, ~3.4GB gz)
#         s3://BUCKET/input/swiftkey_enus.txt
# Output: s3://BUCKET/output/full_fourgrams.json  (KN-smoothed 4-grams)
#         s3://BUCKET/output/full_trigrams.json
#         s3://BUCKET/output/full_bigrams.json
#         s3://BUCKET/output/full_phrases.json
#         s3://BUCKET/output/full_phrases.txt
set -euxo pipefail
exec > >(tee /var/log/ngrams-user-data.log) 2>&1

dnf install -y python3.11 python3.11-pip awscli || dnf install -y python3 awscli

BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
WORK=/opt/ngrams
mkdir -p "$WORK"/{input,scripts,out,work}
cd "$WORK"

# Download scripts
aws s3 cp "s3://${BUCKET}/scripts/build_ngrams.py" scripts/ --region "$REGION"
aws s3 cp "s3://${BUCKET}/scripts/extract_phrases.py" scripts/ --region "$REGION"

echo "started $(date -Is) full-4gram" | aws s3 cp - "s3://${BUCKET}/status/started.txt" --region "$REGION"

# Download + decompress OpenSubtitles
aws s3 cp "s3://${BUCKET}/input/en.txt.gz" input/en.txt.gz --region "$REGION"
gunzip -k input/en.txt.gz   # produces input/en.txt

# Download SwiftKey
aws s3 cp "s3://${BUCKET}/input/swiftkey_enus.txt" input/swiftkey_enus.txt --region "$REGION"

# Combine corpora
cat input/en.txt input/swiftkey_enus.txt > input/combined.txt
echo "combined lines: $(wc -l < input/combined.txt)"

# Run 4-gram build (KN smoothing, order=4)
/usr/bin/python3 scripts/build_ngrams.py \
  --input input/combined.txt \
  --output out/full_fourgrams.json \
  --output-bigrams out/full_bigrams.json \
  --output-bigrams-support out/full_bigrams_support.json \
  --order 4 \
  --work-dir work \
  --workers 16 \
  --spill-entries 5000000 \
  --min-ngram-count 3 \
  --min-bigram-count 2 \
  2>&1 | tee out/build.log

# Also emit trigrams.json (used by cklm compiler)
/usr/bin/python3 scripts/build_ngrams.py \
  --input input/combined.txt \
  --output out/full_trigrams.json \
  --output-bigrams /dev/null \
  --output-bigrams-support /dev/null \
  --order 3 \
  --work-dir work \
  --workers 16 \
  --spill-entries 5000000 \
  --min-ngram-count 3 \
  --min-bigram-count 2 \
  2>&1 | tee -a out/build.log

# Upload n-gram outputs
aws s3 cp out/build.log           "s3://${BUCKET}/output/full_build.log"           --region "$REGION"
aws s3 cp out/full_fourgrams.json "s3://${BUCKET}/output/full_fourgrams.json"      --region "$REGION"
aws s3 cp out/full_trigrams.json  "s3://${BUCKET}/output/full_trigrams.json"       --region "$REGION"
aws s3 cp out/full_bigrams.json   "s3://${BUCKET}/output/full_bigrams.json"        --region "$REGION"

# Phrase extraction (PMI + t-score, max 4-word phrases, English-vocab filtered)
/usr/bin/python3 - <<'PYEOF'
import sqlite3
conn = sqlite3.connect("work/counts.sqlite")
with open("out/full_unigrams.tsv", "w", encoding="utf-8") as f:
    for w, c in conn.execute("SELECT w, count FROM unigrams ORDER BY count DESC"):
        f.write(f"{w}\t{c}\n")
conn.close()
PYEOF

/usr/bin/python3 scripts/extract_phrases.py \
  --counts work/counts.sqlite \
  --output out/full_phrases.json \
  --output-text out/full_phrases.txt \
  --min-count 20 \
  --min-pmi 2.0 \
  --max-len 4 \
  2>&1 | tee -a out/build.log

aws s3 cp out/full_unigrams.tsv  "s3://${BUCKET}/output/full_unigrams.tsv"  --region "$REGION"
aws s3 cp out/full_phrases.json  "s3://${BUCKET}/output/full_phrases.json"  --region "$REGION"
aws s3 cp out/full_phrases.txt   "s3://${BUCKET}/output/full_phrases.txt"   --region "$REGION"
aws s3 cp work/counts.sqlite     "s3://${BUCKET}/output/full_counts.sqlite" --region "$REGION"

echo "finished $(date -Is) full-4gram" | aws s3 cp - "s3://${BUCKET}/status/finished.txt" --region "$REGION"

INSTANCE_ID=$(TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") && curl -sH "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" || shutdown -h now
