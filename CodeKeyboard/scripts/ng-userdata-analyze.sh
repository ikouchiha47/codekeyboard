#!/bin/bash
# Cap-sweep analysis (generic): download <VARIANT> cap JSONs + compare_ngrams.py,
# compute pairwise top-1 agreement and size across caps, upload report to S3.
# One instance per variant. Template — generate via scripts/gen_sweep_instances.sh.
set -euxo pipefail
exec > >(tee /var/log/ngrams-analyze.log) 2>&1
dnf install -y python3.11 python3.11-pip awscli || dnf install -y python3 awscli
BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
VARIANT="__VARIANT__"
WORK=/opt/ngrams
mkdir -p "$WORK"/{scripts,out}
cd "$WORK"
aws s3 cp "s3://${BUCKET}/scripts/compare_ngrams.py" scripts/ --region "$REGION"
for cap in 10 16 32 64; do
  aws s3 cp "s3://${BUCKET}/output/${VARIANT}_tri_cap${cap}.json" "out/${VARIANT}_tri_cap${cap}.json" --region "$REGION"
done
echo "started $(date -Is) analyze ${VARIANT}" | aws s3 cp - "s3://${BUCKET}/status/started.txt" --region "$REGION"
{
  echo "=== ${VARIANT} cap sweep analysis ==="
  echo "file sizes:"
  ls -la out/${VARIANT}_tri_cap*.json
  echo
  echo "=== three-way: cap10 vs cap32 vs cap64 (thr=25) ==="
  /usr/bin/python3 scripts/compare_ngrams.py three \
    out/${VARIANT}_tri_cap10.json out/${VARIANT}_tri_cap32.json out/${VARIANT}_tri_cap64.json \
    --thr 25 2>&1 || true
  echo
  echo "=== three-way: cap10 vs cap16 vs cap32 (thr=25) ==="
  /usr/bin/python3 scripts/compare_ngrams.py three \
    out/${VARIANT}_tri_cap10.json out/${VARIANT}_tri_cap16.json out/${VARIANT}_tri_cap32.json \
    --thr 25 2>&1 || true
  echo
  echo "=== pair: cap10 vs cap64 ==="
  /usr/bin/python3 scripts/compare_ngrams.py pair \
    out/${VARIANT}_tri_cap10.json out/${VARIANT}_tri_cap64.json 2>&1 || true
} | tee out/report.txt
aws s3 cp out/report.txt "s3://${BUCKET}/output/${VARIANT}_cap_report.txt" --region "$REGION"
echo "finished $(date -Is) analyze ${VARIANT}" | aws s3 cp - "s3://${BUCKET}/status/finished.txt" --region "$REGION"
INSTANCE_ID=$(TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") && curl -sH "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$REGION" || shutdown -h now
