#!/bin/bash
# Monitor the n-gram extraction AWS job (spot t3.xlarge, ap-south-1).
# Runs via cron every 30 min.
#   - if status/finished.txt exists  -> job done, nothing to do
#   - if no started.txt yet          -> still booting, nothing to do
#   - if started > 3h without finish -> terminate the running job instance (kill switch)
set -uo pipefail

REGION="ap-south-1"
BUCKET="codekeyboard-ngrams-790762402508"
LOG="$HOME/.codekeyboard-ngrams-monitor.log"
TIMEOUT_SECONDS=$((3 * 3600))   # 3 hours

export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

log() { echo "$(date +%Y-%m-%dT%H:%M:%S%z) $*" >> "$LOG"; }

# 1. Finished?
if aws s3 ls "s3://${BUCKET}/status/finished.txt" --region "$REGION" >/dev/null 2>&1; then
  log "JOB DONE — finished.txt present."
  exit 0
fi

# 2. Started yet?
STARTED=$(aws s3 ls "s3://${BUCKET}/status/started.txt" --region "$REGION" 2>/dev/null)
if [ -z "$STARTED" ]; then
  log "WAIT — no started.txt yet."
  exit 0
fi

# 3. Elapsed since started
START_STR=$(echo "$STARTED" | awk '{print $1, $2}')
START_EPOCH=$(date -j -f "%Y-%m-%d %H:%M:%S" "$START_STR" +%s 2>/dev/null)
if [ -z "$START_EPOCH" ]; then
  log "ERROR — cannot parse started timestamp: $START_STR"
  exit 1
fi
ELAPSED=$(( $(date +%s) - START_EPOCH ))

if [ "$ELAPSED" -gt "$TIMEOUT_SECONDS" ]; then
  log "KILL — elapsed ${ELAPSED}s > 3h without finished.txt. Terminating job instances."
  IDS=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=instance-state-name,Values=running" \
              "Name=instance-type,Values=t3.xlarge" \
              "Name=instance-lifecycle,Values=spot" \
    --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null | tr '\t' '\n')
  for iid in $IDS; do
    [ -n "$iid" ] && aws ec2 terminate-instances --instance-ids "$iid" --region "$REGION" >/dev/null 2>&1 && log "terminated $iid"
  done
  echo "killed $(date -Is) elapsed=${ELAPSED}s" | aws s3 cp - "s3://${BUCKET}/status/killed.txt" --region "$REGION" >/dev/null 2>&1
  log "KILL DONE — killed.txt written."
else
  log "RUN — elapsed ${ELAPSED}s / ${TIMEOUT_SECONDS}s."
fi
