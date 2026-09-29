[CmdletBinding()]
param([switch]$Pair, [switch]$Background)
$ErrorActionPreference = 'Stop'
$Root = Join-Path $env:LOCALAPPDATA 'shurufa-wechat-import'
$Python = Join-Path $Root 'venv\Scripts\python.exe'
$Agent = Join-Path $Root 'agent.py'
if ($Pair -and $Background) { Write-Host '配对需要前台安全输入，不能使用后台模式。'; exit 1 }
if (-not (Test-Path -LiteralPath $Python)) { Write-Host '请先运行 install.ps1 安装助手。'; exit 1 }
if ($Background) {
    # Explicit new console, not hidden service/task: close its window to stop.
    $Process = Start-Process -FilePath $Python -ArgumentList @(('"' + $Agent + '"'), 'run') -WorkingDirectory $Root -PassThru
    Write-Host ('助手已在独立窗口启动，进程号 ' + $Process.Id + '；关闭该窗口停止。不自动开机启动。')
} else {
    $Command = if ($Pair) { 'pair' } else { 'run' }
    & $Python $Agent $Command
    exit $LASTEXITCODE
}
