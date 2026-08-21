#!/bin/bash
# Cap-followers sweep (generic): score <VARIANT> DBs at a single --max-followers
# cap, upload <variant>_tri_cap<CAP>.json to S3, terminate. One instance per cap.
# Generate instances via scripts/gen_sweep_instances.sh; this is the template.
set -euxo pipefail
exec > >(tee /var/log/ngrams-sweep.log) 2>&1
dnf install -y python3.11 python3.11-pip awscli || dnf install -y python3 awscli
BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
VARIANT="__VARIANT__"
CAP="__CAP__"
WORK=/opt/ngrams
mkdir -p "$WORK"/{scripts,out,work,input}
cd "$WORK"
if [ "$VARIANT" = "kn" ]; then
  S3_SCRIPT="build_ngrams.py"
  DB_PREFIX="kn"
  EXTRA=""
elif [ "$VARIANT" = "katz" ]; then
  S3_SCRIPT="build_ngrams_katz.py"
  DB_PREFIX="katz"
  EXTRA=""
else
  S3_SCRIPT="build_ngrams_swiftkey.py"
  DB_PREFIX="swiftkey"
  EXTRA="--delta 1e-6"
fi
aws s3 cp "s3://${BUCKET}/scripts/${S3_SCRIPT}" scripts/ --region "$REGION"
aws s3 cp "s3://${BUCKET}/output/${DB_PREFIX}_counts.sqlite" work/counts.sqlite --region "$REGION"
aws s3 cp "s3://${BUCKET}/output/${DB_PREFIX}_normalized.sqlite" work/normalized.sqlite --region "$REGION"
touch input/dummy.txt
echo "started $(date -Is) ${VARIANT} cap=$CAP" | aws s3 cp - "s3://${BUCKET}/status/started.txt" --region "$REGION"
# shellcheck disable=SC2086
/usr/bin/python3 "scripts/${S3_SCRIPT}" \
  --input input/dummy.txt \
  --output "out/${VARIANT}_tri_cap${CAP}.json" \
  --output-bigrams "" \
  --output-bigrams-support "" \
  --order 3 \
  --work-dir work \
  --workers 4 \
  --max-followers "$CAP" \
  $EXTRA \
  --stage score \
  2>&1 | tee out/build.log
aws s3 cp out/build.log "s3://${BUCKET}/output/${VARIANT}_cap${CAP}.log" --region "$REGION"
aws s3 cp "out/${VARIANT}_tri_cap${CAP}.json" "s3://${BUCKET}/output/${VARIANT}_tri_cap${CAP}.json" --region "$REGION"
echo "finished $(date -Is) ${VARIANT} cap=$CAP" | aws s3 cp - "s3://${BUCKET}/status/finished.txt" --region "$REGION"
INSTANCE_ID=$(TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") && curl -sH "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" || shutdown -h now
