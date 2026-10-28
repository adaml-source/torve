#!/usr/bin/env bash
# Walk the operator through every case in the real-device matrix and
# write a result markdown to smoke-results/. Prompts for PASS / FAIL /
# SKIP per case + a notes line; collects logcat automatically on FAIL.
#
# Usage:
#   scripts/smoke/run-smoke.sh [--operator <name>] [--out <file>]
#                              [--cases 1,2,4]   # subset
#                              [--non-interactive]  # auto-SKIP everything
#
# The output file is committable evidence of the run. Pre-fills the
# header from capture-version.sh; everything else is operator input.

set -euo pipefail
LIB="$(dirname "$0")/_lib.sh"
# shellcheck source=_lib.sh
. "$LIB"

OPERATOR=""
OUT=""
CASES=""
NONINTERACTIVE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --operator)        OPERATOR="$2"; shift 2 ;;
    --out)             OUT="$2";      shift 2 ;;
    --cases)           CASES="$2";    shift 2 ;;
    --non-interactive) NONINTERACTIVE=1; shift ;;
    --help|-h)
      sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

ROOT="$(project_root)"
TS=$(date +%Y%m%d-%H%M)

if [[ -z "$OUT" ]]; then
  OUT="$ROOT/smoke-results/$TS-${OPERATOR:-anon}.md"
fi
mkdir -p "$(dirname "$OUT")"

# Header: capture installed versions on every connected device so the
# evidence is unambiguous about what was tested.
{
  echo "# Smoke Run"
  echo
  echo "| Field         | Value |"
  echo "| ------------- | ----- |"
  echo "| Date / time   | $(date -Iseconds 2>/dev/null || date) |"
  echo "| Operator      | ${OPERATOR:-anon} |"
  echo "| Backend env   | api.torve.app |"
  if command -v adb >/dev/null 2>&1; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      echo "| Device        | \`$line\` |"
    done < <(bash "$(dirname "$0")/capture-version.sh" 2>/dev/null || true)
  fi
  echo
  echo "## Case results"
  echo
  echo "| #  | Case                                       | Result | Notes |"
  echo "| -- | ------------------------------------------ | ------ | ----- |"
} > "$OUT"

CASE_LIST=(
  "Mobile sign-in (existing account)"
  "New-account setup"
  "Phone signs in TV via QR"
  "Credential transfer desktop->TV"
  "LAN playback"
  "Cellular guard"
  "TV couch QR readability"
  "Windows clean install / playback / update"
)

# Resolve --cases filter: comma-separated 1-based indices.
should_run() {
  local idx="$1"
  if [[ -z "$CASES" ]]; then return 0; fi
  IFS=',' read -ra W <<< "$CASES"
  for w in "${W[@]}"; do [[ "$w" == "$idx" ]] && return 0; done
  return 1
}

ask_result() {
  local prompt="$1"
  if [[ "$NONINTERACTIVE" == "1" ]]; then
    echo "SKIP"
    return
  fi
  local r
  while true; do
    read -r -p "$prompt [P/F/S]: " r
    case "${r:-S}" in
      P|p) echo "PASS"; return ;;
      F|f) echo "FAIL"; return ;;
      S|s) echo "SKIP"; return ;;
      *) echo "  enter P, F, or S" ;;
    esac
  done
}

ask_notes() {
  if [[ "$NONINTERACTIVE" == "1" ]]; then echo ""; return; fi
  local n
  read -r -p "  notes (one line, blank to skip): " n
  echo "$n"
}

failures=0
for i in "${!CASE_LIST[@]}"; do
  num=$((i+1))
  name="${CASE_LIST[$i]}"
  if ! should_run "$num"; then continue; fi

  echo
  echo "── Case $num: $name ──"
  result=$(ask_result "  result")
  notes=$(ask_notes)

  log_path=""
  if [[ "$result" == "FAIL" ]]; then
    failures=$((failures+1))
    echo "  capturing logcat…"
    log_path=$(bash "$(dirname "$0")/capture-logcat.sh" 2>/dev/null || true)
    if [[ -n "$log_path" ]]; then
      notes="$notes (log: $log_path)"
    fi
  fi

  printf "| %d  | %-40s | %s | %s |\n" "$num" "$name" "$result" "$notes" >> "$OUT"
done

echo >> "$OUT"
echo "Failures: $failures" >> "$OUT"

echo
echo "Result file: $OUT"
exit $((failures == 0 ? 0 : 1))
