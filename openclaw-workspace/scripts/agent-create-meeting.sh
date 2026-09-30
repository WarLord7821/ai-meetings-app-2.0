#!/usr/bin/env bash
# Agent create-meeting helper (Linux/macOS)
#
# Reads API_BASE from ../.env, then POSTs the meeting title + transcript with
# the JWT captured from a prior successful login. Sends the
# "X-Agent-Channel: whatsapp" header so the Spring Boot API enforces the
# Pro-only rule for WhatsApp-originated requests.
#
# Prints the JSON response followed by a final line "HTTP_STATUS:<code>"
# (000 = the API could not be reached or timed out).
#
# Long transcripts: pass "@/path/to/transcript.txt" as the third argument to
# read the transcript from a UTF-8 file instead of the command line.
#
# Usage: bash agent-create-meeting.sh "<JWT>" "<title>" "<transcript | @file>"
set -euo pipefail

JWT="${1:?usage: agent-create-meeting.sh <jwt> <title> <transcript|@file>}"
TITLE="${2:-}"
TRANSCRIPT="${3:?usage: agent-create-meeting.sh <jwt> <title> <transcript|@file>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR/../.env" ] && set -a && . "$SCRIPT_DIR/../.env" && set +a
API_BASE="${API_BASE:-http://localhost:8080}"
API_BASE="${API_BASE%/}"

# Node is guaranteed on any OpenClaw host — use it to build valid JSON safely.
BODY_FILE="$(mktemp)"
OUT="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$OUT"' EXIT
node -e '
const fs = require("fs");
let [, title, transcript, out] = process.argv;
if (transcript.startsWith("@")) transcript = fs.readFileSync(transcript.slice(1), "utf8");
if (title.trim().toLowerCase() === "skip") title = "";
fs.writeFileSync(out, JSON.stringify({ title: title.trim(), transcript: transcript.trim() }));
' "$TITLE" "$TRANSCRIPT" "$BODY_FILE"

# Summaries of long transcripts can take 1-2 minutes (backend timeout is 120s)
STATUS="$(curl -s --connect-timeout 10 --max-time 180 -X POST "$API_BASE/api/meetings" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json; charset=utf-8" \
  -H "X-Agent-Channel: whatsapp" \
  --data-binary "@$BODY_FILE" \
  -o "$OUT" -w '%{http_code}')" || STATUS=000
cat "$OUT"
printf '\nHTTP_STATUS:%s\n' "$STATUS"
