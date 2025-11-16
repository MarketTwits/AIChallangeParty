#!/bin/bash

# Local build and save script
# Builds Docker image for AMD64 (VPS) and saves it as a tar.gz file

set -e

IMAGE_NAME="ai-running-coach"
OUTPUT_FILE="ai-running-coach.tar.gz"

echo "🧹 Cleaning up previous builds..."
docker rmi ${IMAGE_NAME}:latest 2>/dev/null || true
rm -f ${OUTPUT_FILE}

echo "🔨 Building Docker image for AMD64 platform..."
docker build --platform linux/amd64 --no-cache -t ${IMAGE_NAME}:latest .

echo "✅ Testing image locally..."
docker run --rm --name test-container -p 8081:8080 -e ANTHROPIC_API_KEY=test -e HUGGINGFACE_API_KEY=test ${IMAGE_NAME}:latest &
TEST_PID=$!

# Wait for container to start
echo "⏳ Waiting for health check..."
sleep 15

# Test health endpoint
if curl -f http://localhost:8081/health >/dev/null 2>&1; then
    echo "✅ Health check passed"
else
    echo "❌ Health check failed"
    docker kill test-container 2>/dev/null || true
    exit 1
fi

# Stop test container
docker kill test-container 2>/dev/null || true
docker wait test-container 2>/dev/null || true

echo "💾 Saving image to ${OUTPUT_FILE}..."
docker save ${IMAGE_NAME}:latest | gzip > ${OUTPUT_FILE}

echo "🧹 Cleaning up test container..."
docker rmi ${IMAGE_NAME}:latest 2>/dev/null || true

echo "✅ Done!"
echo ""
echo "Image saved to: ${OUTPUT_FILE}"
echo "File size: $(du -h ${OUTPUT_FILE} | cut -f1)"
echo ""
echo "To deploy on VPS:"
echo "1. Upload ${OUTPUT_FILE} to your VPS: scp ${OUTPUT_FILE} root@your-vps:~/ai-coauch/"
echo "2. Upload docker-compose.yml if changed: scp docker-compose.yml root@your-vps:~/ai-coauch/"
echo "3. SSH to VPS and run:"
echo "   cd ~/ai-coauch"
echo "   docker compose down"
echo "   docker rmi ai-running-coach:latest 2>/dev/null || true"
echo "   gunzip -c ${OUTPUT_FILE} | docker load"
echo "   docker compose up -d"
echo "   docker logs ai-running-coach"
