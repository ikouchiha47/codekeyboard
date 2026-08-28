#!/bin/bash
# Reusable AWS n-gram build runner.
# Usage:
#   aws_ngrams.sh upload [katz]   # upload build_ngrams.py (or _katz) to S3
#   aws_ngrams.sh instances       # list running ngrams-build instances
#   aws_ngrams.sh launch [katz]   # launch spot instance (user-data runs the build)
#   aws_ngrams.sh wait [ID]       # wait until instance is running + build started
#   aws_ngrams.sh status [ID]     # instance state + S3 status/output files
set -euo pipefail

BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
AMI="ami-0d15e9052c94acb75"
INSTANCE_TYPE="t3.xlarge"
SUBNET="subnet-03d4d8602d82d12fb"
SG="sg-05ae0ba999ad32a23"
IAM_PROFILE="codekeyboard-ngrams-ec2"
TAG="Name=ngrams-build"

cmd="${1:-help}"
variant="${2:-}"

# Variant selects its own script + user-data; outputs are prefixed so they
# never overwrite the KN build's files on S3 (see build_ngrams_katz.py).
if [ "$variant" = "katz" ]; then
  SCRIPT="$(cd "$(dirname "$0")" && pwd)/build_ngrams_katz.py"
  USERDATA="$(cd "$(dirname "$0")" && pwd)/ng-userdata-katz.sh"
  S3_SCRIPT="build_ngrams_katz.py"
elif [ "$variant" = "swiftkey" ]; then
  SCRIPT="$(cd "$(dirname "$0")" && pwd)/build_ngrams_swiftkey.py"
  USERDATA="$(cd "$(dirname "$0")" && pwd)/ng-userdata-swiftkey.sh"
  S3_SCRIPT="build_ngrams_swiftkey.py"
elif [ "$variant" = "4gram-full" ]; then
  SCRIPT="$(cd "$(dirname "$0")" && pwd)/build_ngrams.py"
  USERDATA="$(cd "$(dirname "$0")" && pwd)/ng-userdata-4gram-full.sh"
  S3_SCRIPT="build_ngrams.py"
  INSTANCE_TYPE="c5.4xlarge"
else
  SCRIPT="$(cd "$(dirname "$0")" && pwd)/build_ngrams.py"
  USERDATA="$(cd "$(dirname "$0")" && pwd)/ng-userdata-fast.sh"
  S3_SCRIPT="build_ngrams.py"
fi

upload() {
  [ -f "$SCRIPT" ] || { echo "missing $SCRIPT" >&2; exit 1; }
  aws s3 cp "$SCRIPT" "s3://${BUCKET}/scripts/${S3_SCRIPT}" --region "$REGION"
  echo "uploaded ${S3_SCRIPT}"
}

instances() {
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=ngrams-build" \
    --query 'Reservations[].Instances[].{Id:InstanceId,State:State.Name,IP:PublicIpAddress,Spot:InstanceLifecycle}' \
    --output table
}

launch() {
  [ -f "$USERDATA" ] || { echo "missing $USERDATA" >&2; exit 1; }
  aws s3 rm "s3://${BUCKET}/status/started.txt" --region "$REGION" >/dev/null 2>&1 || true
  aws s3 rm "s3://${BUCKET}/status/finished.txt" --region "$REGION" >/dev/null 2>&1 || true
  aws ec2 run-instances --region "$REGION" \
    --image-id "$AMI" \
    --instance-type "$INSTANCE_TYPE" \
    --subnet-id "$SUBNET" \
    --security-group-ids "$SG" \
    --iam-instance-profile "Name=${IAM_PROFILE}" \
    --instance-market-options '{"MarketType":"spot","SpotOptions":{"SpotInstanceType":"one-time","InstanceInterruptionBehavior":"terminate"}}' \
    --user-data "file://${USERDATA}" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=ngrams-build}]" \
    --query 'Instances[0].{Id:InstanceId,State:State.Name,Spot:InstanceLifecycle}' \
    --output json
}

wait_launch() {
  local id="${1:-}"
  if [ -z "$id" ]; then
    id=$(aws ec2 describe-instances --region "$REGION" \
      --filters "Name=tag:Name,Values=ngrams-build" "Name=instance-state-name,Values=pending,running" \
      --query 'Reservations[0].Instances[0].InstanceId' --output text)
  fi
  [ "$id" != "None" ] || { echo "no instance found" >&2; exit 1; }
  echo "waiting for $id to reach running..."
  aws ec2 wait instance-running --instance-ids "$id" --region "$REGION"
  echo "instance running: $id"
  echo "waiting for build to start (started.txt)..."
  for i in $(seq 1 30); do
    if aws s3 ls "s3://${BUCKET}/status/started.txt" --region "$REGION" >/dev/null 2>&1; then
      echo "build started: $(aws s3 cp "s3://${BUCKET}/status/started.txt" - --region "$REGION" 2>/dev/null)"
      return 0
    fi
    sleep 10
  done
  echo "timed out waiting for started.txt" >&2
  return 1
}

status() {
  local id="${1:-}"
  if [ -z "$id" ]; then
    id=$(aws ec2 describe-instances --region "$REGION" \
      --filters "Name=tag:Name,Values=ngrams-build" "Name=instance-state-name,Values=pending,running" \
      --query 'Reservations[0].Instances[0].InstanceId' --output text)
  fi
  echo "--- instance ---"
  if [ "$id" != "None" ]; then
    aws ec2 describe-instances --instance-ids "$id" --region "$REGION" \
      --query 'Reservations[0].Instances[0].{Id:InstanceId,State:State.Name,IP:PublicIpAddress}' --output table
  else
    echo "no running instance"
  fi
  echo "--- S3 status ---"
  aws s3 ls "s3://${BUCKET}/status/" --region "$REGION" || true
  echo "--- S3 output ---"
  aws s3 ls "s3://${BUCKET}/output/" --region "$REGION" || true
}

case "$cmd" in
  upload)    upload ;;
  instances) instances ;;
  launch)    launch ;;
  wait)      wait_launch "${2:-}" ;;
  status)    status "${2:-}" ;;
  *)
    echo "usage: $0 {upload|instances|launch|wait [ID]|status [ID]}" >&2
    exit 1
    ;;
esac