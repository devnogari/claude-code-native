#!/bin/bash
# CCN Backend Service Control Script
# Cross-platform control for macOS (launchd), Linux (systemd)

set -e

SERVICE_NAME="com.devnogari.ccn-backend"
SYSTEMD_SERVICE="ccn-backend"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"

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

# macOS functions
macos_install() {
    local plist_src="$SCRIPT_DIR/ccn-backend.plist"
    local plist_dst="$HOME/Library/LaunchAgents/com.devnogari.ccn-backend.plist"

    # Build binary if needed
    if [[ ! -f "$BACKEND_DIR/ccn-backend" ]]; then
        log_info "Building backend binary..."
        (cd "$BACKEND_DIR" && go build -o ccn-backend ./cmd/server)
    fi

    # Process template
    log_info "Installing launchd service..."
    sed -e "s|__INSTALL_PATH__|$BACKEND_DIR|g" \
        -e "s|__HOME__|$HOME|g" \
        -e "s|__DATABASE_URL__|${DATABASE_URL:-postgres://ccn:localdev123@localhost:5438/claude_code_native?sslmode=disable}|g" \
        -e "s|__JWT_SECRET__|${JWT_SECRET:-your-super-secure-jwt-secret-key-minimum-32-chars}|g" \
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
    log_success "Service started"
}

macos_stop() {
    local plist="$HOME/Library/LaunchAgents/com.devnogari.ccn-backend.plist"
    if [[ -f "$plist" ]]; then
        launchctl unload "$plist" 2>/dev/null || true
        log_success "Service stopped"
    fi
}

macos_restart() {
    macos_stop
    sleep 1
    macos_start
}

macos_status() {
    if launchctl list | grep -q "$SERVICE_NAME"; then
        local pid=$(launchctl list | grep "$SERVICE_NAME" | awk '{print $1}')
        if [[ "$pid" != "-" && -n "$pid" ]]; then
            log_success "Service is running (PID: $pid)"
        else
            log_warn "Service is loaded but not running"
        fi
    else
        log_warn "Service is not running"
    fi
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

# Linux functions
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
    log_success "Service started"
}

linux_stop() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi
    systemctl stop $SYSTEMD_SERVICE
    log_success "Service stopped"
}

linux_restart() {
    if [[ $EUID -ne 0 ]]; then
        log_error "Please run with sudo"
        exit 1
    fi
    systemctl restart $SYSTEMD_SERVICE
    log_success "Service restarted"
}

linux_status() {
    systemctl status $SYSTEMD_SERVICE --no-pager
}

linux_logs() {
    if [[ "$1" == "-f" ]]; then
        journalctl -u $SYSTEMD_SERVICE -f
    else
        journalctl -u $SYSTEMD_SERVICE -n 100 --no-pager
    fi
}

# Windows stub (use PowerShell script directly)
windows_help() {
    log_info "For Windows, use the PowerShell script:"
    echo "  Install:   powershell -ExecutionPolicy Bypass -File scripts/service/install-windows.ps1"
    echo "  Uninstall: powershell -ExecutionPolicy Bypass -File scripts/service/install-windows.ps1 -Uninstall"
    echo "  Control:   Start-Service ccn-backend / Stop-Service ccn-backend"
}

# Command dispatcher
case "$OS" in
    macos)
        case "$1" in
            install)   macos_install ;;
            uninstall) macos_uninstall ;;
            start)     macos_start ;;
            stop)      macos_stop ;;
            restart)   macos_restart ;;
            status)    macos_status ;;
            logs)      macos_logs "$2" ;;
            *)
                echo "Usage: $0 {install|uninstall|start|stop|restart|status|logs [-f]}"
                exit 1
                ;;
        esac
        ;;
    linux)
        case "$1" in
            install)   linux_install ;;
            uninstall) linux_uninstall ;;
            start)     linux_start ;;
            stop)      linux_stop ;;
            restart)   linux_restart ;;
            status)    linux_status ;;
            logs)      linux_logs "$2" ;;
            *)
                echo "Usage: $0 {install|uninstall|start|stop|restart|status|logs [-f]}"
                exit 1
                ;;
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
