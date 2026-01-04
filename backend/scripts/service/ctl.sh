#!/bin/bash
# CCN Backend Service Control Script
# Cross-platform control for macOS (launchd), Linux (systemd)
# Integrated with Docker Compose for PostgreSQL

set -e

SERVICE_NAME="com.devnogari.ccn-backend"
SYSTEMD_SERVICE="ccn-backend"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
PROJECT_DIR="$(dirname "$BACKEND_DIR")"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.yml"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

detect_os() {
    case "$(uname -s)" in
        Darwin*) echo "macos" ;;
        Linux*)  echo "linux" ;;
        CYGWIN*|MINGW*|MSYS*) echo "windows" ;;
        *) echo "unknown" ;;
    esac
}

OS=$(detect_os)

# ============================================
# Docker Compose functions (PostgreSQL)
# ============================================

db_start() {
    log_info "Starting PostgreSQL..."
    docker-compose -f "$COMPOSE_FILE" up -d postgres
    log_success "PostgreSQL started"
}

db_stop() {
    log_info "Stopping PostgreSQL..."
    docker-compose -f "$COMPOSE_FILE" stop postgres
    log_success "PostgreSQL stopped"
}

db_status() {
    if docker-compose -f "$COMPOSE_FILE" ps postgres 2>/dev/null | grep -q "Up"; then
        local port=$(docker-compose -f "$COMPOSE_FILE" port postgres 5432 2>/dev/null | cut -d: -f2)
        log_success "PostgreSQL is running (port: ${port:-5438})"
    else
        log_warn "PostgreSQL is not running"
    fi
}

db_logs() {
    if [[ "$1" == "-f" ]]; then
        docker-compose -f "$COMPOSE_FILE" logs -f postgres
    else
        docker-compose -f "$COMPOSE_FILE" logs --tail=100 postgres
    fi
}

db_shell() {
    docker-compose -f "$COMPOSE_FILE" exec postgres psql -U ccn -d claude_code_native
}

# ============================================
# macOS functions
# ============================================

macos_install() {
    local plist_src="$SCRIPT_DIR/ccn-backend.plist"
    local plist_dst="$HOME/Library/LaunchAgents/com.devnogari.ccn-backend.plist"

    # Build binary if needed
    if [[ ! -f "$BACKEND_DIR/ccn-backend" ]]; then
        log_info "Building backend binary..."
        (cd "$BACKEND_DIR" && go build -o ccn-backend ./cmd/server)
    fi

    # Get claude CLI path and build minimal PATH
    local claude_path=$(which claude 2>/dev/null)
    local claude_dir=""
    if [[ -n "$claude_path" ]]; then
        claude_dir="$(dirname "$claude_path"):"
    fi
    local service_path="${claude_dir}/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

    # Process template
    log_info "Installing launchd service..."
    sed -e "s|__INSTALL_PATH__|$BACKEND_DIR|g" \
        -e "s|__HOME__|$HOME|g" \
        -e "s|__DATABASE_URL__|${DATABASE_URL:-postgres://ccn:localdev123@localhost:5438/claude_code_native?sslmode=disable}|g" \
        -e "s|__JWT_SECRET__|${JWT_SECRET:-your-super-secure-jwt-secret-key-minimum-32-chars}|g" \
        -e "s|__PATH__|$service_path|g" \
        -e "s|__GITHUB_TOKEN__|${GITHUB_TOKEN:-}|g" \
        "$plist_src" > "$plist_dst"

    log_success "Service installed at $plist_dst"
    log_info "Start with: $0 start"
}

macos_uninstall() {
    local plist="$HOME/Library/LaunchAgents/com.devnogari.ccn-backend.plist"
    if [[ -f "$plist" ]]; then
        macos_stop 2>/dev/null || true
        rm -f "$plist"
        log_success "Service uninstalled"
    else
        log_warn "Service not installed"
    fi
}

macos_start() {
    local plist="$HOME/Library/LaunchAgents/com.devnogari.ccn-backend.plist"
    if [[ ! -f "$plist" ]]; then
        log_error "Service not installed. Run: $0 install"
        exit 1
    fi
    launchctl load "$plist" 2>/dev/null || true
    log_success "Backend started"
}

macos_stop() {
    local plist="$HOME/Library/LaunchAgents/com.devnogari.ccn-backend.plist"
    if [[ -f "$plist" ]]; then
        launchctl unload "$plist" 2>/dev/null || true
        log_success "Backend stopped"
    fi
}

macos_restart() {
    macos_stop
    sleep 1
    macos_start
}

macos_status() {
    echo "=== Backend ==="
    if launchctl list | grep -q "$SERVICE_NAME"; then
        local pid=$(launchctl list | grep "$SERVICE_NAME" | awk '{print $1}')
        if [[ "$pid" != "-" && -n "$pid" ]]; then
            log_success "Backend is running (PID: $pid)"
        else
            log_warn "Backend is loaded but not running"
        fi
    else
        log_warn "Backend is not running"
    fi
    echo ""
    echo "=== Database ==="
    db_status
}

macos_logs() {
    local stdout_log="$HOME/Library/Logs/ccn-backend.log"
    local stderr_log="$HOME/Library/Logs/ccn-backend.error.log"

    if [[ "$1" == "-f" ]]; then
        tail -f "$stdout_log" "$stderr_log"
    else
        echo "=== stdout ==="
        tail -100 "$stdout_log" 2>/dev/null || echo "(no logs)"
        echo ""
        echo "=== stderr ==="
        tail -100 "$stderr_log" 2>/dev/null || echo "(no logs)"
    fi
}

macos_update() {
    log_info "Building backend binary..."
    (cd "$BACKEND_DIR" && go build -o ccn-backend ./cmd/server)
    log_success "Build complete"
    macos_restart
}

