[CmdletBinding()]
param([switch]$NoPause)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Code = 1
try {
    $Python = Join-Path $Root 'venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $Python)) { throw 'not installed' }
    & $Python (Join-Path $Root 'export.py') --config (Join-Path $Root 'export-config.json')
    $Code = $LASTEXITCODE
    if ($Code -eq 0 -or $Code -eq 2) {
        $Config = Get-Content -LiteralPath (Join-Path $Root 'export-config.json') -Raw | ConvertFrom-Json
        if (Test-Path -LiteralPath $Config.output_dir -PathType Container) {
            Start-Process explorer.exe -ArgumentList ('"' + $Config.output_dir + '"')
        }
    }
} catch { Write-Host '无法启动导出工具。请先安装，并检查本机配置；无需后台地址或配对。' }
if (-not $NoPause) { Read-Host '按回车关闭窗口' | Out-Null }
exit $Code
