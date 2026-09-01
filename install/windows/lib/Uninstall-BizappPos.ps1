# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# ---------------------------------------------------------------------------
# AUTHORED WITHOUT A WINDOWS TEST RUN.
# No Windows machine was available while this was written. The logic was
# hand-traced rather than executed. If it misbehaves, please report the exact
# console text and the output of:
#   powershell -NoProfile -Command "$PSVersionTable.PSVersion"
# ---------------------------------------------------------------------------
#
# ABSOLUTE PROHIBITION, restated so nobody "tidies" it away later:
# NEVER use a wildcard anywhere near dist. "Remove-Item dist*" would destroy
# the committed directory  dist - bizpapp\  and the committed archive dist.rar,
# both of which are repository content. Only the exact literal paths
# <root>\build and <root>\dist are ever deleted, and only by name.
#
# Windows PowerShell 5.1 ONLY.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$BizappInstallerVersion = '1.0.0'
$BizappProductVersion   = '4.0'

$script:AssumeYes = $false
$global:LASTEXITCODE = 0

# ---------------------------------------------------------------------------
# Output helpers. Everything human goes to real stderr.
# ---------------------------------------------------------------------------

function Write-Step     { param([string]$Text) [Console]::Error.WriteLine('==> ' + $Text) }
function Write-Info     { param([string]$Text) [Console]::Error.WriteLine($Text) }
function Write-WarnLine { param([string]$Text) [Console]::Error.WriteLine('[warn] ' + $Text) }
function Write-ErrLine  { param([string]$Text) [Console]::Error.WriteLine('[error] ' + $Text) }
function Write-Hint     { param([string]$Text) [Console]::Error.WriteLine('        ' + $Text) }

function Get-Env {
    param([string]$Name)
    return [System.Environment]::GetEnvironmentVariable($Name)
}

