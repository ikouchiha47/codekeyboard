#!/bin/bash
# Cap-sweep orchestration for the n-gram follower cap experiment.
#
# Generates per-variant/per-cap user-data files from the templates
#   ng-userdata-sweep.sh     (score a variant's DBs at one --max-followers cap)
#   ng-userdata-analyze.sh   (compare a variant's cap JSONs on-instance)
# then launches the instances and reports their IDs.
#
# Usage:
#   gen_sweep_instances.sh all        # kn+katz sweeps, swiftkey analyze (caps exist)
#   gen_sweep_instances.sh sweep VAR  # kn|katz|swiftkey: 4 cap instances
#   gen_sweep_instances.sh analyze VAR # kn|katz|swiftkey: 1 analyze instance
#   gen_sweep_instances.sh upload     # upload compare_ngrams.py + templates to S3
set -euo pipefail

BUCKET="codekeyboard-ngrams-790762402508"
REGION="ap-south-1"
AMI="ami-0d15e9052c94acb75"
INSTANCE_TYPE="t3.xlarge"
SUBNET="subnet-03d4d8602d82d12fb"
SG="sg-05ae0ba999ad32a23"
IAM_PROFILE="codekeyboard-ngrams-ec2"
DIR="$(cd "$(dirname "$0")" && pwd)"
CAPS="10 16 32 64"

launch_one() {
  local userdata="$1" tag="$2"
  aws ec2 run-instances --region "$REGION" \
    --image-id "$AMI" \
    --instance-type "$INSTANCE_TYPE" \
    --subnet-id "$SUBNET" \
    --security-group-ids "$SG" \
    --iam-instance-profile "Name=${IAM_PROFILE}" \
    --instance-market-options '{"MarketType":"spot","SpotOptions":{"SpotInstanceType":"one-time","InstanceInterruptionBehavior":"terminate"}}' \
    --user-data "file://${userdata}" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${tag}}]" \
    --query 'Instances[0].{Id:InstanceId,State:State.Name,Spot:InstanceLifecycle}' \
    --output json
}

upload_scripts() {
  aws s3 cp "$DIR/compare_ngrams.py" "s3://${BUCKET}/scripts/compare_ngrams.py" --region "$REGION"
  echo "uploaded compare_ngrams.py"
}

sweep() {
  local variant="$1"
  for cap in $CAPS; do
    local userdata="$DIR/ng-userdata-sweep-${variant}-${cap}.sh"
    sed -e "s/__VARIANT__/${variant}/g" -e "s/__CAP__/${cap}/g" \
        "$DIR/ng-userdata-sweep.sh" > "$userdata"
    echo "--- ${variant} cap=${cap} ---"
    launch_one "$userdata" "ngrams-sweep-${variant}-${cap}"
  done
}

analyze() {
  local variant="$1"
  local userdata="$DIR/ng-userdata-analyze-${variant}.sh"
  sed "s/__VARIANT__/${variant}/g" "$DIR/ng-userdata-analyze.sh" > "$userdata"
  echo "--- analyze ${variant} ---"
  launch_one "$userdata" "ngrams-analyze-${variant}"
}

cmd="${1:-help}"
case "$cmd" in
  all)
    upload_scripts
    sweep kn
    sweep katz
    analyze swiftkey   # swiftkey caps already on S3 from the earlier run
    ;;
  sweep)  sweep "${2:?variant required}" ;;
  analyze) analyze "${2:?variant required}" ;;
  upload) upload_scripts ;;
  *)
    echo "usage: $0 {all|sweep VAR|analyze VAR|upload}" >&2
    exit 1
    ;;
esac
