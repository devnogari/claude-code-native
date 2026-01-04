# CCN Backend Service Control Script for Windows
# Usage: .\ctl.ps1 {start|stop|restart|status|logs}

param(
    [Parameter(Position=0, Mandatory=$true)]
    [ValidateSet('start', 'stop', 'restart', 'update', 'status', 'logs', 'install', 'uninstall')]
    [string]$Command,

    [switch]$Follow
)

$ServiceName = "ccn-backend"

function Test-ServiceExists {
    $service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    return $null -ne $service
}

function Show-Status {
    if (Test-ServiceExists) {
        $service = Get-Service -Name $ServiceName
        Write-Host "Service: $($service.DisplayName)"
        Write-Host "Status:  $($service.Status)"
        if ($service.Status -eq 'Running') {
            $process = Get-Process -Name "ccn-backend" -ErrorAction SilentlyContinue
            if ($process) {
                Write-Host "PID:     $($process.Id)"
                Write-Host "Memory:  $([math]::Round($process.WorkingSet64 / 1MB, 2)) MB"
            }
        }
    } else {
        Write-Host "Service not installed. Run: .\ctl.ps1 install"
    }
}

function Start-Backend {
    if (-not (Test-ServiceExists)) {
        Write-Error "Service not installed. Run: .\ctl.ps1 install"
        return
    }

    $service = Get-Service -Name $ServiceName
    if ($service.Status -eq 'Running') {
        Write-Host "Service is already running"
        return
    }

    Start-Service $ServiceName
    Write-Host "Service started"
}

function Stop-Backend {
    if (-not (Test-ServiceExists)) {
        Write-Warning "Service not installed"
        return
    }

    $service = Get-Service -Name $ServiceName
    if ($service.Status -eq 'Stopped') {
        Write-Host "Service is already stopped"
        return
    }

    Stop-Service $ServiceName -Force
    Write-Host "Service stopped"
}

function Restart-Backend {
    Stop-Backend
    Start-Sleep -Seconds 2
    Start-Backend
}

function Show-Logs {
    $logPath = "$env:ProgramFiles\ccn-backend\logs"
    $stdoutLog = Join-Path $logPath "stdout.log"
    $stderrLog = Join-Path $logPath "stderr.log"

    if ($Follow) {
        Write-Host "Following logs (Ctrl+C to stop)..."
        Get-Content $stdoutLog, $stderrLog -Wait -Tail 50
    } else {
        if (Test-Path $stdoutLog) {
            Write-Host "=== stdout ===" -ForegroundColor Cyan
            Get-Content $stdoutLog -Tail 50
        }
        if (Test-Path $stderrLog) {
            Write-Host "`n=== stderr ===" -ForegroundColor Yellow
            Get-Content $stderrLog -Tail 50
        }
    }
}

function Install-Backend {
    $scriptPath = Join-Path $PSScriptRoot "install-windows.ps1"
    & $scriptPath
}

function Uninstall-Backend {
    $scriptPath = Join-Path $PSScriptRoot "install-windows.ps1"
    & $scriptPath -Uninstall
}

function Update-Backend {
    $backendDir = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    Write-Host "[INFO] Building backend binary..." -ForegroundColor Blue
    Push-Location $backendDir
    try {
        & go build -o ccn-backend.exe ./cmd/server
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[OK] Build complete" -ForegroundColor Green

            # Copy to install location if service is installed
            $installPath = "$env:ProgramFiles\ccn-backend"
            if (Test-Path $installPath) {
                Copy-Item "ccn-backend.exe" -Destination $installPath -Force
            }

            Restart-Backend
        } else {
            Write-Error "Build failed"
        }
    } finally {
        Pop-Location
    }
}

switch ($Command) {
    'start'     { Start-Backend }
    'stop'      { Stop-Backend }
    'restart'   { Restart-Backend }
    'update'    { Update-Backend }
    'status'    { Show-Status }
    'logs'      { Show-Logs }
    'install'   { Install-Backend }
    'uninstall' { Uninstall-Backend }
}
