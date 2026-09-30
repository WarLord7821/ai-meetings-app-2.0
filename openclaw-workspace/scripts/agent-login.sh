#!/usr/bin/env bash
# Agent login helper (Linux/macOS)
#
# Reads API_BASE / FRONTEND_URL from ../.env, then POSTs the user's email and
# password to the API and prints the JSON response (JWT + user object) to stdout,
# followed by a final line "HTTP_STATUS:<code>" (000 = API unreachable).
#
# Usage: bash agent-login.sh "<email>" "<password>"
set -euo pipefail

EMAIL="${1:?usage: agent-login.sh <email> <password>}"
PASSWORD="${2:?usage: agent-login.sh <email> <password>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$SCRIPT_DIR/../.env" ] && set -a && . "$SCRIPT_DIR/../.env" && set +a
API_BASE="${API_BASE:-http://localhost:8080}"
API_BASE="${API_BASE%/}"

# Node is guaranteed on any OpenClaw host — use it to build valid JSON safely.
BODY="$(node -e 'const [, email, password] = process.argv; process.stdout.write(JSON.stringify({ email: email.trim(), password }));' "$EMAIL" "$PASSWORD")"

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT
STATUS="$(curl -s --connect-timeout 10 --max-time 30 -X POST "$API_BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  --data-binary "$BODY" \
  -o "$OUT" -w '%{http_code}')" || STATUS=000
cat "$OUT"
printf '\nHTTP_STATUS:%s\n' "$STATUS"
