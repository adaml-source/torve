#!/usr/bin/env bash
#
# Torve backend deploy helper.
# Runs maintenance commands as the torve user, restarts the service,
# and verifies ownership + health afterward.
#
# Usage:
#   sudo ./scripts/deploy.sh           # full deploy: deps + migrate + restart
#   sudo ./scripts/deploy.sh migrate    # alembic upgrade head only
#   sudo ./scripts/deploy.sh deps       # pip install only
#   sudo ./scripts/deploy.sh restart    # restart + verify only
#   sudo ./scripts/deploy.sh check      # ownership check only
#
set -euo pipefail

APP_DIR="/opt/torve-backend"
VENV="$APP_DIR/venv"
SERVICE_USER="torve"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[+]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
fail()  { echo -e "${RED}[x]${NC} $*"; exit 1; }

# Must run as root (for systemctl and sudo -u torve)
[[ $EUID -eq 0 ]] || fail "Run with sudo: sudo $0 $*"

run_as_torve() {
    sudo -u "$SERVICE_USER" -H bash -c "cd $APP_DIR && $1"
}

check_ownership() {
    info "Checking file ownership..."
    BAD_FILES=$(find "$APP_DIR" -not -path '*/venv/*' \! -user "$SERVICE_USER" 2>/dev/null || true)
    if [[ -n "$BAD_FILES" ]]; then
        warn "Files NOT owned by $SERVICE_USER:"
        echo "$BAD_FILES"
        info "Fixing ownership..."
        chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR"
        info "Ownership fixed."
    else
        info "All app files owned by $SERVICE_USER."
    fi

    # Check .env permissions
    ENV_PERMS=$(stat -c '%a' "$APP_DIR/.env" 2>/dev/null || echo "missing")
    if [[ "$ENV_PERMS" != "640" ]]; then
        warn ".env permissions are $ENV_PERMS, fixing to 640..."
        chmod 640 "$APP_DIR/.env"
    fi
}

do_deps() {
    info "Installing Python dependencies as $SERVICE_USER..."
    run_as_torve "$VENV/bin/pip install -r requirements.txt -q"
    info "Dependencies installed."
}

do_migrate() {
    info "Running Alembic migrations as $SERVICE_USER..."
    run_as_torve "$VENV/bin/alembic upgrade head"
    info "Migrations complete."
}

do_restart() {
    info "Restarting torve-backend..."
    systemctl restart torve-backend
    sleep 2

    if systemctl is-active --quiet torve-backend; then
        info "Service is active."
    else
        fail "Service failed to start! Check: journalctl -u torve-backend -n 30"
    fi

    # Verify the process runs as the correct user
    PROC_USER=$(ps -o user= -p "$(systemctl show -p MainPID --value torve-backend)" 2>/dev/null || echo "unknown")
    if [[ "$PROC_USER" == "$SERVICE_USER" ]]; then
        info "Process running as $SERVICE_USER."
    else
        warn "Process running as '$PROC_USER', expected '$SERVICE_USER'!"
    fi
}

do_health() {
    info "Health check..."
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/health 2>/dev/null || echo "000")
    if [[ "$HTTP_CODE" == "200" ]]; then
        info "Health endpoint returned 200."
    else
        fail "Health check failed (HTTP $HTTP_CODE)."
    fi
}

case "${1:-full}" in
    full)
        check_ownership
        do_deps
        do_migrate
        do_restart
        do_health
        info "Deploy complete."
        ;;
    deps)
        check_ownership
        do_deps
        ;;
    migrate)
        check_ownership
        do_migrate
        ;;
    restart)
        check_ownership
        do_restart
        do_health
        ;;
    check)
        check_ownership
        ;;
    *)
        echo "Usage: sudo $0 {full|deps|migrate|restart|check}"
        exit 1
        ;;
esac