# ============================================
# Linux functions
# ============================================

linux_install() {
    local service_src="$SCRIPT_DIR/ccn-backend.service"
    local service_dst="/etc/systemd/system/ccn-backend.service"

    # Build binary if needed
    if [[ ! -f "$BACKEND_DIR/ccn-backend" ]]; then
        log_info "Building backend binary..."
        (cd "$BACKEND_DIR" && go build -o ccn-backend ./cmd/server)
    fi

    # Check for sudo
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi

    # Process template
    log_info "Installing systemd service..."
    sed -e "s|__INSTALL_PATH__|$BACKEND_DIR|g" \
        -e "s|__HOME__|$HOME|g" \
        -e "s|__USER__|$(logname)|g" \
        -e "s|__GROUP__|$(id -gn $(logname))|g" \
        "$service_src" > "$service_dst"

    systemctl daemon-reload
    systemctl enable ccn-backend

    log_success "Service installed"
    log_info "Start with: sudo $0 start"
}

linux_uninstall() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi

    systemctl stop $SYSTEMD_SERVICE 2>/dev/null || true
    systemctl disable $SYSTEMD_SERVICE 2>/dev/null || true
    rm -f /etc/systemd/system/ccn-backend.service
    systemctl daemon-reload
    log_success "Service uninstalled"
}

linux_start() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi
    systemctl start $SYSTEMD_SERVICE
    log_success "Backend started"
}

linux_stop() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi
    systemctl stop $SYSTEMD_SERVICE
    log_success "Backend stopped"
}

linux_restart() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi
    systemctl restart $SYSTEMD_SERVICE
    log_success "Backend restarted"
}

linux_status() {
    echo "=== Backend ==="
    systemctl status $SYSTEMD_SERVICE --no-pager || true
    echo ""
    echo "=== Database ==="
    db_status
}

linux_logs() {
    if [[ "$1" == "-f" ]]; then
        journalctl -u $SYSTEMD_SERVICE -f
    else
        journalctl -u $SYSTEMD_SERVICE -n 100 --no-pager
    fi
}

linux_update() {
    log_info "Building backend binary..."
    (cd "$BACKEND_DIR" && go build -o ccn-backend ./cmd/server)
    log_success "Build complete"
    linux_restart
}

# ============================================
# Full stack commands (up/down)
# ============================================

stack_up() {
    log_info "Starting full stack..."
    db_start
    sleep 2  # Wait for PostgreSQL to be ready
    case "$OS" in
        macos) macos_start ;;
        linux) linux_start ;;
    esac
    log_success "Full stack started"
}

stack_down() {
    log_info "Stopping full stack..."
    case "$OS" in
        macos) macos_stop ;;
        linux) linux_stop ;;
    esac
    db_stop
    log_success "Full stack stopped"
}

# ============================================
# Windows stub
# ============================================

windows_help() {
    log_info "For Windows, use the PowerShell script:"
    echo "  Install:   powershell -ExecutionPolicy Bypass -File scripts/service/install-windows.ps1"
    echo "  Uninstall: powershell -ExecutionPolicy Bypass -File scripts/service/install-windows.ps1 -Uninstall"
    echo "  Control:   Start-Service ccn-backend / Stop-Service ccn-backend"
}

# ============================================
# Usage
# ============================================

show_usage() {
    echo "Usage: $0 <command> [options]"
    echo ""
    echo "Stack commands:"
    echo "  up              Start PostgreSQL + Backend"
    echo "  down            Stop Backend + PostgreSQL"
    echo ""
    echo "Backend commands:"
    echo "  install         Install backend as system service"
    echo "  uninstall       Remove backend service"
    echo "  start           Start backend service"
    echo "  stop            Stop backend service"
    echo "  restart         Restart backend service"
    echo "  update          Build and restart backend"
    echo "  status          Show backend + database status"
    echo "  logs [-f]       Show backend logs (-f to follow)"
    echo ""
    echo "Database commands:"
    echo "  db start        Start PostgreSQL container"
    echo "  db stop         Stop PostgreSQL container"
    echo "  db status       Show PostgreSQL status"
    echo "  db logs [-f]    Show PostgreSQL logs"
    echo "  db shell        Open psql shell"
}

# ============================================
# Command dispatcher
# ============================================

case "$1" in
    # Stack commands
    up)   stack_up ;;
    down) stack_down ;;

    # Database commands
    db)
        case "$2" in
            start)  db_start ;;
            stop)   db_stop ;;
            status) db_status ;;
            logs)   db_logs "$3" ;;
            shell)  db_shell ;;
            *)
                echo "Usage: $0 db {start|stop|status|logs [-f]|shell}"
                exit 1
                ;;
        esac
        ;;

    # Backend commands (OS-specific)
    install|uninstall|start|stop|restart|update|status|logs)
        case "$OS" in
            macos)
                case "$1" in
                    install)   macos_install ;;
                    uninstall) macos_uninstall ;;
                    start)     macos_start ;;
                    stop)      macos_stop ;;
                    restart)   macos_restart ;;
                    update)    macos_update ;;
                    status)    macos_status ;;
                    logs)      macos_logs "$2" ;;
                esac
                ;;
            linux)
                case "$1" in
                    install)   linux_install ;;
                    uninstall) linux_uninstall ;;
                    start)     linux_start ;;
                    stop)      linux_stop ;;
                    restart)   linux_restart ;;
                    update)    linux_update ;;
                    status)    linux_status ;;
                    logs)      linux_logs "$2" ;;
                esac
                ;;
            windows)
                windows_help
                ;;
            *)
                log_error "Unsupported operating system: $OS"
                exit 1
                ;;
        esac
        ;;

    *)
        show_usage
        exit 1
        ;;
esac
