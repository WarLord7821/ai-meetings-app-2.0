#!/usr/bin/env bash
# Agent RAG-chat helper (Linux/macOS)
#
# Reads API_BASE from ../.env, then POSTs a natural-language question about the
# user's past meetings, using the JWT captured from a prior successful login.
# The backend embeds the question, retrieves the most relevant stored meetings,
# and answers using only that context (see RagService on the backend).
#
# Prints the JSON response followed by a final line "HTTP_STATUS:<code>"
# (000 = the API could not be reached or timed out).
#
# Usage: bash agent-ask.sh "<JWT>" "<question>"
set -euo pipefail

JWT="${1:?usage: agent-ask.sh <jwt> <question>}"
QUESTION="${2:?usage: agent-ask.sh <jwt> <question>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR/../.env" ] && set -a && . "$SCRIPT_DIR/../.env" && set +a
API_BASE="${API_BASE:-http://localhost:8080}"
API_BASE="${API_BASE%/}"

# Node is guaranteed on any OpenClaw host — use it to build valid JSON safely.
BODY="$(node -e 'const [, question] = process.argv; process.stdout.write(JSON.stringify({ question: question.trim() }));' "$QUESTION")"

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT
# Embedding the question + retrieval + answer generation is 2 OpenRouter
# round-trips; give it real headroom (backend timeout is 120s per call).
STATUS="$(curl -s --connect-timeout 10 --max-time 150 -X POST "$API_BASE/api/chat" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary "$BODY" \
  -o "$OUT" -w '%{http_code}')" || STATUS=000
cat "$OUT"
printf '\nHTTP_STATUS:%s\n' "$STATUS"
