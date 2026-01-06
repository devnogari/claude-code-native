# CCN Backend Service Scripts

Cross-platform daemon/service management for Claude Code Native backend.

## Quick Start

### macOS (launchd)

```bash
# Install and start
./ctl.sh install
./ctl.sh start

# Control
./ctl.sh status
./ctl.sh restart
./ctl.sh stop
./ctl.sh logs        # View recent logs
./ctl.sh logs -f     # Follow logs

# Uninstall
./ctl.sh uninstall
```

### Linux (systemd)

```bash
# Install (requires sudo)
sudo ./ctl.sh install
sudo ./ctl.sh start

# Control
./ctl.sh status
sudo ./ctl.sh restart
sudo ./ctl.sh stop
./ctl.sh logs        # View recent logs
./ctl.sh logs -f     # Follow logs

# Uninstall
sudo ./ctl.sh uninstall
```

### Windows (NSSM)

Requires [NSSM](https://nssm.cc/) (Non-Sucking Service Manager):
```powershell
choco install nssm  # or download from https://nssm.cc/
```

```powershell
# Install (requires Administrator)
.\ctl.ps1 install

# Control
.\ctl.ps1 status
.\ctl.ps1 start
.\ctl.ps1 stop
.\ctl.ps1 restart
.\ctl.ps1 logs
.\ctl.ps1 logs -Follow

# Uninstall
.\ctl.ps1 uninstall
```

## Environment Variables

Set these before running `install`:

| Variable | Description | Required |
|----------|-------------|----------|
| `DATABASE_URL` | PostgreSQL connection string | **Yes** |
| `JWT_SECRET` | JWT signing secret (32+ chars) | **Yes** |
| `PORT` | Server port | No (default: `8083`) |
| `LOG_LEVEL` | Log level (debug/info/warn/error) | No (default: `info`) |
| `CLAUDE_PROJECTS_PATH` | Claude projects directory | No (default: `~/.claude`) |

### macOS/Linux

```bash
export DATABASE_URL="postgres://user:pass@host:5432/db?sslmode=disable"
export JWT_SECRET="your-32-character-or-longer-secret-key"
./ctl.sh install
```

### Windows

```powershell
.\install-windows.ps1 -DatabaseUrl "postgres://..." -JwtSecret "your-secret"
```

## File Locations

### macOS
- Service plist: `~/Library/LaunchAgents/com.devnogari.ccn-backend.plist`
- Logs: `~/Library/Logs/ccn-backend.log`, `~/Library/Logs/ccn-backend.error.log`

### Linux
- Service unit: `/etc/systemd/system/ccn-backend.service`
- Logs: `journalctl -u ccn-backend`

### Windows
- Service binary: `C:\Program Files\ccn-backend\ccn-backend.exe`
- Logs: `C:\Program Files\ccn-backend\logs\`

## Building from Source

Build for current platform:
```bash
cd backend
go build -o ccn-backend ./cmd/server
```

Build for all platforms:
```bash
./scripts/build-all.sh
# Output: backend/dist/
```

## Troubleshooting

### Service won't start

1. Check logs: `./ctl.sh logs`
2. Verify PostgreSQL is running
3. Check environment variables are set correctly

### Permission denied (Linux)

```bash
sudo ./ctl.sh start
```

### NSSM not found (Windows)

Install via Chocolatey or download from https://nssm.cc/

### Port already in use

Check if another process is using port 8083:
```bash
lsof -i :8083  # macOS/Linux
netstat -ano | findstr :8083  # Windows
```
