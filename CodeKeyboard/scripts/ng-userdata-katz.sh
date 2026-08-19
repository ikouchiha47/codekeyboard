#!/bin/bash
set -euxo pipefail
exec > >(tee /var/log/ngrams-user-data.log) 2>&1
dnf install -y python3.11 python3.11-pip awscli || dnf install -y python3 awscli
BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
WORK=/opt/ngrams
mkdir -p "$WORK"/{input,scripts,out,work}
cd "$WORK"
aws s3 cp "s3://${BUCKET}/scripts/build_ngrams_katz.py" scripts/ --region "$REGION"
aws s3 cp "s3://${BUCKET}/input/swiftkey_all.txt" input/ --region "$REGION"
echo "started $(date -Is) katz" | aws s3 cp - "s3://${BUCKET}/status/started.txt" --region "$REGION"
/usr/bin/python3 scripts/build_ngrams_katz.py \
  --input input/swiftkey_all.txt \
  --output out/katz_trigrams.json \
  --output-bigrams out/katz_bigrams.json \
  --output-bigrams-support out/katz_bigrams_support.json \
  --order 3 \
  --work-dir work \
  --workers 4 \
  --spill-entries 2000000 \
  --min-ngram-count 3 \
  --min-bigram-count 2 \
  2>&1 | tee out/build.log
aws s3 cp out/build.log "s3://${BUCKET}/output/katz_build.log" --region "$REGION"
aws s3 cp out/katz_trigrams.json "s3://${BUCKET}/output/katz_trigrams.json" --region "$REGION"
aws s3 cp out/katz_bigrams.json "s3://${BUCKET}/output/katz_bigrams.json" --region "$REGION"
aws s3 cp out/katz_bigrams_support.json "s3://${BUCKET}/output/katz_bigrams_support.json" --region "$REGION"
echo "finished $(date -Is) katz" | aws s3 cp - "s3://${BUCKET}/status/finished.txt" --region "$REGION"
INSTANCE_ID=$(TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") && curl -sH "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" || shutdown -h now