function Get-PropValue {
    param($InputObject, [string]$Name)
    if ($null -eq $InputObject) { return $null }
    $prop = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-CleanPath {
    param([string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return $Path }
    $result = $Path
    while ($result.Length -gt 3 -and $result.EndsWith('\')) {
        $result = $result.Substring(0, $result.Length - 1)
    }
    return $result
}

function Test-Interactive {
    try { if ([Console]::IsInputRedirected) { return $false } } catch { }
    try { if (-not [System.Environment]::UserInteractive) { return $false } } catch { }
    return $true
}

function Read-YesNo {
    param([string]$Question)
    if ($script:AssumeYes) { return $true }
    if (-not (Test-Interactive)) { return $false }
    [Console]::Error.Write($Question + ' [y/N] ')
    $answer = $null
    try { $answer = [Console]::ReadLine() } catch { return $false }
    [Console]::Error.WriteLine('')
    if ($null -eq $answer) { return $false }
    $answer = $answer.Trim()
    return ($answer -eq 'y' -or $answer -eq 'yes')
}

# Deletes one exact path. Never a wildcard, never a pattern.
function Remove-BizappItem {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrEmpty($Path)) { return }
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Info ('  not present   ' + $Label)
        return
    }
    try {
        Remove-Item -LiteralPath $Path -Recurse -Force
        Write-Info ('  removed       ' + $Path)
    } catch {
        Write-WarnLine ('could not remove ' + $Path)
        Write-Hint $_.Exception.Message
    }
}

function Show-Usage {
    Write-Info ('BIZAPP POS setup ' + $BizappInstallerVersion + '  (BIZAPP POS ' + $BizappProductVersion + ')')
    Write-Info ''
    Write-Info 'Usage:  install\windows\uninstall.cmd [options]'
    Write-Info ''
    Write-Info 'Removes the BIZAPP POS shortcuts, logs and build output. Your settings and'
    Write-Info 'your point-of-sale database are kept unless you explicitly ask otherwise.'
    Write-Info ''
    Write-Info 'Options:'
    Write-Info '  --remove-data   also delete your settings and your database. Requires typing'
    Write-Info '                  a confirmation phrase. --yes does not bypass it.'
    Write-Info '  --keep-build    keep the build and dist folders in the checkout'
    Write-Info '  --yes           do not ask for the ordinary confirmation'
    Write-Info '  --help          show this text'
    Write-Info '  --version       print the version and exit'
}

# ---------------------------------------------------------------------------
# Arguments.
# ---------------------------------------------------------------------------

$optRemoveData = $false
$optKeepBuild  = $false
$optHelp       = $false
$optVersion    = $false

$argv = @($args)
$index = 0
while ($index -lt $argv.Count) {
    $token = [string]$argv[$index]
    switch ($token) {
        '--remove-data' { $optRemoveData = $true }
        '--purge-data'  { $optRemoveData = $true }
        '--keep-build'  { $optKeepBuild  = $true }
        '--yes'         { $script:AssumeYes = $true }
        '--help'        { $optHelp        = $true }
        '-h'            { $optHelp        = $true }
        '/?'            { $optHelp        = $true }
        '--version'     { $optVersion     = $true }
        default {
            Write-ErrLine ('unknown option: ' + $token)
            Write-Hint 'Run  install\windows\uninstall.cmd --help  for the list of options.'
            exit 2
        }
    }
    $index = $index + 1
}

if ($optVersion) { [Console]::Out.WriteLine($BizappInstallerVersion); exit 0 }
if ($optHelp)    { Show-Usage; exit 0 }

Write-Step ('BIZAPP POS setup ' + $BizappInstallerVersion + '  (BIZAPP POS ' + $BizappProductVersion + ')')

$userProfile = Get-Env 'USERPROFILE'
if ([string]::IsNullOrEmpty($userProfile)) {
    Write-ErrLine 'USERPROFILE is not set, so uninstall cannot find your home folder.'
    Write-Hint 'Run this from a normal user account rather than a service context.'
    exit 10
}

$Root = $null
try {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
} catch {
    Write-ErrLine 'uninstall could not work out where this checkout lives.'
    Write-Hint 'Run uninstall.cmd from the folder git created, not from a copied script.'
    exit 5
}
$Root = Get-CleanPath $Root

$localAppData = Get-Env 'LOCALAPPDATA'
if ([string]::IsNullOrEmpty($localAppData)) { $localAppData = Join-Path $userProfile 'AppData\Local' }

$appDataDir  = Join-Path $localAppData 'BIZAPP POS'
$logDir      = Join-Path $appDataDir 'logs'
$logConfig   = Join-Path $appDataDir 'logging.properties'
$startMenu   = $null
$appData     = Get-Env 'APPDATA'
if (-not [string]::IsNullOrEmpty($appData)) {
    $startMenu = Join-Path $appData 'Microsoft\Windows\Start Menu\Programs\BIZAPP POS.lnk'
}

$desktopLink = $null
try {
    $desktopDir = [System.Environment]::GetFolderPath('Desktop')
    if ([string]::IsNullOrEmpty($desktopDir)) {
        $desktopDir = [System.Environment]::GetFolderPath('DesktopDirectory')
    }
    if (-not [string]::IsNullOrEmpty($desktopDir)) {
        $desktopLink = Join-Path $desktopDir 'BIZAPP POS.lnk'
    }
} catch {
    $desktopLink = $null
}

# Exact literals only. See the prohibition at the top of this file.
$buildDir = Join-Path $Root 'build'
$distDir  = Join-Path $Root 'dist'

$propertiesPath = Join-Path $userProfile 'nordpos.properties'
$databasePath   = Join-Path $userProfile '.derby-db'

# ---------------------------------------------------------------------------
# 1. Show exactly what will happen.
# ---------------------------------------------------------------------------

Write-Info ''
Write-Info 'This will REMOVE:'
if ($null -ne $startMenu)   { Write-Info ('  ' + $startMenu) }
if ($null -ne $desktopLink) { Write-Info ('  ' + $desktopLink) }
Write-Info ('  ' + $logDir)
# Section 4 removes these two as well, so section 1 has to name them: this
# file's contract is that the plan is exactly what happens.
Write-Info ('  ' + $logConfig)
Write-Info ('  ' + $appDataDir + '   - only if nothing else is left in it')
if (-not $optKeepBuild) {
    Write-Info ('  ' + $buildDir)
    Write-Info ('  ' + $distDir)
} else {
    Write-Info '  (build output kept: --keep-build)'
}
Write-Info ''
if ($optRemoveData) {
    Write-Info 'It will ALSO OFFER TO DELETE YOUR DATA:'
    Write-Info ('  ' + $propertiesPath + '   - your settings')
    Write-Info ('  ' + $databasePath + '   - your live point-of-sale database:')
    Write-Info '      products, prices, customers and every sale ever recorded'
    Write-Info '  You will have to type a confirmation phrase for that step.'
} else {
    Write-Info 'This will KEEP:'
    Write-Info ('  ' + $propertiesPath + '   - your settings')
    Write-Info ('  ' + $databasePath + '   - your live point-of-sale database:')
    Write-Info '      products, prices, customers and every sale ever recorded'
    Write-Info '  Re-installing regenerates any stale driver path in the settings file,'
    Write-Info '  which is why the settings file is deliberately left alone.'
}
Write-Info ''
Write-Info ('The source folder itself (' + $Root + ') is NOT removed.')
Write-Info ''

# ---------------------------------------------------------------------------
# 2. Refuse while BIZAPP POS is running. Derby holds file locks on .derby-db
#    and a half-finished removal could corrupt it.
# ---------------------------------------------------------------------------

Write-Step 'Checking that BIZAPP POS is not running'

$running = New-Object System.Collections.ArrayList
$javaProcesses = @()
try {
    $javaProcesses = @(Get-CimInstance -ClassName Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue)
} catch {
    try {
        $javaProcesses = @(Get-WmiObject -Class Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue)
    } catch {
        $javaProcesses = @()
    }
}
foreach ($process in $javaProcesses) {
    $commandLine = Get-PropValue $process 'CommandLine'
    if ($null -eq $commandLine) { continue }
    if ([string]$commandLine -like '*nordpos.jar*') {
        [void]$running.Add([string](Get-PropValue $process 'ProcessId'))
    }
}

if ($running.Count -gt 0) {
    Write-ErrLine ('BIZAPP POS is still running (PID ' + ($running -join ', ') + '). Close it and try again.')
    Write-Hint 'The database is locked while the application is open, and removing files now'
    Write-Hint 'could corrupt it.'
    exit 1
}

# ---------------------------------------------------------------------------
# 3. Confirm.
# ---------------------------------------------------------------------------

if (-not $script:AssumeYes) {
    if (-not (Test-Interactive)) {
        Write-ErrLine 'uninstall needs confirmation but is not running interactively.'
        Write-Hint 'Re-run it with --yes if you are sure.'
        exit 9
    }
    if (-not (Read-YesNo 'Continue?')) {
        Write-Info 'Nothing was removed.'
        exit 0
    }
}

# ---------------------------------------------------------------------------
# 4. Remove. Every step is guarded by Test-Path, so re-running is a no-op.
# ---------------------------------------------------------------------------

Write-Step 'Removing shortcuts, logs and build output'

if ($null -ne $startMenu)   { Remove-BizappItem $startMenu   'Start Menu shortcut' }
if ($null -ne $desktopLink) { Remove-BizappItem $desktopLink 'Desktop shortcut' }
Remove-BizappItem $logDir 'log folder'

# The logging configuration setup generates next to the log folder, with this
# machine's real log directory resolved into it (Write-BizappLoggingConfig in
# Install-BizappPos.ps1, which is its only writer). It is setup's own file,
# never anything the user wrote, so it goes with the logs; without this the
# parent below would never be empty and would be left behind. It is listed in
# the plan in section 1 above.
Remove-BizappItem $logConfig 'logging configuration'

# Tidy up the now-empty parent, but only if it really is empty.
if (Test-Path -LiteralPath $appDataDir) {
    try {
        $leftOver = @(Get-ChildItem -LiteralPath $appDataDir -Force)
        if ($leftOver.Count -eq 0) { Remove-Item -LiteralPath $appDataDir -Force }
    } catch { }
}

if (-not $optKeepBuild) {
    Remove-BizappItem $buildDir 'build folder'
    Remove-BizappItem $distDir  'dist folder'
}

# ---------------------------------------------------------------------------
# 5. Optional data removal, behind a typed confirmation. --yes does NOT
#    bypass this.
# ---------------------------------------------------------------------------

if ($optRemoveData) {
    Write-Info ''
    Write-Info '================================ WARNING ================================'
    Write-Info 'You asked to delete your BIZAPP POS data. This removes:'
    Write-Info ('  ' + $propertiesPath)
    Write-Info ('  ' + $databasePath)
    Write-Info 'That database holds every product, price, customer and sale you have ever'
    Write-Info 'recorded. It cannot be recovered afterwards.'
    Write-Info '========================================================================='
    Write-Info ''

    if (-not (Test-Interactive)) {
        Write-ErrLine 'deleting your data needs a typed confirmation, and this is not an interactive session.'
        Write-Hint 'Your data was kept. Run uninstall.cmd --remove-data from a console window.'
        exit 9
    }

    [Console]::Error.Write('Type exactly DELETE MY DATA to confirm: ')
    $typed = $null
    try { $typed = [Console]::ReadLine() } catch { $typed = $null }
    [Console]::Error.WriteLine('')

    # Case-sensitive comparison on purpose.
    if (($null -eq $typed) -or ($typed -cne 'DELETE MY DATA')) {
        Write-Info 'Aborted. Your data was kept.'
        Write-Info 'Everything else listed above was removed.'
        # 9 is the declined-confirmation code (see INSTALL.md), and the macOS
        # twin in install/macos/uninstall.command exits 9 here too. A support
        # script has to be able to tell "data deleted" from "not confirmed",
        # and the documented exit codes are shared across both platforms.
        exit 9
    }

    Write-Step 'Removing your settings and database'
    Remove-BizappItem $propertiesPath 'settings file'
    Remove-BizappItem $databasePath   'database folder'
}

# ---------------------------------------------------------------------------
# 6. Close.
# ---------------------------------------------------------------------------

Write-Info ''
Write-Step 'Done'
if (-not $optRemoveData) {
    Write-Info 'Your settings and your database were kept:'
    Write-Info ('  ' + $propertiesPath)
    Write-Info ('  ' + $databasePath)
    Write-Info ''
}
Write-Info ('The source folder was not removed. Delete it yourself if you want it gone:')
Write-Info ('  ' + $Root)
exit 0
