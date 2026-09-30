[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$AccountDirectory,
    [Parameter(Mandatory=$true)][string]$WeixinExe
)
$ErrorActionPreference = 'Stop'
$Root = Join-Path $env:LOCALAPPDATA 'shurufa-wechat-export'
$FailureMessage = '未找到 Python 3.12，请先安装当前用户的 Python 3.12（带 py 启动器）。'
try {
    $Python = Get-Command py.exe -ErrorAction SilentlyContinue
    if (-not $Python) { throw 'python' }
    & $Python.Source -3.12 -c 'import sys; assert sys.version_info[:2] == (3,12)'
    if ($LASTEXITCODE -ne 0) { throw 'python' }
    $FailureMessage = '微信账号目录或程序无效，请检查路径。'
    if (-not (Test-Path -LiteralPath (Join-Path $AccountDirectory 'db_storage\emoticon\emoticon.db') -PathType Leaf)) { throw 'account' }
    if (-not (Test-Path -LiteralPath $WeixinExe -PathType Leaf)) { throw 'exe' }
    $FailureMessage = '无法创建导出工具目录，请检查当前用户权限及目录中是否包含链接。'
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    $Targets = @((Get-Item -LiteralPath $Root)) + @(Get-ChildItem -LiteralPath $Root -Force -Recurse)
    foreach ($Item in $Targets) {
        if ($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'link' }
    }
    $Sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($Item in $Targets) {
        if ($Item.PSIsContainer) {
            $Acl = New-Object Security.AccessControl.DirectorySecurity
            $Rule = New-Object Security.AccessControl.FileSystemAccessRule($Sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        } else {
            $Acl = New-Object Security.AccessControl.FileSecurity
            $Rule = New-Object Security.AccessControl.FileSystemAccessRule($Sid, 'FullControl', 'Allow')
        }
        $Acl.SetAccessRuleProtection($true, $false)
        $Acl.SetOwner($Sid); $Acl.AddAccessRule($Rule)
        Set-Acl -LiteralPath $Item.FullName -AclObject $Acl
    }
    foreach ($Name in @('export.py','collector.py','deadline_http.py','requirements.txt','THIRD_PARTY_NOTICES.md','export.ps1','README.md')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $Name) -Destination (Join-Path $Root $Name) -Force
    }
    $Config = Join-Path $Root 'export-config.json'
    if (-not (Test-Path -LiteralPath $Config)) {
        @{account_dir=(Get-Item -LiteralPath $AccountDirectory).FullName; weixin_exe=(Get-Item -LiteralPath $WeixinExe).FullName; output_dir=(Join-Path $Root 'images')} | ConvertTo-Json | Set-Content -LiteralPath $Config -Encoding UTF8
    } else { Write-Host '保留已有导出配置与图片；如需切换账号，请编辑 export-config.json。' }
    $Venv = Join-Path $Root 'venv'
    $FailureMessage = '独立 Python 环境安装失败，请检查网络后重试，未修改全局 Python。'
    if (-not (Test-Path -LiteralPath (Join-Path $Venv 'Scripts\python.exe'))) {
        & $Python.Source -3.12 -m venv $Venv
        if ($LASTEXITCODE -ne 0) { throw 'venv' }
    }
    & (Join-Path $Venv 'Scripts\python.exe') -m pip install --disable-pip-version-check -r (Join-Path $Root 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'pip' }
    $FailureMessage = '程序已安装，但快捷方式创建失败。可手动运行安装目录中的 export.ps1。'
    $Shell = New-Object -ComObject WScript.Shell
    $ShortcutPath = Join-Path $Root '微信收藏导出.lnk'
    $Shortcut = $Shell.CreateShortcut($ShortcutPath)
    $Shortcut.TargetPath = Join-Path $PSHOME 'powershell.exe'
    $Shortcut.Arguments = '-NoProfile -File "' + (Join-Path $Root 'export.ps1') + '"'
    $Shortcut.WorkingDirectory = $Root
    $Shortcut.Save()
    $Desktop = [Environment]::GetFolderPath('Desktop')
    $DesktopLink = Join-Path $Desktop '微信收藏导出.lnk'
    if ((Test-Path -LiteralPath $Desktop -PathType Container) -and -not (Test-Path -LiteralPath $DesktopLink)) {
        Copy-Item -LiteralPath $ShortcutPath -Destination $DesktopLink
    }
    Write-Host ('安装完成。双击：' + $ShortcutPath)
    Write-Host ('图片保存到：' + (Join-Path $Root 'images'))
    Write-Host '不需要后台地址或配对，不会开机自动运行。'
} catch { Write-Host $FailureMessage; exit 1 }
