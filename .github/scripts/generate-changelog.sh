#!/bin/bash
# Generate changelog from commits using Claude API
# Requires: ANTHROPIC_API_KEY environment variable

set -e

# Get the last tag (excluding the current one)
LAST_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")

# Get commits since last tag, or all commits if no previous tag
if [ -z "$LAST_TAG" ]; then
  echo "No previous tag found, using all commits" >&2
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

# Escape commits for JSON (handle newlines, quotes, backslashes)
COMMITS_ESCAPED=$(echo "$COMMITS" | jq -Rs .)

# Check if API key is set
if [ -z "$ANTHROPIC_API_KEY" ]; then
  echo "Warning: ANTHROPIC_API_KEY not set, using commit list as changelog" >&2
  echo "## Changes"
  echo ""
  echo "$COMMITS" | while read -r line; do
    echo "- $line"
  done
  exit 0
fi

# Call Claude API to generate changelog
RESPONSE=$(curl -s https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d "{
    \"model\": \"claude-sonnet-4-20250514\",
    \"max_tokens\": 1024,
    \"messages\": [{
      \"role\": \"user\",
      \"content\": \"Generate a changelog for a Hytale server plugin release called HyFixes. Format as markdown with sections for Features, Fixes, and Changes as needed. Be concise and user-friendly. Focus on what changed, not commit hashes. If there are no commits in a category, omit that section. Here are the commits since last release:\\n\\n${COMMITS_ESCAPED}\"
    }]
  }")

# Extract the text content from the response
CHANGELOG=$(echo "$RESPONSE" | jq -r '.content[0].text // empty')

# Check if we got a valid response
if [ -z "$CHANGELOG" ]; then
  echo "Warning: Failed to generate changelog from API, using fallback" >&2
  echo "## Changes"
  echo ""
  echo "$COMMITS" | while read -r line; do
    echo "- ${line#* }"  # Remove commit hash prefix
  done
  exit 0
fi

echo "$CHANGELOG"
