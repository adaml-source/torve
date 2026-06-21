#!/usr/bin/env bash
#
# Torve backend deploy from repo -> VPS.
#
# Repo `server/` is the source of truth; this script rsyncs it onto
# /opt/torve-backend on the VPS and triggers the on-VPS deploy helper
# (which handles deps + migrations + service restart + health check).
#
# Usage:
#   ./scripts/deploy-backend.sh                 # dry-run (default — shows the plan, applies nothing)
#   ./scripts/deploy-backend.sh apply           # full deploy: rsync + deps + migrate + restart
#   ./scripts/deploy-backend.sh apply migrate   # rsync + alembic upgrade head only
#   ./scripts/deploy-backend.sh apply deps      # rsync + pip install only
#   ./scripts/deploy-backend.sh apply restart   # rsync + restart + verify only
#   ./scripts/deploy-backend.sh apply check     # rsync + ownership check only
#
# Env:
#   TORVE_SSH_TARGET — SSH host for the VPS. Default: torve@torve.app.
#   TORVE_REQUIRE_CLEAN — if "1", refuses to deploy when local server/
#                         has uncommitted changes. Default: 0 (warn only).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_TARGET="${TORVE_SSH_TARGET:-torve@torve.app}"
APP_DIR="/opt/torve-backend"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
fail() { echo -e "${RED}[x]${NC} $*"; exit 1; }

MODE="${1:-dry}"            # dry|apply
DEPLOY_MODE="${2:-full}"    # full|migrate|deps|restart|check (passed to on-VPS deploy.sh)

case "$MODE" in
    dry|apply) ;;
    *) fail "First arg must be 'dry' or 'apply', got '$MODE'." ;;
esac

case "$DEPLOY_MODE" in
    full|migrate|deps|restart|check) ;;
    *) fail "Second arg must be one of: full|migrate|deps|restart|check (got '$DEPLOY_MODE')." ;;
esac

# Sanity: refuse to run from anywhere except the repo root.
[[ -d "$REPO_ROOT/server" ]] || fail "Cannot find server/ at $REPO_ROOT — run from the repo."
[[ -f "$REPO_ROOT/server/scripts/deploy.sh" ]] \
    || fail "server/scripts/deploy.sh missing — repo snapshot is incomplete."

# Refuse to deploy with uncommitted changes if TORVE_REQUIRE_CLEAN=1.
if [[ "$MODE" == "apply" ]]; then
    DIRTY=$(git -C "$REPO_ROOT" status --porcelain server/ 2>/dev/null | head -1 || true)
    if [[ -n "$DIRTY" ]]; then
        if [[ "${TORVE_REQUIRE_CLEAN:-0}" == "1" ]]; then
            fail "server/ has uncommitted changes. Commit or stash before deploying. (TORVE_REQUIRE_CLEAN=1)"
        else
            warn "server/ has uncommitted changes — deploying them anyway."
            warn "Set TORVE_REQUIRE_CLEAN=1 to refuse this in future."
        fi
    fi
fi

# Build the rsync command. --delete is required for repo-canonical
# semantics (prod should match repo exactly), but exclusions protect
# the VPS-only files (.env, venv, git, runtime caches).
RSYNC_FLAGS=(
    -av
    --delete
    --exclude=venv
    --exclude=.env
    --exclude=.git
    --exclude=__pycache__
    --exclude='*.pyc'
    --exclude=.pytest_cache
    --exclude=node_modules
    --exclude=.coverage
    --exclude=htmlcov
    # Repo-only artifacts. Prod runs under systemd, not Docker — these
    # files have never existed on the VPS and shouldn't get pushed.
    # They're kept in the repo for local-dev reference only. Caught by
    # the first Option B dry-run 2026-05-03.
    --exclude=Dockerfile
    --exclude=docker-compose.yml
    --exclude=docker-compose.yaml
)

if [[ "$MODE" == "dry" ]]; then
    RSYNC_FLAGS+=(--dry-run --itemize-changes)
    info "DRY RUN — no changes will be made on the VPS."
    info "Re-run with 'apply' to execute."
fi

info "Rsyncing $REPO_ROOT/server/ -> $SSH_TARGET:$APP_DIR/"
rsync "${RSYNC_FLAGS[@]}" "$REPO_ROOT/server/" "$SSH_TARGET:$APP_DIR/"

if [[ "$MODE" == "dry" ]]; then
    info "Dry run complete. Review the file list above."
    info "If it looks right, run: $0 apply $DEPLOY_MODE"
    exit 0
fi

info "Triggering on-VPS deploy helper: $APP_DIR/scripts/deploy.sh $DEPLOY_MODE"
ssh -t "$SSH_TARGET" "sudo $APP_DIR/scripts/deploy.sh $DEPLOY_MODE"

info "Deploy complete."
