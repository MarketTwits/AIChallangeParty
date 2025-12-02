#!/bin/bash

# RAG Progress Monitor Script
# Shows real-time progress of RAG knowledge base building

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

clear_screen() {
    clear
}

format_time() {
    local seconds=$1
    local mins=$((seconds / 60))
    local secs=$((seconds % 60))
    printf "%02d:%02d" $mins $secs
}

draw_progress_bar() {
    local percent=$1
    local filled=$((percent / 5))
    local empty=$((20 - filled))

    printf "["
    printf "█%.0s" $(seq 1 $filled)
    printf "░%.0s" $(seq 1 $empty)
    printf "] %3d%%" $percent
}

print_progress() {
    local status=$1
    local step=$2
    local percent=$3
    local processed=$4
    local total=$5
    local elapsed=$6
    local eta=$7
    local error=$8

    echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║          RAG Knowledge Base Building Progress              ║${NC}"
    echo -e "${BLUE}╠════════════════════════════════════════════════════════════╣${NC}"

    # Status
    case $status in
        "completed")
            echo -e "${GREEN}║ Status: ✅ COMPLETED                                       ║${NC}"
            ;;
        "error")
            echo -e "${RED}║ Status: ❌ ERROR                                           ║${NC}"
            ;;
        "embedding")
            echo -e "${CYAN}║ Status: ⚡ GENERATING EMBEDDINGS (this takes time)        ║${NC}"
            ;;
        "saving")
            echo -e "${YELLOW}║ Status: 💾 SAVING TO DATABASE                             ║${NC}"
            ;;
        "chunking")
            echo -e "${YELLOW}║ Status: ✂️  CHUNKING DOCUMENTS                             ║${NC}"
            ;;
        "loading")
            echo -e "${YELLOW}║ Status: 📄 LOADING DOCUMENTS                              ║${NC}"
            ;;
        *)
            echo -e "║ Status: $status                                              ║"
            ;;
    esac

    echo -e "║ Step: ${step:0:58}$(printf ' %.0s' {1..58} | cut -c1-$((58-${#step})))║"

    # Progress bar
    echo -n "║ Progress: "
    draw_progress_bar $percent
    echo "                       ║"

    # Chunks
    echo -e "║ Chunks: ${processed}/${total}${NC}$(printf ' %.0s' {1..50} | cut -c1-$((50-${#processed}-${#total}-2)))║"

    # Time
    echo -e "║ Time: $(format_time $elapsed) | ETA: $(format_time $eta)$(printf ' %.0s' {1..34} | cut -c1-34)║"

    # Error if present
    if [ ! -z "$error" ] && [ "$error" != "null" ]; then
        echo -e "${RED}║ ERROR: ${error:0:54}$(printf ' %.0s' {1..54} | cut -c1-$((54-${#error})))║${NC}"
    fi

    echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
}

main() {
    echo -e "${BLUE}Starting RAG Knowledge Base Build...${NC}"
    echo ""
    sleep 1

    # Start building
    echo -e "${CYAN}Sending build request...${NC}"
    curl -s -X POST http://localhost:8080/rag/build-knowledge-base > /dev/null 2>&1

    echo -e "${GREEN}✅ Build started! Monitoring progress...${NC}"
    echo ""
    sleep 2

    # Monitor progress
    local last_status=""
    local update_count=0

    while true; do
        clear_screen

        # Get current progress
        local response=$(curl -s http://localhost:8080/rag/progress 2>/dev/null)

        if [ $? -ne 0 ] || [ -z "$response" ]; then
            echo -e "${RED}Error: Cannot connect to server at http://localhost:8080${NC}"
            echo -e "${YELLOW}Make sure the application is running:${NC}"
            echo "  ./gradlew run"
            exit 1
        fi

        # Parse JSON response
        local status=$(echo "$response" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
        local current_step=$(echo "$response" | grep -o '"currentStep":"[^"]*"' | head -1 | cut -d'"' -f4)
        local progress_percent=$(echo "$response" | grep -o '"progressPercent":[0-9]*' | head -1 | cut -d':' -f2)
        local processed=$(echo "$response" | grep -o '"processedChunks":[0-9]*' | head -1 | cut -d':' -f2)
        local total=$(echo "$response" | grep -o '"totalChunks":[0-9]*' | head -1 | cut -d':' -f2)
        local elapsed=$(echo "$response" | grep -o '"elapsedSeconds":[0-9]*' | head -1 | cut -d':' -f2)
        local eta=$(echo "$response" | grep -o '"estimatedRemainingSeconds":[0-9]*' | head -1 | cut -d':' -f2)
        local error=$(echo "$response" | grep -o '"errorMessage":"[^"]*"' | head -1 | cut -d'"' -f4)

        # Default values
        progress_percent=${progress_percent:-0}
        processed=${processed:-0}
        total=${total:-1}
        elapsed=${elapsed:-0}
        eta=${eta:-0}

        # Print progress
        print_progress "$status" "$current_step" "$progress_percent" "$processed" "$total" "$elapsed" "$eta" "$error"

        # Exit if completed or error
        if [ "$status" = "completed" ] || [ "$status" = "error" ]; then
            echo ""
            if [ "$status" = "completed" ]; then
                echo -e "${GREEN}✅ Build completed successfully!${NC}"
                echo -e "${CYAN}Knowledge base is ready for use.${NC}"
                echo ""
                echo -e "${YELLOW}Next steps:${NC}"
                echo "  1. Check /rag/stats for statistics"
                echo "  2. Try a search: POST /rag/search with {\"query\": \"...\"}"
            else
                echo -e "${RED}❌ Build failed!${NC}"
                echo -e "${YELLOW}Error: $error${NC}"
            fi
            echo ""
            break
        fi

        # Update every second
        sleep 1
        ((update_count++))
    done
}

main
