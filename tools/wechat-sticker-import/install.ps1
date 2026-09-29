[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Origin,
    [Parameter(Mandatory=$true)][string]$AccountDirectory,
    [Parameter(Mandatory=$true)][string]$WeixinExe,
    [switch]$AllowLocalHttp
)
$ErrorActionPreference = 'Stop'
$Root = Join-Path $env:LOCALAPPDATA 'shurufa-wechat-import'
try {
    $FailureMessage = '未找到 Python 3.12，请先安装当前用户的 Python 3.12（含 py 启动器）。'
    $Python = Get-Command py.exe -ErrorAction SilentlyContinue
    if (-not $Python) { throw '需要安装当前用户的 Python 3.12（含 py 启动器），再重新运行。' }
    & $Python.Source -3.12 -c 'import sys; assert sys.version_info[:2] == (3,12)'
    if ($LASTEXITCODE -ne 0) { throw '未找到 Python 3.12，请先安装后再重试。' }
    $FailureMessage = '微信账号目录或微信程序路径不存在。'
    if (-not (Test-Path -LiteralPath $AccountDirectory -PathType Container)) { throw '微信账号目录不存在。' }
    if (-not (Test-Path -LiteralPath $WeixinExe -PathType Leaf)) { throw '微信程序路径不存在。' }
    $FailureMessage = '服务器地址无效：必须是 HTTPS 根地址，本地测试仅允许显式 HTTP 回环地址。'
    # Validate origin before writing config. No shell interpolation into Python code.
    $env:SHURUFA_CHECK_ORIGIN = $Origin
    $env:SHURUFA_CHECK_LOCAL = [string]$AllowLocalHttp.IsPresent
    $Validation = @'
import os, ipaddress, urllib.parse
s = os.environ['SHURUFA_CHECK_ORIGIN']
u = urllib.parse.urlsplit(s)
assert not any(ord(c) <= 32 for c in s) and chr(92) not in s
assert u.hostname and u.username is None and u.password is None
assert u.path in ('', '/') and '?' not in s and '#' not in s
assert u.port is None or 0 < u.port < 65536
assert u.scheme == 'https' or (u.scheme == 'http' and os.environ['SHURUFA_CHECK_LOCAL'] == 'True' and ipaddress.ip_address(u.hostname).is_loopback)
'@
    $Validation | & $Python.Source -3.12 - 2>$null
    if ($LASTEXITCODE -ne 0) { throw '服务器地址无效：必须是 HTTPS 根地址，本地测试仅允许显式 HTTP 回环地址。' }
    $FailureMessage = '无法配置助手专用目录，请检查当前用户权限。'
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    if ((Get-Item -LiteralPath $Root).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '工具目录不能是链接。' }
    $Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    # Refuse reparse points before recursively tightening this tool directory only.
    $Children = @(Get-ChildItem -LiteralPath $Root -Force -Recurse)
    foreach ($Item in $Children) {
        if ($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '工具目录内不能包含链接。' }
    }
    $Targets = @((Get-Item -LiteralPath $Root)) + $Children
    foreach ($Item in $Targets) {
        if ($Item.PSIsContainer) {
            $Acl = New-Object Security.AccessControl.DirectorySecurity
            $Rule = New-Object Security.AccessControl.FileSystemAccessRule($Sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        } else {
            $Acl = New-Object Security.AccessControl.FileSecurity
            $Rule = New-Object Security.AccessControl.FileSystemAccessRule($Sid, 'FullControl', 'Allow')
        }
        $Acl.SetAccessRuleProtection($true, $false)
        $Acl.SetOwner($Sid)
        $Acl.AddAccessRule($Rule)
        Set-Acl -LiteralPath $Item.FullName -AclObject $Acl
    }
    $ConfigFile = Join-Path $Root 'config.json'
    if (-not (Test-Path -LiteralPath $ConfigFile)) {
        @{origin=$Origin.TrimEnd('/'); account_dir=(Get-Item -LiteralPath $AccountDirectory).FullName; weixin_exe=(Get-Item -LiteralPath $WeixinExe).FullName; allow_local_http=$AllowLocalHttp.IsPresent} |
            ConvertTo-Json | Set-Content -LiteralPath $ConfigFile -Encoding UTF8
    } else { Write-Host '保留已有配置及配对授权；本次参数不覆盖旧配置。' }
    foreach ($Name in @('agent.py','collector.py','deadline_http.py','requirements.txt','THIRD_PARTY_NOTICES.md','start.ps1','README.md')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $Name) -Destination (Join-Path $Root $Name) -Force
    }
    $Venv = Join-Path $Root 'venv'
    if (-not (Test-Path -LiteralPath (Join-Path $Venv 'Scripts\python.exe'))) {
        & $Python.Source -3.12 -m venv $Venv
        if ($LASTEXITCODE -ne 0) { throw '创建独立 Python 环境失败。' }
    }
    $FailureMessage = '独立环境依赖安装失败，请检查网络后重试；未修改全局 Python。'
    & (Join-Path $Venv 'Scripts\python.exe') -m pip install --disable-pip-version-check -r (Join-Path $Root 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw '依赖安装失败；未修改全局 Python。请检查网络后重试。' }
    Write-Host '安装完成。使用 start.ps1 -Pair 配对，再使用 start.ps1 启动；不会自动开机运行。'
} catch {
    Write-Host $FailureMessage
    exit 1
} finally {
    Remove-Item Env:SHURUFA_CHECK_ORIGIN -ErrorAction SilentlyContinue
    Remove-Item Env:SHURUFA_CHECK_LOCAL -ErrorAction SilentlyContinue
}
