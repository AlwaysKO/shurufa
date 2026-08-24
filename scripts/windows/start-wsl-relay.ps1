param(
    [int]$ListenPort = 3001,
    [Parameter(Mandatory = $true)][string]$TargetHost,
    [int]$TargetPort = 3000
)

$ErrorActionPreference = 'Stop'
$relay = Join-Path $PSScriptRoot 'wsl-tcp-relay.ps1'

# WSL IP 重启后可能变化，先停止旧目标的中继进程再重建。
Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -like "*$relay*" -and $_.ProcessId -ne $PID } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $relay,
    '-ListenPort', $ListenPort,
    '-TargetHost', $TargetHost,
    '-TargetPort', $TargetPort
)

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 100
    if (Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $ListenPort -ErrorAction SilentlyContinue) {
        Write-Output "Windows USB relay: 127.0.0.1:$ListenPort -> ${TargetHost}:$TargetPort"
        exit 0
    }
}
throw "Windows USB relay failed to start on port $ListenPort"
