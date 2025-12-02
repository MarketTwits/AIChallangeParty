#!/bin/bash

# Script to trigger code review from CI/CD
# Usage: ./trigger-review.sh <base_branch> <head_branch> [server_url]

set -e

BASE_BRANCH="${1:-main}"
HEAD_BRANCH="${2:-$(git branch --show-current)}"
SERVER_URL="${3:-http://localhost:8080}"

echo "🔍 Triggering Code Review"
echo "================================"
echo "Base Branch: $BASE_BRANCH"
echo "Head Branch: $HEAD_BRANCH"
echo "Server URL: $SERVER_URL"
echo ""

# Check if server is running
echo "⏳ Checking server status..."
if ! curl -sf "${SERVER_URL}/tools/status" > /dev/null 2>&1; then
    echo "❌ Server is not running at $SERVER_URL"
    echo "💡 Please start the server first: ./gradlew run"
    exit 1
fi
echo "✅ Server is running"
echo ""

# Start code review
echo "🚀 Starting code review..."
RESPONSE=$(curl -s -X POST "${SERVER_URL}/code-review/analyze" \
    -H "Content-Type: application/json" \
    -d "{
        \"baseBranch\": \"${BASE_BRANCH}\",
        \"headBranch\": \"${HEAD_BRANCH}\",
        \"analysisTypes\": [\"general\", \"security\", \"performance\", \"best_practices\"],
        \"includeFileContent\": false
    }")

REVIEW_ID=$(echo "$RESPONSE" | jq -r '.reviewId')

if [ -z "$REVIEW_ID" ] || [ "$REVIEW_ID" = "null" ]; then
    echo "❌ Failed to start review"
    echo "Response: $RESPONSE"
    exit 1
fi

echo "✅ Review started"
echo "Review ID: $REVIEW_ID"
echo ""

# Poll for completion
echo "⏳ Waiting for review to complete..."
MAX_ATTEMPTS=60
ATTEMPT=0

while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    ATTEMPT=$((ATTEMPT + 1))

    STATUS_RESPONSE=$(curl -s "${SERVER_URL}/code-review/status/${REVIEW_ID}")
    STATUS=$(echo "$STATUS_RESPONSE" | jq -r '.status')

    echo "  Status: $STATUS ($ATTEMPT/$MAX_ATTEMPTS)"

    if [ "$STATUS" = "COMPLETED" ]; then
        echo ""
        echo "✅ Review completed!"
        break
    elif [ "$STATUS" = "FAILED" ]; then
        echo ""
        echo "❌ Review failed!"
        ERROR=$(echo "$STATUS_RESPONSE" | jq -r '.error')
        echo "Error: $ERROR"
        exit 1
    fi

    if [ $ATTEMPT -eq $MAX_ATTEMPTS ]; then
        echo ""
        echo "❌ Review timed out after $MAX_ATTEMPTS attempts"
        exit 1
    fi

    sleep 5
done

echo ""
echo "📊 Fetching review results..."

# Get full result
RESULT=$(curl -s "${SERVER_URL}/code-review/result/${REVIEW_ID}")

# Display summary
echo ""
echo "================================"
echo "📈 REVIEW SUMMARY"
echo "================================"
TOTAL_FINDINGS=$(echo "$RESULT" | jq -r '.summary.totalFindings')
FILES_ANALYZED=$(echo "$RESULT" | jq -r '.summary.filesAnalyzed')
LINES_CHANGED=$(echo "$RESULT" | jq -r '.summary.linesChanged')

echo "Total Findings: $TOTAL_FINDINGS"
echo "Files Analyzed: $FILES_ANALYZED"
echo "Lines Changed: $LINES_CHANGED"
echo ""

# Display findings by severity
echo "By Severity:"
echo "$RESULT" | jq -r '.summary.bySeverity | to_entries[] | "  \(.key): \(.value)"'
echo ""

# Display critical and high severity findings
CRITICAL=$(echo "$RESULT" | jq '[.findings[] | select(.severity == "CRITICAL")]')
HIGH=$(echo "$RESULT" | jq '[.findings[] | select(.severity == "HIGH")]')

CRITICAL_COUNT=$(echo "$CRITICAL" | jq 'length')
HIGH_COUNT=$(echo "$HIGH" | jq 'length')

if [ "$CRITICAL_COUNT" -gt 0 ]; then
    echo "🔴 CRITICAL ISSUES ($CRITICAL_COUNT):"
    echo "$CRITICAL" | jq -r '.[] | "  - [\(.file):\(.line)] \(.title)"'
    echo ""
fi

if [ "$HIGH_COUNT" -gt 0 ]; then
    echo "🟠 HIGH SEVERITY ISSUES ($HIGH_COUNT):"
    echo "$HIGH" | jq -r '.[] | "  - [\(.file):\(.line)] \(.title)"'
    echo ""
fi

# Save markdown report
echo "💾 Saving markdown report..."
MARKDOWN=$(curl -s "${SERVER_URL}/code-review/result/${REVIEW_ID}/markdown")
REPORT_FILE="code-review-${REVIEW_ID}.md"
echo "$MARKDOWN" > "$REPORT_FILE"
echo "✅ Report saved to: $REPORT_FILE"
echo ""

# Exit with error if critical or high issues found
if [ "$CRITICAL_COUNT" -gt 0 ] || [ "$HIGH_COUNT" -gt 0 ]; then
    echo "⚠️  Review found critical or high severity issues"
    echo "📄 Full report: $REPORT_FILE"
    echo ""
    exit 1
fi

echo "✅ Code review passed!"
echo "📄 Full report: $REPORT_FILE"
echo ""
