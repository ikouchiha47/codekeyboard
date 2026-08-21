#!/bin/bash
# Vocab-tail test (generic): download <VARIANT> unigrams + cap10 model +
# vocab_cap.py, measure top-1 agreement when vocabulary is capped at
# 16K/32K/64K/128K, upload report to S3. One instance per variant.
# Template — generate via scripts/gen_sweep_instances.sh.
set -euxo pipefail
exec > >(tee /var/log/ngrams-vocab.log) 2>&1
dnf install -y python3.11 python3.11-pip awscli || dnf install -y python3 awscli
BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
VARIANT="kn"
WORK=/opt/ngrams
mkdir -p "$WORK"/{scripts,out}
cd "$WORK"
aws s3 cp "s3://${BUCKET}/scripts/vocab_cap.py" scripts/ --region "$REGION"
aws s3 cp "s3://${BUCKET}/output/${VARIANT}_unigrams.tsv" out/unigrams.tsv --region "$REGION"
aws s3 cp "s3://${BUCKET}/output/${VARIANT}_tri_cap10.json" out/model.json --region "$REGION"
echo "started $(date -Is) vocab ${VARIANT}" | aws s3 cp - "s3://${BUCKET}/status/started.txt" --region "$REGION"
/usr/bin/python3 scripts/vocab_cap.py \
  --unigrams out/unigrams.tsv \
  --model out/model.json \
  --caps 16000 32000 64000 128000 2>&1 | tee out/report.txt
aws s3 cp out/report.txt "s3://${BUCKET}/output/${VARIANT}_vocab_report.txt" --region "$REGION"
echo "finished $(date -Is) vocab ${VARIANT}" | aws s3 cp - "s3://${BUCKET}/status/finished.txt" --region "$REGION"
INSTANCE_ID=$(TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") && curl -sH "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" || shutdown -h now