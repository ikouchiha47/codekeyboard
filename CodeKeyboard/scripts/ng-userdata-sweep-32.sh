#!/bin/bash
# Cap-followers sweep: score the swiftkey DBs at a single --max-followers cap,
# upload tri_<CAP>.json to S3, terminate. Each instance handles exactly one cap.
set -euxo pipefail
exec > >(tee /var/log/ngrams-sweep.log) 2>&1
dnf install -y python3.11 python3.11-pip awscli || dnf install -y python3 awscli
BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
CAP="32"
WORK=/opt/ngrams
mkdir -p "$WORK"/{scripts,out,work,input}
cd "$WORK"
aws s3 cp "s3://${BUCKET}/scripts/build_ngrams_swiftkey.py" scripts/ --region "$REGION"
# Reuse the swiftkey DBs already on S3 (byte-identical to katz/kn counts).
aws s3 cp "s3://${BUCKET}/output/swiftkey_counts.sqlite" work/counts.sqlite --region "$REGION"
aws s3 cp "s3://${BUCKET}/output/swiftkey_normalized.sqlite" work/normalized.sqlite --region "$REGION"
touch input/dummy.txt
echo "started $(date -Is) sweep cap=$CAP" | aws s3 cp - "s3://${BUCKET}/status/started.txt" --region "$REGION"
/usr/bin/python3 scripts/build_ngrams_swiftkey.py \
  --input input/dummy.txt \
  --output out/swiftkey_tri_cap${CAP}.json \
  --output-bigrams "" \
  --output-bigrams-support "" \
  --order 3 \
  --work-dir work \
  --workers 4 \
  --max-followers "$CAP" \
  --delta 1e-6 \
  --stage score \
  2>&1 | tee out/build.log
aws s3 cp out/build.log "s3://${BUCKET}/output/swiftkey_cap${CAP}.log" --region "$REGION"
aws s3 cp out/swiftkey_tri_cap${CAP}.json "s3://${BUCKET}/output/swiftkey_tri_cap${CAP}.json" --region "$REGION"
echo "finished $(date -Is) sweep cap=$CAP" | aws s3 cp - "s3://${BUCKET}/status/finished.txt" --region "$REGION"
INSTANCE_ID=$(TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") && curl -sH "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" || shutdown -h now
