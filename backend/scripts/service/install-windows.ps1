# CCN Backend Windows Service Installation Script
# Requires: NSSM (Non-Sucking Service Manager) - https://nssm.cc/

param(
    [string]$InstallPath = "$env:ProgramFiles\ccn-backend",
    [string]$DatabaseUrl,
    [string]$JwtSecret,
    [int]$Port = 8083,
    [switch]$Uninstall
)

$ServiceName = "ccn-backend"
$DisplayName = "Claude Code Native Backend"

function Test-Admin {
    $currentUser = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $currentUser.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Install-Service {
    if (-not (Test-Admin)) {
        Write-Error "Please run as Administrator"
        exit 1
    }

    # Check for NSSM
    $nssm = Get-Command nssm -ErrorAction SilentlyContinue
    if (-not $nssm) {
        Write-Host "NSSM not found. Please install NSSM first:"
        Write-Host "  choco install nssm"
        Write-Host "  or download from https://nssm.cc/"
        exit 1
    }

    # Check if service exists
    $existingService = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($existingService) {
        Write-Host "Service already exists. Use -Uninstall first."
        exit 1
    }

    # Create install directory
    if (-not (Test-Path $InstallPath)) {
        New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
    }

    # Copy binary
    $binaryPath = Join-Path $PSScriptRoot "..\..\..\ccn-backend.exe"
    if (-not (Test-Path $binaryPath)) {
        Write-Error "Binary not found at $binaryPath. Please build first."
        exit 1
    }
    Copy-Item $binaryPath -Destination (Join-Path $InstallPath "ccn-backend.exe") -Force

    # Create uploads directory
    $uploadsPath = Join-Path $InstallPath "uploads"
    if (-not (Test-Path $uploadsPath)) {
        New-Item -ItemType Directory -Path $uploadsPath -Force | Out-Null
    }

    # Install service with NSSM
    $exePath = Join-Path $InstallPath "ccn-backend.exe"
    & nssm install $ServiceName $exePath

    # Configure service
    & nssm set $ServiceName DisplayName $DisplayName
    & nssm set $ServiceName Description "Backend server for Claude Code Native application"
    & nssm set $ServiceName AppDirectory $InstallPath
    & nssm set $ServiceName AppStdout (Join-Path $InstallPath "logs\stdout.log")
    & nssm set $ServiceName AppStderr (Join-Path $InstallPath "logs\stderr.log")
    & nssm set $ServiceName AppRotateFiles 1
    & nssm set $ServiceName AppRotateBytes 10485760

    # Set environment variables
    $envVars = @(
        "PORT=$Port",
        "LOG_LEVEL=info",
        "CLAUDE_PROJECTS_PATH=$env:USERPROFILE\.claude"
    )
    if ($DatabaseUrl) {
        $envVars += "DATABASE_URL=$DatabaseUrl"
    }
    if ($JwtSecret) {
        $envVars += "JWT_SECRET=$JwtSecret"
    }
    & nssm set $ServiceName AppEnvironmentExtra ($envVars -join "`n")

    # Create logs directory
    $logsPath = Join-Path $InstallPath "logs"
    if (-not (Test-Path $logsPath)) {
        New-Item -ItemType Directory -Path $logsPath -Force | Out-Null
    }

    Write-Host "Service installed successfully!"
    Write-Host "Start with: Start-Service $ServiceName"
    Write-Host "Or: nssm start $ServiceName"
}

function Uninstall-Service {
    if (-not (Test-Admin)) {
        Write-Error "Please run as Administrator"
        exit 1
    }

    $existingService = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($existingService) {
        if ($existingService.Status -eq 'Running') {
            Stop-Service $ServiceName -Force
        }
        & nssm remove $ServiceName confirm
        Write-Host "Service uninstalled successfully!"
    } else {
        Write-Host "Service not found."
    }
}

if ($Uninstall) {
    Uninstall-Service
} else {
    Install-Service
}
