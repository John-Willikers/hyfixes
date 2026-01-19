#!/bin/bash
# Generate changelog from commits using OpenRouter API
# Requires: OPENROUTER_API_KEY and OPENROUTER_MODEL environment variables

set -e

# Get the last tag (excluding the current one)
LAST_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")

# Get commits since last tag, or all commits if no previous tag
if [ -z "$LAST_TAG" ]; then
  echo "No previous tag found, using recent commits" >&2
  COMMITS=$(git log --oneline --no-merges -50)
else
  echo "Getting commits since $LAST_TAG" >&2
  COMMITS=$(git log --oneline --no-merges ${LAST_TAG}..HEAD)
fi

# If no commits found, provide default message
if [ -z "$COMMITS" ]; then
  echo "## Changes"
  echo ""
  echo "- Minor updates and improvements"
  exit 0
fi

# Fallback function - format commits as changelog
fallback_changelog() {
  echo "## Changes"
  echo ""
  echo "$COMMITS" | while read -r line; do
    # Remove commit hash prefix (first word)
    echo "- ${line#* }"
  done
}

# Check if API key is set
if [ -z "$OPENROUTER_API_KEY" ]; then
  echo "Warning: OPENROUTER_API_KEY not set, using commit list as changelog" >&2
  fallback_changelog
  exit 0
fi

# Default model if not specified
MODEL="${OPENROUTER_MODEL:-anthropic/claude-sonnet-4}"

# Create a temporary file for the JSON payload
PAYLOAD_FILE=$(mktemp)
trap "rm -f $PAYLOAD_FILE" EXIT

# Build the prompt - include version info for context
RELEASE_VER="${RELEASE_VERSION:-UNKNOWN}"
PREV_VER="${LAST_TAG:-initial}"

PROMPT="Generate a changelog for HyFixes v${RELEASE_VER} (a Hytale server plugin).

Rules:
- Do NOT include a title/header with version number (that's handled separately)
- Start directly with the changes grouped by category
- Use sections: ## Features, ## Fixes, ## Changes (only include sections that have items)
- Be concise and user-friendly
- Focus on what changed from the user's perspective, not commit hashes
- If changes span multiple previous versions, group them by version with ### v1.X.X subheaders

Changes since ${PREV_VER}:

$COMMITS"

# Use jq to properly construct the JSON payload
jq -n \
  --arg model "$MODEL" \
  --arg prompt "$PROMPT" \
  '{
    model: $model,
    max_tokens: 1024,
    messages: [{
      role: "user",
      content: $prompt
    }]
  }' > "$PAYLOAD_FILE"

# Call OpenRouter API (OpenAI-compatible format)
RESPONSE=$(curl -s https://openrouter.ai/api/v1/chat/completions \
  -H "Authorization: Bearer $OPENROUTER_API_KEY" \
  -H "HTTP-Referer: https://github.com/John-Willikers/hyfixes" \
  -H "X-Title: HyFixes Release Changelog" \
  -H "Content-Type: application/json" \
  -d @"$PAYLOAD_FILE")

# Extract the text content from the response (OpenAI format)
CHANGELOG=$(echo "$RESPONSE" | jq -r '.choices[0].message.content // empty')

# Check if we got a valid response
if [ -z "$CHANGELOG" ]; then
  echo "Warning: Failed to generate changelog from API, using fallback" >&2
  # Check for error message
  ERROR=$(echo "$RESPONSE" | jq -r '.error.message // empty')
  if [ -n "$ERROR" ]; then
    echo "API Error: $ERROR" >&2
  else
    echo "Raw response: $RESPONSE" >&2
  fi
  fallback_changelog
  exit 0
fi

echo "$CHANGELOG"
