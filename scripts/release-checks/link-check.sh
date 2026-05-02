#!/usr/bin/env bash
# Verify every public URL referenced by the LegalUrls object resolves.
#
# Run before cutting a stable release. Catches the case where:
#   - the privacy / terms / help page got renamed on torve.app,
#   - the delete-account.html mirror was never published (release
#     blocker B1 in docs/release-hardening.md),
#   - a 5xx is in flight from the host.
#
# Exits 0 when every HTTP(S) URL resolves with a 2xx; non-zero (and
# prints a summary table to stderr) on any miss. mailto: targets are
# listed but not probed.
#
# Usage:
#   scripts/release-checks/link-check.sh                 # default check
#   scripts/release-checks/link-check.sh --quiet         # only print failures
#   scripts/release-checks/link-check.sh --timeout 15    # per-URL HTTP timeout

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LEGAL_FILE="$ROOT/shared/src/commonMain/kotlin/com/torve/presentation/legal/LegalUrls.kt"

QUIET=0
TIMEOUT=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --quiet) QUIET=1 ;;
    --timeout) shift; TIMEOUT="$1" ;;
    --help|-h)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown flag: $1" >&2
      exit 2
      ;;
  esac
  shift
done

if [[ ! -f "$LEGAL_FILE" ]]; then
  echo "FAIL: LegalUrls.kt not found at $LEGAL_FILE" >&2
  exit 2
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "SKIP: curl not on PATH — cannot probe URLs" >&2
  exit 2
fi

# Extract values from "const val NAME: String = \"value\"" lines.
# Tolerates extra whitespace and either explicit type or inferred.
mapfile -t RAW_LINES < <(
  awk '
    /const val [A-Z_]+:?[[:space:]]*(String)?[[:space:]]*=[[:space:]]*"[^"]+"/ {
      # Strip everything before the value, then everything after the closing quote.
      sub(/^[^"]*"/, "")
      sub(/".*$/, "")
      print
    }
  ' "$LEGAL_FILE"
)

# Pair each value with the const name it came from for a readable
# report. Re-walk the file and emit "NAME=VALUE".
mapfile -t ENTRIES < <(
  awk '
    match($0, /const val ([A-Z_]+)/, m) && match($0, /"([^"]+)"/, v) {
      print m[1] "=" v[1]
    }
  ' "$LEGAL_FILE"
)

if [[ ${#ENTRIES[@]} -eq 0 ]]; then
  echo "FAIL: no URL constants matched in $LEGAL_FILE" >&2
  exit 2
fi

passed=()
failed=()
skipped=()

for entry in "${ENTRIES[@]}"; do
  name="${entry%%=*}"
  url="${entry#*=}"
  case "$url" in
    mailto:*)
      skipped+=("$name $url (mailto)")
      continue
      ;;
    http://*|https://*) ;;
    *)
      skipped+=("$name $url (non-http)")
      continue
      ;;
  esac

  # HEAD first; some hosts 405 HEAD but allow GET, so fall back.
  status=$(curl -sS -o /dev/null -w '%{http_code}' \
    --max-time "$TIMEOUT" -L -I "$url" 2>/dev/null || echo "000")
  if [[ "$status" =~ ^[45] ]] || [[ "$status" == "000" ]]; then
    status=$(curl -sS -o /dev/null -w '%{http_code}' \
      --max-time "$TIMEOUT" -L "$url" 2>/dev/null || echo "000")
  fi

  if [[ "$status" =~ ^2 ]]; then
    passed+=("$name $url -> $status")
  else
    failed+=("$name $url -> $status")
  fi
done

if [[ "$QUIET" -eq 0 ]]; then
  echo "Link check report"
  echo "================="
  echo "PASSED (${#passed[@]}):"
  for line in "${passed[@]}"; do echo "  $line"; done
  if [[ ${#skipped[@]} -gt 0 ]]; then
    echo "SKIPPED (${#skipped[@]}):"
    for line in "${skipped[@]}"; do echo "  $line"; done
  fi
fi

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "FAILED (${#failed[@]}):" >&2
  for line in "${failed[@]}"; do echo "  $line" >&2; done
  exit 1
fi

exit 0
