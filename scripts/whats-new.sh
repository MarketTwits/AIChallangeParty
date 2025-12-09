#!/bin/bash

# Generate "What's new" notes for the current PR/branch and save them locally.
# Usage: ./scripts/whats-new.sh <base_branch> [head_branch] [server_url]

set -euo pipefail

BASE_BRANCH="${1:-main}"
HEAD_BRANCH="${2:-$(git branch --show-current)}"
SERVER_URL="${3:-http://localhost:8080}"

echo "🚀 Generating What's New notes"
echo "Base: $BASE_BRANCH"
echo "Head: $HEAD_BRANCH"
echo "Server: $SERVER_URL"
echo ""

# Optional title from latest commit on head branch
PR_TITLE=$(git log -1 --pretty=%s || echo "")

PAYLOAD=$(cat <<EOF
{
  "baseBranch": "$BASE_BRANCH",
  "headBranch": "$HEAD_BRANCH",
  "prTitle": "$PR_TITLE",
  "includeFileContent": false
}
EOF
)

ID=$(curl -s -X POST "$SERVER_URL/whats-new/generate" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" | jq -r '.id')

if [ -z "$ID" ] || [ "$ID" = "null" ]; then
  echo "❌ Failed to start generation"
  exit 1
fi

echo "✅ Job started: $ID"
echo "⏳ Waiting for completion..."

ATTEMPTS=0
MAX_ATTEMPTS=60
SLEEP_SECONDS=3

while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
  ATTEMPTS=$((ATTEMPTS + 1))
  STATUS=$(curl -s "$SERVER_URL/whats-new/status/$ID" | jq -r '.status')
  echo "  Status: $STATUS ($ATTEMPTS/$MAX_ATTEMPTS)"

  if [ "$STATUS" = "COMPLETED" ]; then
    break
  fi

  if [ "$STATUS" = "FAILED" ]; then
    echo "❌ Generation failed"
    exit 1
  fi

  sleep $SLEEP_SECONDS
done

if [ "$STATUS" != "COMPLETED" ]; then
  echo "❌ Timed out waiting for generation"
  exit 1
fi

RESULT=$(curl -s "$SERVER_URL/whats-new/result/$ID")

mkdir -p data/whats_new
echo "$RESULT" | jq -r '.markdown' > data/whats_new/latest.cli.md
echo "$RESULT" | jq '.' > data/whats_new/latest.cli.json

echo ""
echo "✅ What's New generated and saved to:"
echo "   - data/whats_new/latest.cli.md"
echo "   - data/whats_new/latest.cli.json"
