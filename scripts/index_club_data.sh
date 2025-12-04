#!/bin/bash

# Script to index SportSauce Club data into RAG system
# This prepares the knowledge base for the club support assistant
# This script should be run BEFORE starting the server

echo "🏃 SportSauce Club Data Indexing Script"
echo "========================================"
echo ""

# Get project root (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "📂 Project root: $PROJECT_ROOT"
echo ""

# Check if source files exist
SPORTSAUCE_DIR="$PROJECT_ROOT/data/sportsauce"

if [ ! -d "$SPORTSAUCE_DIR" ]; then
    echo "❌ Source directory not found: $SPORTSAUCE_DIR"
    exit 1
fi

echo "✅ Source directory found: $SPORTSAUCE_DIR"
echo ""

# Check for markdown files
FILE_COUNT=$(find "$SPORTSAUCE_DIR" -name "*.md" | wc -l | tr -d ' ')

if [ "$FILE_COUNT" -eq 0 ]; then
    echo "❌ No markdown files found in $SPORTSAUCE_DIR"
    exit 1
fi

echo "📄 Found $FILE_COUNT markdown files to index"
echo ""

# Run Gradle task for indexing
echo "🚀 Starting indexing process via Gradle..."
echo ""

cd "$PROJECT_ROOT" || exit 1

./gradlew runIndexClubData --no-daemon

EXIT_CODE=$?

echo ""

if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ Indexing completed successfully!"
    echo ""
    echo "🎉 Club data is ready to use!"
    echo "   Start the server: ./gradlew run"
    echo ""
    echo "💡 Test the assistant:"
    echo "   curl -X POST http://localhost:8080/club/chat -H 'Content-Type: application/json' -d '{\"message\": \"Расскажи о клубе\"}'"
else
    echo "❌ Indexing failed with exit code: $EXIT_CODE"
    exit $EXIT_CODE
fi
