#!/bin/bash

# Local build and save script
# Builds Docker image for AMD64 (VPS) and saves it as a tar.gz file

set -e

IMAGE_NAME="ai-running-coach"
OUTPUT_FILE="ai-running-coach.tar.gz"

echo "🔨 Building Docker image for AMD64 platform..."
docker build --platform linux/amd64 -t ${IMAGE_NAME}:latest .

echo "💾 Saving image to ${OUTPUT_FILE}..."
docker save ${IMAGE_NAME}:latest | gzip > ${OUTPUT_FILE}

echo "✅ Done!"
echo ""
echo "Image saved to: ${OUTPUT_FILE}"
echo "File size: $(du -h ${OUTPUT_FILE} | cut -f1)"
echo ""
echo "To deploy on VPS:"
echo "1. Upload ${OUTPUT_FILE} to your VPS: scp ${OUTPUT_FILE} root@your-vps:~/ai-coauch/"
echo "2. SSH to VPS and run: gunzip -c ${OUTPUT_FILE} | docker load"
echo "3. Run: docker compose up -d"
