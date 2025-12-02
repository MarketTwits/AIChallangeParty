#!/bin/bash

# Script to index project documentation (README + docs/) into RAG system
# Day 20 Task - Developer Assistant

set -e

echo "🚀 Indexing Project Documentation for RAG System"
echo "================================================"
echo ""

# Check if server is running
echo "📡 Checking if server is running on localhost:8080..."
if ! curl -s http://localhost:8080/health > /dev/null 2>&1; then
    echo "❌ Server is not running!"
    echo "   Please start the server first: ./gradlew run"
    exit 1
fi
echo "✅ Server is running"
echo ""

# Check RAG status
echo "🔍 Checking RAG system status..."
curl -s http://localhost:8080/rag/status | jq '.' || echo "⚠️  Could not get RAG status"
echo ""

# Create data directory if it doesn't exist
echo "📁 Preparing data directories..."
mkdir -p data/project_docs

# Copy README.md
echo "📄 Copying README.md..."
cp README.md data/project_docs/ 2>/dev/null || echo "⚠️  README.md not found"

# Copy docs folder
echo "📚 Copying docs/ folder..."
if [ -d "docs" ]; then
    cp -r docs/* data/project_docs/ 2>/dev/null || echo "⚠️  No files in docs/"
else
    echo "⚠️  docs/ folder not found"
fi

# Copy CLAUDE.md if exists (project instructions)
echo "📝 Copying CLAUDE.md (project instructions)..."
cp CLAUDE.md data/project_docs/ 2>/dev/null || echo "ℹ️  CLAUDE.md not found (optional)"

echo ""
echo "📦 Files to be indexed:"
find data/project_docs -type f | while read file; do
    echo "   - $file"
done
echo ""

# Build knowledge base
echo "🔨 Building knowledge base from project documentation..."
echo "   This may take a few minutes..."
echo ""

# Call the RAG build endpoint with project_docs folder
curl -X POST http://localhost:8080/rag/build-knowledge-base \
  -H "Content-Type: application/json" \
  -d '{"path": "data/project_docs"}' \
  2>/dev/null || true

echo ""
echo "⏳ Waiting for indexing to complete..."
sleep 5

# Check progress
for i in {1..20}; do
    echo ""
    echo "=== Progress Check $i ==="
    PROGRESS=$(curl -s http://localhost:8080/rag/progress | jq -r '.status, .progressPercent, .currentStep' 2>/dev/null || echo "unknown")
    echo "$PROGRESS"

    STATUS=$(echo "$PROGRESS" | head -1)
    if [ "$STATUS" = "completed" ]; then
        echo ""
        echo "✅ Indexing completed successfully!"
        break
    fi

    if [ $i -lt 20 ]; then
        sleep 10
    fi
done

echo ""
echo "📊 Final RAG Statistics:"
curl -s http://localhost:8080/rag/stats | jq '.' || echo "Could not retrieve stats"

echo ""
echo "================================================"
echo "✅ Project Documentation Indexing Complete!"
echo ""
echo "🎯 Now you can:"
echo "   1. Use /help endpoint to ask questions about the project"
echo "   2. Query documentation via POST /docs/search"
echo "   3. Get git status via GET /git/status"
echo "   4. Test tools via POST /tools/execute"
echo ""
echo "Example queries:"
echo "   curl -X POST http://localhost:8080/help \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"question\": \"How do I setup the project?\"}'"
echo ""
echo "   curl http://localhost:8080/git/status"
echo ""
