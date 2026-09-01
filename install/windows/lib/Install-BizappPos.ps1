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
# hand-traced rather than executed. If it misbehaves, please report:
#   * the exact console text,
#   * the contents of %LOCALAPPDATA%\BIZAPP POS\logs\install.log,
#   * the output of:  powershell -NoProfile -Command "$PSVersionTable.PSVersion"
#   * the output of:  where java & where javac
# The single most likely thing to be wrong is the .lnk argument round-trip in
# New-BizappShortcut; it verifies itself and degrades gracefully, but the
# degraded path is also untested.
# ---------------------------------------------------------------------------
#
# Windows PowerShell 5.1 ONLY. No ternary operator, no ??, no -Parallel,
# no utf8NoBOM, no pwsh-only cmdlets.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$BizappInstallerVersion = '1.0.0'
$BizappProductVersion   = '4.0'

$script:Quiet        = $false
$script:LogFile      = $null
$script:LogDir       = $null
$script:LogConfig    = $null
$script:NativeExit   = 0
$script:AssumeYes    = $false
$global:LASTEXITCODE = 0

# ---------------------------------------------------------------------------
# Output helpers.
#
# Human output goes to REAL stderr with [Console]::Error.WriteLine so that a
# caller doing  $out = & powershell.exe -File ... Install-BizappPos.ps1
# captures nothing but the machine-readable lines, and so that progress noise
# can never corrupt a parsed value. Write-Host would bypass redirection and
# Write-Output would land on stdout; neither is acceptable here.
#
# Every line is also teed into install.log. Start-Transcript is deliberately
# NOT used: it hooks the PowerShell host's output methods and therefore does
# not capture direct [Console] writes at all.
# ---------------------------------------------------------------------------

function Write-Log {
    param([string]$Text)
    if ($null -eq $script:LogFile) { return }
    try {
        $enc = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::AppendAllText($script:LogFile, $Text + "`r`n", $enc)
    } catch {
        # Logging must never be the reason an install fails.
    }
}

function Write-Step {
    param([string]$Text)
    $line = '==> ' + $Text
    if (-not $script:Quiet) { [Console]::Error.WriteLine($line) }
    Write-Log $line
}

function Write-Info {
    param([string]$Text)
    [Console]::Error.WriteLine($Text)
    Write-Log $Text
}

function Write-WarnLine {
    param([string]$Text)
    $line = '[warn] ' + $Text
    [Console]::Error.WriteLine($line)
    Write-Log $line
}

function Write-ErrLine {
    param([string]$Text)
    $line = '[error] ' + $Text
    [Console]::Error.WriteLine($line)
    Write-Log $line
}

function Write-Hint {
    param([string]$Text)
    $line = '        ' + $Text
    [Console]::Error.WriteLine($line)
    Write-Log $line
}

function Stop-Setup {
    param([int]$Code)
    Write-Log ('--- exit ' + $Code + ' at ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
    exit $Code
}

# ---------------------------------------------------------------------------
# Small utilities.
# ---------------------------------------------------------------------------

# Reads an environment variable without ever tripping Set-StrictMode.
function Get-Env {
    param([string]$Name)
    return [System.Environment]::GetEnvironmentVariable($Name)
}

# StrictMode-safe property read. Also protects against the shared Jdk.psm1
# returning an object shaped slightly differently than expected.
function Get-PropValue {
    param($InputObject, [string]$Name)
    if ($null -eq $InputObject) { return $null }
    $prop = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

# Trailing backslashes are removed because a path ending in \ that is then
# wrapped in double quotes on a command line escapes the closing quote:
#   -Ddirname.path="C:\repo\"   parses as   -Ddirname.path=C:\repo"
# A drive root such as C:\ is left alone (and rejected separately).
function Get-CleanPath {
    param([string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return $Path }
    $result = $Path
    while ($result.Length -gt 3 -and $result.EndsWith('\')) {
        $result = $result.Substring(0, $result.Length - 1)
    }
    return $result
}

# A value written into a .properties file. A backslash is an escape character
# there, and java.util.Properties.load reads the stream as ISO-8859-1 whatever
# the platform charset is, so anything outside printable ASCII is written as a
# \uXXXX escape. The result is pure ASCII by construction.
function ConvertTo-BizappPropertiesValue {
    param([string]$Text)
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $Text.ToCharArray()) {
        $code = [int][char]$ch
        if ($ch -eq '\') {
            [void]$sb.Append('\\')
        } elseif ($code -lt 0x20 -or $code -gt 0x7E) {
            [void]$sb.Append('\u' + $code.ToString('x4'))
        } else {
            [void]$sb.Append($ch)
        }
    }
    return $sb.ToString()
}

# ---------------------------------------------------------------------------
# The resolved logging configuration.
#
# install\windows\lib\logging.properties can only name the log directory as
# %h/AppData/Local/..., where %h is the JVM's user.home. That is a DIFFERENT
# value from %LOCALAPPDATA% on any machine whose Local AppData is redirected or
# roaming, and FileHandler does not create parent directories: it throws
# NoSuchFileException on its .lck file and a javaw-launched app then logs
# NOTHING - which is exactly the diagnostic a silent failure depends on.
#
# So the real directory is resolved once, here, and written into a generated
# copy of the file that every launch path points at. Returns the path of the
# generated file, or $null if it could not be written.
# ---------------------------------------------------------------------------
function Write-BizappLoggingConfig {
    param([string]$Directory, [string]$LogDirectory)

    if ([string]::IsNullOrEmpty($Directory))    { return $null }
    if ([string]::IsNullOrEmpty($LogDirectory)) { return $null }

    $pattern = ConvertTo-BizappPropertiesValue ($LogDirectory.Replace('\', '/').TrimEnd('/') + '/bizapp-%g.log')
    $target = Join-Path $Directory 'logging.properties'

    $lines = New-Object System.Collections.ArrayList
    # This function is the ONLY writer of that file. install\windows\run.cmd used
    # to regenerate it with an echo block, which could not do the \uXXXX escaping
    # ConvertTo-BizappPropertiesValue does above - so on a profile holding a
    # non-ASCII character it wrote a broken file OVER this correct one, and both
    # .lnk shortcuts point at it. run.cmd now only selects a file that exists.
    [void]$lines.Add('# BIZAPP POS - GENERATED FILE. Written by install.cmd (setup).')
    [void]$lines.Add('# Do not edit: the source is install/windows/lib/logging.properties.')
    [void]$lines.Add('# This copy exists to hold the log directory resolved to a real path,')
    [void]$lines.Add('# written with forward slashes because a backslash is an escape character.')
    [void]$lines.Add('handlers=java.util.logging.FileHandler')
    [void]$lines.Add('.level=INFO')
    [void]$lines.Add('java.util.logging.FileHandler.pattern=' + $pattern)
    [void]$lines.Add('java.util.logging.FileHandler.limit=2097152')
    [void]$lines.Add('java.util.logging.FileHandler.count=5')
    [void]$lines.Add('java.util.logging.FileHandler.append=true')
    [void]$lines.Add('java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter')

    try {
        $latin1 = [System.Text.Encoding]::GetEncoding(28591)
        [System.IO.File]::WriteAllText($target, (($lines.ToArray() -join "`r`n") + "`r`n"), $latin1)
    } catch {
        Write-Log ('could not write ' + $target + ': ' + $_.Exception.Message)
        return $null
    }
    return $target
}

# Native commands are invoked with $ErrorActionPreference temporarily relaxed.
# With 'Stop' in force, anything a native tool writes to stderr can surface as
# a terminating NativeCommandError; the tools called here (winget, netstat,
# powershell.exe running the build engine) all write to stderr routinely.
# Success is judged by $LASTEXITCODE, never by the absence of an exception.
function Invoke-NativeCapture {
    param([string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $captured = $null
    try {
        $captured = & $Exe @Arguments
        $script:NativeExit = $LASTEXITCODE
    } catch {
        $script:NativeExit = -1
        $captured = $null
    } finally {
        $ErrorActionPreference = $previous
    }
    return ,@($captured)
}

# Same, but the tool's own output stays on the console so the user can watch a
# long download instead of staring at a frozen window.
function Invoke-NativePassthru {
    param([string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments
        $script:NativeExit = $LASTEXITCODE
    } catch {
        $script:NativeExit = -1
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-Interactive {
    try {
        if ([Console]::IsInputRedirected) { return $false }
    } catch {
        # .NET older than 4.5 would not have IsInputRedirected; fall through.
    }
    try {
        if (-not [System.Environment]::UserInteractive) { return $false }
    } catch {
    }
    return $true
}

# Consent prompt. Defaults to No. Never returns $true without either an
# explicit flag or a typed answer.
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
    Write-Log ($Question + ' [y/N] ' + $answer)
    return ($answer -eq 'y' -or $answer -eq 'yes')
}

# Non-consent confirmation that defaults to Yes (used only to let someone stop
# after a port warning; it never authorises anything).
function Read-YesNoDefaultYes {
    param([string]$Question)
    if ($script:AssumeYes) { return $true }
    if (-not (Test-Interactive)) { return $true }
    [Console]::Error.Write($Question + ' [Y/n] ')
    $answer = $null
    try { $answer = [Console]::ReadLine() } catch { return $true }
    [Console]::Error.WriteLine('')
    if ($null -eq $answer) { return $true }
    $answer = $answer.Trim()
    Write-Log ($Question + ' [Y/n] ' + $answer)
    if ($answer -eq '') { return $true }
    return ($answer -eq 'y' -or $answer -eq 'yes')
}

# ---------------------------------------------------------------------------
# TCP port inspection. Get-NetTCPConnection exists on Windows 8 / Server 2012
# and later; netstat is the fallback for Windows 7.
# ---------------------------------------------------------------------------
function Get-PortListenerName {
    param([int]$Port)

    $names = New-Object System.Collections.ArrayList

    $cmd = Get-Command -Name 'Get-NetTCPConnection' -ErrorAction SilentlyContinue
    if ($null -ne $cmd) {
        $connections = @()
        try {
            $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
        } catch {
            $connections = @()
        }
        foreach ($connection in $connections) {
            $owner = Get-PropValue $connection 'OwningProcess'
            if ($null -eq $owner) { continue }
            $label = 'pid ' + $owner
            $process = Get-Process -Id ([int]$owner) -ErrorAction SilentlyContinue
            if ($null -ne $process) { $label = $process.ProcessName + ' (pid ' + $owner + ')' }
            [void]$names.Add($label)
        }
        return @($names | Select-Object -Unique)
    }

    # netstat fallback. The state word is localised on non-English Windows, so
    # it is not matched literally; a listener is recognised by a foreign
    # address ending in :0 instead.
    $lines = Invoke-NativeCapture 'netstat.exe' @('-ano')
    $pattern = '^\s*TCP\s+\S+:' + $Port + '\s+(\S+)\s+(\S+)\s+(\d+)\s*$'
    $regex = New-Object System.Text.RegularExpressions.Regex($pattern)
    foreach ($line in $lines) {
        $match = $regex.Match([string]$line)
        if (-not $match.Success) { continue }
        if (-not $match.Groups[1].Value.EndsWith(':0')) { continue }
        $owner = $match.Groups[3].Value
        $label = 'pid ' + $owner
        $process = Get-Process -Id ([int]$owner) -ErrorAction SilentlyContinue
        if ($null -ne $process) { $label = $process.ProcessName + ' (pid ' + $owner + ')' }
        [void]$names.Add($label)
    }
    return @($names | Select-Object -Unique)
}

# ---------------------------------------------------------------------------
# Look-and-feel repair. This is the ONLY write this installer ever makes to
# %USERPROFILE%\nordpos.properties, it is opt-in behind --repair-laf, it backs
# the file up first, and it changes exactly one line.
#
# The file is read and written as ISO-8859-1 (code page 28591). That is the
# encoding java.util.Properties.store() uses, and every byte 0x00-0xFF maps to
# exactly one character and back, so every untouched byte round-trips
# unchanged - including whatever line endings the file already has.
# ---------------------------------------------------------------------------

$script:LafKeyPattern = '(?m)^([ \t]*swing\.defaultlaf[ \t]*[=:])([^\r\n]*)'
$script:SafeLaf       = 'javax.swing.plaf.nimbus.NimbusLookAndFeel'

function Get-LafMatch {
    param([string]$PropertiesPath)
    if (-not (Test-Path -LiteralPath $PropertiesPath -PathType Leaf)) { return $null }
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    $text = $null
    try { $text = [System.IO.File]::ReadAllText($PropertiesPath, $latin1) } catch { return $null }
    $match = [System.Text.RegularExpressions.Regex]::Match($text, $script:LafKeyPattern)
    if (-not $match.Success) { return $null }
    return (New-Object PSObject -Property @{
        Text  = $text
        Match = $match
        Value = $match.Groups[2].Value.Trim()
    })
}

function Repair-LafFile {
    param([string]$PropertiesPath)

    $info = Get-LafMatch $PropertiesPath
    if ($null -eq $info) {
        Write-Info 'Nothing to repair: no saved settings file, or it does not set a look and feel.'
        Write-Info 'The settings file is never created by this installer.'
        return $true
    }
    if (-not $info.Value.StartsWith('org.pushingpixels.')) {
        Write-Info ('Nothing to repair: swing.defaultlaf is already ' + $info.Value)
        return $true
    }

    $directory = [System.IO.Path]::GetDirectoryName($PropertiesPath)
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
    $backup = Join-Path $directory ('nordpos.properties.bizapp-backup-' + $stamp)
    $suffix = 1
    while (Test-Path -LiteralPath $backup) {
        $backup = Join-Path $directory ('nordpos.properties.bizapp-backup-' + $stamp + '-' + $suffix)
        $suffix = $suffix + 1
    }

    try {
        Copy-Item -LiteralPath $PropertiesPath -Destination $backup -Force
    } catch {
        Write-ErrLine 'could not back up your settings file, so nothing was changed.'
        Write-Hint ('Tried to write: ' + $backup)
        Write-Hint $_.Exception.Message
        return $false
    }

    $match = $info.Match
    $replacement = 'swing.defaultlaf=' + $script:SafeLaf
    $newText = $info.Text.Substring(0, $match.Index) + $replacement +
               $info.Text.Substring($match.Index + $match.Length)

    $temp = Join-Path $directory ('nordpos.properties.bizapp-tmp-' + $PID)
    try {
        $latin1 = [System.Text.Encoding]::GetEncoding(28591)
        [System.IO.File]::WriteAllText($temp, $newText, $latin1)
        # Replace is as close to atomic as Windows offers and keeps the
        # original file's ACLs.
        [System.IO.File]::Replace($temp, $PropertiesPath, $null)
    } catch {
        Write-ErrLine 'could not rewrite your settings file. It was left untouched.'
        Write-Hint $_.Exception.Message
        Write-Hint ('Your backup is at: ' + $backup)
        if (Test-Path -LiteralPath $temp) {
            try { Remove-Item -LiteralPath $temp -Force } catch { }
        }
        return $false
    }

    # Only here, on the path that really rewrote the file. Repair-LafFile also
    # returns $true when there was nothing to repair, so its return value cannot
    # tell the summary whether setup changed anything in the home folder.
    # Mirrors laf_repaired in install/macos/install.command.
    $script:LafRepaired = $true

    Write-Info ('Repaired swing.defaultlaf in ' + $PropertiesPath)
    Write-Info ('  was: ' + $info.Value)
    Write-Info ('  now: ' + $script:SafeLaf)
    Write-Info ('  backup: ' + $backup)
    return $true
}

# ---------------------------------------------------------------------------
# Shortcut creation, with a mandatory read-back check.
#
# Returns the Arguments string read back from the saved .lnk, or $null if the
# shortcut could not be written at all.
# ---------------------------------------------------------------------------
function New-BizappShortcut {
    param(
        [string]$LinkPath,
        [string]$TargetPath,
        [string]$Arguments,
        [string]$WorkingDirectory,
        [string]$IconPath,
        [string]$Description
    )

    $directory = [System.IO.Path]::GetDirectoryName($LinkPath)
    if (-not [string]::IsNullOrEmpty($directory)) {
        try { [void][System.IO.Directory]::CreateDirectory($directory) } catch { }
    }

    $shell = $null
    $link = $null
    try {
        $shell = New-Object -ComObject WScript.Shell
        $link = $shell.CreateShortcut($LinkPath)
        $link.TargetPath       = $TargetPath
        $link.Arguments        = $Arguments
        $link.WorkingDirectory = $WorkingDirectory
        $link.Description      = $Description
        if (-not [string]::IsNullOrEmpty($IconPath)) {
            $link.IconLocation = $IconPath + ',0'
        }
        $link.Save()
    } catch {
        Write-WarnLine ('could not write the shortcut ' + $LinkPath)
        Write-Hint $_.Exception.Message
        return $null
    } finally {
        if ($null -ne $link)  { try { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($link) }  catch { } }
        if ($null -ne $shell) { try { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($shell) } catch { } }
    }

    # Re-open with a brand new WScript.Shell so the value comes off disk and
    # not out of a cached RCW.
    $shell2 = $null
    $link2 = $null
    $readBack = $null
    try {
        $shell2 = New-Object -ComObject WScript.Shell
        $link2 = $shell2.CreateShortcut($LinkPath)
        $readBack = [string]$link2.Arguments
    } catch {
        $readBack = $null
    } finally {
        if ($null -ne $link2)  { try { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($link2) }  catch { } }
        if ($null -ne $shell2) { try { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($shell2) } catch { } }
    }
    return $readBack
}

# ---------------------------------------------------------------------------
# The launch command. This is the single definition of how BIZAPP POS is
# started on Windows; the shortcuts and --launch both use it.
#
#  -Ddirname.path      AppConfig builds db.driverlib as
#                      <dirname.path>/lib-jdbc/derbyclient.jar and defaults to
#                      "./". When it is wrong the only symptom is a modal
#                      "Database driver not found" dialog.
#  -Dswing.defaultlaf  the shipped Substance CremeSkin throws a
#                      NullPointerException inside JRootFrame's constructor on
#                      every JDK 9 or newer and no window is ever drawn.
#  -Dfile.encoding     the default charset on a typical en-US Windows JDK up to
#                      17 is windows-1252 and the app ships non-ASCII resources.
#  -Xmx                set explicitly because the one real crash log in this
#                      repository is a heap exhaustion with no -Xmx.
#  -jar <jar>          the path the BUILD ENGINE reported, never the literal
#                      dist\nordpos.jar: --dist-dir and BIZAPP_DIST_DIR put the
#                      jar somewhere else, and a shortcut naming a jar that is
#                      not there runs javaw.exe, which has no console, so the
#                      failure is completely silent - no window, no dialog, no
#                      log. It is written RELATIVE to the checkout whenever it
#                      lives inside it: WorkingDirectory is the checkout, and
#                      the jar's manifest Class-Path keeps the command line
#                      short. The absolute 86-jar classpath measures over 8000
#                      characters, past cmd.exe's limit.
#
# There is deliberately NO -Dderby.system.home. ServerDatabase.java:40 sets that
# property itself from user.home on every start, so the flag never had any
# effect; carrying it only suggested the database could be moved with it.
#
# -Minimal drops the two arguments that are safely covered by other means, for
# use only if the .lnk round-trip check fails: without -Ddirname.path AppConfig
# falls back to "./", which resolves against WorkingDirectory = the checkout,
# and without -XX:ErrorFile the JVM writes its crash file into the working
# directory instead.
# ---------------------------------------------------------------------------

# The -jar value: relative to the checkout when the jar is inside it, absolute
# when it is not. Always quoted - --dist-dir may name a folder with a space in
# it, and an unquoted argument would then split into two.
function Get-BizappJarArgument {
    param([string]$RepoRoot, [string]$JarPath)
    $prefix = $RepoRoot.TrimEnd('\') + '\'
    if (($JarPath.Length -gt $prefix.Length) -and
        $JarPath.Substring(0, $prefix.Length).Equals($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return '"' + $JarPath.Substring($prefix.Length) + '"'
    }
    return '"' + $JarPath + '"'
}

function Get-BizappLaunchArgument {
    param(
        [string]$RepoRoot,
        [string]$JarPath,
        [string]$LogDirectory,
        [string]$LoggingConfig,
        [string]$ExtraJavaOptions,
        [switch]$Minimal
    )

    # The generated, path-resolved copy is preferred; the checked-in template is
    # the fallback for the rare case where it could not be written.
    $loggingFile = $LoggingConfig
    if ([string]::IsNullOrEmpty($loggingFile)) {
        $loggingFile = Join-Path $RepoRoot 'install\windows\lib\logging.properties'
    }

    $parts = New-Object System.Collections.ArrayList
    [void]$parts.Add('-Xms256m')
    [void]$parts.Add('-Xmx1024m')
    [void]$parts.Add('-Dfile.encoding=UTF-8')
    if (-not $Minimal) {
        [void]$parts.Add('-Ddirname.path="' + $RepoRoot + '"')
    }
    [void]$parts.Add('-Dswing.defaultlaf=' + $script:SafeLaf)
    [void]$parts.Add('-DKETTLE_PLUGIN_BASE_FOLDERS="' + (Join-Path $RepoRoot 'lib-ext\data-integration\plugins') + '"')
    [void]$parts.Add('-Djava.util.logging.config.file="' + $loggingFile + '"')
    if ((-not $Minimal) -and (-not [string]::IsNullOrEmpty($LogDirectory))) {
        [void]$parts.Add('-XX:ErrorFile="' + (Join-Path $LogDirectory 'hs_err_pid%p.log') + '"')
    }
    # BIZAPP_JAVA_OPTS goes in unquoted, so it word-splits, and last, so a value
    # such as -Xmx3072m wins over the -Xmx above. A .lnk cannot read an
    # environment variable at all, so only the --launch path passes it.
    if (-not [string]::IsNullOrEmpty($ExtraJavaOptions)) {
        [void]$parts.Add($ExtraJavaOptions.Trim())
    }
    [void]$parts.Add('-jar')
    [void]$parts.Add((Get-BizappJarArgument -RepoRoot $RepoRoot -JarPath $JarPath))
    return ($parts -join ' ')
}

function Show-Usage {
    [Console]::Error.WriteLine('BIZAPP POS setup ' + $BizappInstallerVersion + '  (BIZAPP POS ' + $BizappProductVersion + ')')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('Usage:  install.cmd [options]')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('Builds BIZAPP POS from this checkout and creates per-user Start Menu and')
    [Console]::Error.WriteLine('Desktop shortcuts. Nothing is installed system-wide and no administrator')
    [Console]::Error.WriteLine('password is needed unless you ask for a JDK to be installed.')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('Options:')
    [Console]::Error.WriteLine('  --install-jdk        install Eclipse Temurin with winget or Chocolatey if no')
    [Console]::Error.WriteLine('                       JDK is found. This installs software system-wide and')
    [Console]::Error.WriteLine('                       may prompt for administrator approval.')
    [Console]::Error.WriteLine('  --no-shortcuts       build only; do not create any shortcut')
    [Console]::Error.WriteLine('  --launch             start BIZAPP POS when setup finishes')
    [Console]::Error.WriteLine('  --repair-laf         fix a saved Substance look-and-feel setting that stops')
    [Console]::Error.WriteLine('                       the application from starting. Backs up your settings')
    [Console]::Error.WriteLine('                       file first and changes exactly one line.')
    [Console]::Error.WriteLine('  --clean              delete previous build output before building')
    [Console]::Error.WriteLine('  --build-dir <path>   intermediate build directory (default: build)')
    [Console]::Error.WriteLine('  --dist-dir <path>    output directory for nordpos.jar (default: dist)')
    [Console]::Error.WriteLine('  --jdk <path>         build and run with the JDK in <path> (the folder that')
    [Console]::Error.WriteLine('                       contains bin\javac.exe). Takes precedence over')
    [Console]::Error.WriteLine('                       BIZAPP_JDK_HOME, which takes precedence over JAVA_HOME.')
    [Console]::Error.WriteLine('  --yes                answer yes to consent prompts')
    [Console]::Error.WriteLine('  --quiet              suppress progress lines')
    [Console]::Error.WriteLine('  --help               show this text')
    [Console]::Error.WriteLine('  --version            print the setup version and exit')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('Your settings (%USERPROFILE%\nordpos.properties) and your database')
    [Console]::Error.WriteLine('(%USERPROFILE%\.derby-db) are never created, changed or deleted by setup,')
    [Console]::Error.WriteLine('with the single exception of --repair-laf.')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('Full documentation: INSTALL.md')
}

# ---------------------------------------------------------------------------
# Argument parsing. Done by hand rather than with param() because PowerShell
# cannot express a --double-dash parameter name. Both "--name value" and
# "--name=value" are accepted for every option that takes a value.
# ---------------------------------------------------------------------------

$optInstallJdk  = $false
$optNoShortcuts = $false
$optLaunch      = $false
$optRepairLaf   = $false
$optClean       = $false
$optHelp        = $false
$optVersion     = $false
$optBuildDir    = $null
$optDistDir     = $null
$optJdk         = $null

$argv = @($args)
$index = 0
while ($index -lt $argv.Count) {
    $token = [string]$argv[$index]
    $name  = $token
    $value = $null
    $split = $token.IndexOf('=')
    if ($split -ge 0) {
        $name  = $token.Substring(0, $split)
        $value = $token.Substring($split + 1)
    }

    $needsValue = ($name -eq '--build-dir' -or $name -eq '--dist-dir' -or $name -eq '--jdk')
    $exampleValue = 'build-alt'
    if ($name -eq '--jdk') { $exampleValue = '"C:\Program Files\Eclipse Adoptium\jdk-17"' }
    if ($needsValue -and ($null -eq $value)) {
        $index = $index + 1
        if ($index -ge $argv.Count) {
            Write-ErrLine ('option ' + $name + ' needs a value.')
            Write-Hint ('Example: install.cmd ' + $name + ' ' + $exampleValue)
            Stop-Setup 2
        }
        $value = [string]$argv[$index]
    }
    if ($needsValue -and [string]::IsNullOrEmpty($value)) {
        Write-ErrLine ('option ' + $name + ' needs a value.')
        Write-Hint ('Example: install.cmd ' + $name + ' ' + $exampleValue)
        Stop-Setup 2
    }

    switch ($name) {
        '--install-jdk'  { $optInstallJdk  = $true }
        '--no-shortcuts' { $optNoShortcuts = $true }
        '--no-shortcut'  { $optNoShortcuts = $true }
        '--launch'       { $optLaunch      = $true }
        '--repair-laf'   { $optRepairLaf   = $true }
        '--clean'        { $optClean       = $true }
        '--yes'          { $script:AssumeYes = $true }
        '--quiet'        { $script:Quiet   = $true }
        '--help'         { $optHelp        = $true }
        '-h'             { $optHelp        = $true }
        '/?'             { $optHelp        = $true }
        '--version'      { $optVersion     = $true }
        '--build-dir'    { $optBuildDir    = $value }
        '--dist-dir'     { $optDistDir     = $value }
        '--jdk'          { $optJdk         = $value }
        default {
            Write-ErrLine ('unknown option: ' + $token)
            Write-Hint 'Run  install.cmd --help  for the list of options.'
            Stop-Setup 2
        }
    }
    $index = $index + 1
}

if ($optVersion) {
    [Console]::Out.WriteLine($BizappInstallerVersion)
    exit 0
}
if ($optHelp) {
    Show-Usage
    exit 0
}

# ---------------------------------------------------------------------------
# Logging. Set up before anything that can fail, because install.cmd tells the
# user this file exists.
# ---------------------------------------------------------------------------

$userProfile = Get-Env 'USERPROFILE'
if ([string]::IsNullOrEmpty($userProfile)) {
    [Console]::Error.WriteLine('[error] USERPROFILE is not set, so setup cannot find your home folder.')
    [Console]::Error.WriteLine('        Run install.cmd from a normal user account rather than a service context.')
    exit 10
}

$localAppData = Get-Env 'LOCALAPPDATA'
if ([string]::IsNullOrEmpty($localAppData)) {
    $localAppData = Join-Path $userProfile 'AppData\Local'
}
$bizappDataDir = Join-Path $localAppData 'BIZAPP POS'
try {
    $script:LogDir = Join-Path $bizappDataDir 'logs'
    [void][System.IO.Directory]::CreateDirectory($script:LogDir)
    $script:LogFile = Join-Path $script:LogDir 'install.log'
} catch {
    $script:LogDir  = $null
    $script:LogFile = $null
}

Write-Log ''
Write-Log ('=== BIZAPP POS setup ' + $BizappInstallerVersion + ' started ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + ' ===')
Write-Log ('PowerShell ' + $PSVersionTable.PSVersion.ToString())
Write-Log ('arguments: ' + ($argv -join ' '))

Write-Step ('BIZAPP POS setup ' + $BizappInstallerVersion + '  (BIZAPP POS ' + $BizappProductVersion + ')')

# The application's own logging configuration, with this machine's real log
# directory baked in. Every launch path (shortcuts, --launch, run.cmd) points at
# this file, so all of them write to the one directory the summary advertises.
$script:LogConfig = Write-BizappLoggingConfig -Directory $bizappDataDir -LogDirectory $script:LogDir
if ($null -eq $script:LogConfig) {
    # Falling back to the checked-in template, which can only say
    # %h/AppData/Local/... - so make sure THAT directory exists too, or
    # FileHandler will fail on its lock file and the application will log
    # nothing at all.
    Write-Log 'using the checked-in logging.properties: the resolved copy could not be written'
    try {
        $profileFolder = [System.Environment]::GetFolderPath('UserProfile')
        if (-not [string]::IsNullOrEmpty($profileFolder)) {
            [void][System.IO.Directory]::CreateDirectory((Join-Path $profileFolder 'AppData\Local\BIZAPP POS\logs'))
        }
    } catch {
    }
}

# ---------------------------------------------------------------------------
# Repository root.
# ---------------------------------------------------------------------------

Write-Step 'Checking the checkout'

$Root = $null
try {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
} catch {
    Write-ErrLine 'setup could not work out where this checkout lives.'
    Write-Hint 'Run install.cmd from the folder git created, not from a copied script.'
    Stop-Setup 5
}
$Root = Get-CleanPath $Root

if ($Root.Length -le 3) {
    Write-ErrLine ('BIZAPP POS cannot be installed at a drive root (' + $Root + ').')
    Write-Hint 'Move the checkout into a folder, for example C:\BizappPOS, and try again.'
    Stop-Setup 10
}

# Measured worst case for a relative build output path inside this project is
# 108 characters, and Windows limits most paths to 260.
if ($Root.Length -gt 140) {
    Write-ErrLine ('the folder path is ' + $Root.Length + ' characters long. Windows limits most paths to 260')
    Write-Hint 'characters and BIZAPP POS needs about 120 of them. Move the checkout somewhere'
    Write-Hint 'shorter, for example C:\BizappPOS, and try again.'
    Stop-Setup 10
}

foreach ($required in @('nbproject\project.properties', 'install\build.ps1', 'install\lib\Jdk.psm1', 'src-pos', 'lib')) {
    $probe = Join-Path $Root $required
    if (-not (Test-Path -LiteralPath $probe)) {
        Write-ErrLine ('this does not look like a BIZAPP POS checkout: ' + $required + ' is missing.')
        Write-Hint 'Re-clone it with:'
        Write-Hint '    git clone https://github.com/krapuleng/pointofsale.git'
        Stop-Setup 5
    }
}
Write-Log ('repository root: ' + $Root)

# ---------------------------------------------------------------------------
# Find a JDK.
# ---------------------------------------------------------------------------

Write-Step 'Looking for a Java Development Kit'

try {
    Import-Module -Name (Join-Path $Root 'install\lib\Jdk.psm1') -Force -DisableNameChecking
} catch {
    Write-ErrLine 'setup could not load install\lib\Jdk.psm1.'
    Write-Hint $_.Exception.Message
    Write-Hint 'Re-clone the repository, or report this with the message above.'
    Stop-Setup 5
}

# AUTO-DISCOVERY ONLY. Find-Jdk searches the machine and ranks what it finds by
# release; it does not implement the --jdk / BIZAPP_JDK_HOME / JAVA_HOME
# precedence, and calling it first would silently ignore an explicit choice.
# That precedence is applied by the caller below, exactly as install\build.ps1
# applies it at its own step 3.
function Find-BizappJdk {
    $found = $null
    try {
        $found = @(Find-Jdk -MinMajor 11 -RequireJavac) | Select-Object -First 1
    } catch {
        Write-Log ('Find-Jdk failed: ' + $_.Exception.Message)
        $found = $null
    }
    return $found
}

# The way out of an explicit JDK that does not work. Precedence means an
# explicit choice is never quietly replaced, so a stale or JRE-pointing
# JAVA_HOME left over from years ago now stops setup dead (exit 3 or 4) where it
# used to fall through to auto-discovery. That is the correct behaviour - but
# only if the message says how to hand the search back to setup, and it has to
# name the thing that actually set the JDK, because clearing JAVA_HOME does
# nothing when the value came from --jdk or from BIZAPP_JDK_HOME.
function Show-JdkSourceRemedy {
    param([string]$Source)

    if ($Source -eq '--jdk') {
        Write-Hint 'Or run install.cmd without --jdk to let setup search for a JDK itself.'
    } else {
        Write-Hint ('Or clear ' + $Source + ' (setx ' + $Source + ' "") to let setup search for a')
        Write-Hint 'JDK itself, then open a NEW window and run install.cmd again.'
    }
}

# An explicitly named JDK: taken as given, never re-ranked, and reported against
# the name of whatever set it so the message says what to change.
function Get-BizappExplicitJdk {
    param([string]$JdkHome, [string]$Source)

    $home_ = Get-CleanPath ($JdkHome.Trim().Trim('"'))
    if (-not (Test-Path -LiteralPath $home_ -PathType Container)) {
        Write-ErrLine ('the JDK location from ' + $Source + ' does not exist: ' + $home_)
        Write-Hint 'Point it at the folder that contains bin\javac.exe.'
        Show-JdkSourceRemedy $Source
        Stop-Setup 3
    }

    $candidate = $null
    try {
        $candidate = Test-JdkCandidate -Home $home_
    } catch {
        Write-Log ('Test-JdkCandidate failed: ' + $_.Exception.Message)
        $candidate = $null
    }
    if ($null -eq $candidate) {
        Write-ErrLine ('the JDK at ' + $home_ + ' (from ' + $Source + ') is not usable.')
        Write-Hint 'It must contain bin\javac.exe, bin\java.exe, bin\javaw.exe and bin\jar.exe.'
        Write-Hint 'A Java runtime (JRE) is not enough - install a full JDK:'
        Write-Hint '    winget install --id EclipseAdoptium.Temurin.17.JDK -e'
        Write-Hint '    or download from https://adoptium.net'
        Show-JdkSourceRemedy $Source
        Stop-Setup 3
    }

    $major = Get-PropValue $candidate 'Major'
    if (($null -ne $major) -and ([int]$major -lt 11)) {
        Write-ErrLine ('JDK 11 or newer is required (found ' + $major + ' at ' + $home_ + ', from ' + $Source + ').')
        Write-Hint 'Install a newer one:'
        Write-Hint '    winget install --id EclipseAdoptium.Temurin.17.JDK -e'
        Write-Hint '    or download from https://adoptium.net'
        Show-JdkSourceRemedy $Source
        Stop-Setup 4
    }
    return $candidate
}

function Show-JdkAdvice {
    Write-ErrLine 'No Java Development Kit (JDK) was found.'
    Write-Hint 'BIZAPP POS needs a JDK, which includes the Java compiler, not just a Java'
    Write-Hint 'runtime. If "java -version" works but this still fails, you have a runtime'
    Write-Hint 'only. On Windows that is usually C:\ProgramData\Oracle\Java\javapath, which'
    Write-Hint 'contains java.exe but no javac.exe.'
    Write-Hint 'Install one:'
    Write-Hint '    winget install --id EclipseAdoptium.Temurin.17.JDK -e'
    Write-Hint '    or download from https://adoptium.net'
    Write-Hint 'Already installed? Point JAVA_HOME at the folder that contains bin\javac.exe,'
    Write-Hint 'then open a NEW window and run install.cmd again:'
    Write-Hint '    setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"'
}

# Precedence, identical on both platforms and in the build engine:
#   --jdk  >  BIZAPP_JDK_HOME  >  JAVA_HOME  >  auto-discovery.
$jdkSource    = ''
$explicitHome = ''
if (-not [string]::IsNullOrEmpty($optJdk)) {
    $explicitHome = [string]$optJdk
    $jdkSource    = '--jdk'
}
if ([string]::IsNullOrEmpty($explicitHome)) {
    $fromEnv = Get-Env 'BIZAPP_JDK_HOME'
    if (-not [string]::IsNullOrEmpty($fromEnv)) {
        $explicitHome = $fromEnv
        $jdkSource    = 'BIZAPP_JDK_HOME'
    }
}
if ([string]::IsNullOrEmpty($explicitHome)) {
    $fromEnv = Get-Env 'JAVA_HOME'
    if (-not [string]::IsNullOrEmpty($fromEnv)) {
        $explicitHome = $fromEnv
        $jdkSource    = 'JAVA_HOME'
    }
}

$jdk = $null
if (-not [string]::IsNullOrEmpty($explicitHome)) {
    # An explicit choice is honoured or reported - never quietly replaced by a
    # different JDK, and never grounds for installing another one.
    $jdk = Get-BizappExplicitJdk -JdkHome $explicitHome -Source $jdkSource
} else {
    $jdkSource = 'auto-discovery'
    $jdk = Find-BizappJdk
}

if ($null -eq $jdk) {
    Show-JdkAdvice

    $winget = Get-Command -Name 'winget.exe' -ErrorAction SilentlyContinue
    $choco  = Get-Command -Name 'choco.exe'  -ErrorAction SilentlyContinue

    if (($null -eq $winget) -and ($null -eq $choco)) {
        Write-Hint 'Neither winget nor Chocolatey is available, so setup cannot install it for you.'
        Stop-Setup 3
    }

    $consent = $false
    if ($optInstallJdk) {
        $consent = $true
    } elseif (Test-Interactive) {
        Write-Info ''
        Write-Info 'Setup can install Eclipse Temurin JDK 17 for you.'
        Write-Info 'This installs software SYSTEM-WIDE and may ask for an administrator password.'
        Write-Info 'Nothing else on your machine is changed, and no firewall or PATH setting is touched.'
        $consent = Read-YesNo 'Install Eclipse Temurin JDK 17 now?'
        if (-not $consent) {
            Write-ErrLine 'a JDK is required and installing one was declined.'
            Write-Hint 'Install it yourself using one of the commands above, then run install.cmd again.'
            Stop-Setup 9
        }
    } else {
        Write-Hint 'Setup is not running interactively, so it will not install anything by itself.'
        Write-Hint 'Install a JDK using one of the commands above, or re-run with --install-jdk.'
        Stop-Setup 9
    }

    if ($consent) {
        if ($null -ne $winget) {
            Write-Step 'Installing Eclipse Temurin JDK 17 with winget (this can take several minutes)'
            Invoke-NativePassthru 'winget.exe' @(
                'install', '--id', 'EclipseAdoptium.Temurin.17.JDK', '-e',
                '--accept-source-agreements', '--accept-package-agreements',
                '--disable-interactivity'
            )
        } else {
            Write-Step 'Installing Eclipse Temurin JDK 17 with Chocolatey (this can take several minutes)'
            Invoke-NativePassthru 'choco.exe' @('install', 'temurin17', '-y')
        }
        # The exit code is deliberately not trusted: winget returns
        # informational non-zero codes such as 0x8A150011 for "already
        # installed". Whether it worked is decided by looking for the JDK again.
        Write-Log ('package manager exit code: ' + $script:NativeExit)

        # This process cannot receive the WM_SETTINGCHANGE broadcast, so PATH
        # is rebuilt by hand from the registry-backed values.
        Write-Step 'Refreshing PATH for this window'
        try {
            $machinePath = [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
            $userPath    = [System.Environment]::GetEnvironmentVariable('Path', 'User')
            $combined = @(@($machinePath, $userPath) | Where-Object { -not [string]::IsNullOrEmpty($_) })
            if ($combined.Count -gt 0) {
                [System.Environment]::SetEnvironmentVariable('Path', ($combined -join ';'))
            }
        } catch {
            Write-Log ('PATH refresh failed: ' + $_.Exception.Message)
        }

        $jdk = Find-BizappJdk
    }
}

if ($null -eq $jdk) {
    Write-ErrLine 'the JDK was installed but setup cannot see it yet.'
    Write-Hint 'Close this window, open a new one, and run install.cmd again.'
    Write-Hint 'If it still is not found, set JAVA_HOME to the folder containing bin\javac.exe.'
    Stop-Setup 3
}

$jdkHome  = Get-CleanPath ([string](Get-PropValue $jdk 'Home'))
$jdkMajor = Get-PropValue $jdk 'Major'
$jdkJavaw = [string](Get-PropValue $jdk 'Javaw')
$jdkJava  = [string](Get-PropValue $jdk 'Java')

if ([string]::IsNullOrEmpty($jdkHome)) {
    Write-ErrLine 'the JDK discovery module returned an entry with no home directory.'
    Write-Hint 'Set JAVA_HOME to a JDK folder containing bin\javac.exe and try again.'
    Stop-Setup 3
}
if ([string]::IsNullOrEmpty($jdkJavaw)) { $jdkJavaw = Join-Path $jdkHome 'bin\javaw.exe' }
if ([string]::IsNullOrEmpty($jdkJava))  { $jdkJava  = Join-Path $jdkHome 'bin\java.exe' }

Write-Info ('Using JDK ' + $jdkMajor + ' at ' + $jdkHome + '  (from ' + $jdkSource + ')')
Write-Log  ('javaw: ' + $jdkJavaw)

# ---------------------------------------------------------------------------
# Build, using the shared engine. No build logic lives here.
#
# The engine runs as a SEPARATE powershell.exe process on purpose: that is what
# makes its "exit N" arrive as $LASTEXITCODE. Only stdout is captured; all of
# the engine's human output flows straight to this console.
# ---------------------------------------------------------------------------

Write-Step 'Building BIZAPP POS (this takes about 30 seconds)'

$buildScript = Join-Path $Root 'install\build.ps1'
if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) {
    Write-ErrLine 'this does not look like a BIZAPP POS checkout: install\build.ps1 is missing.'
    Write-Hint 'Re-clone it with:  git clone https://github.com/krapuleng/pointofsale.git'
    Stop-Setup 5
}

$psExe = 'powershell.exe'
$systemRoot = Get-Env 'SystemRoot'
if (-not [string]::IsNullOrEmpty($systemRoot)) {
    $absolutePs = Join-Path $systemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path -LiteralPath $absolutePs -PathType Leaf) { $psExe = $absolutePs }
}

$buildArgs = New-Object System.Collections.ArrayList
[void]$buildArgs.Add('-NoProfile')
[void]$buildArgs.Add('-NoLogo')
[void]$buildArgs.Add('-ExecutionPolicy')
[void]$buildArgs.Add('Bypass')
[void]$buildArgs.Add('-File')
[void]$buildArgs.Add($buildScript)
[void]$buildArgs.Add('--jdk')
[void]$buildArgs.Add($jdkHome)
if (-not [string]::IsNullOrEmpty($optBuildDir)) {
    [void]$buildArgs.Add('--build-dir')
    [void]$buildArgs.Add((Get-CleanPath $optBuildDir))
}
if (-not [string]::IsNullOrEmpty($optDistDir)) {
    [void]$buildArgs.Add('--dist-dir')
    [void]$buildArgs.Add((Get-CleanPath $optDistDir))
}
if ($optClean)     { [void]$buildArgs.Add('--clean') }
if ($script:Quiet) { [void]$buildArgs.Add('--quiet') }

Write-Log ('build command: ' + $psExe + ' ' + ($buildArgs -join ' '))
Write-Info 'The build engine writes its progress to this window; it is not copied into install.log.'

$buildOutput = Invoke-NativeCapture $psExe ([string[]]$buildArgs.ToArray())
$buildExit = $script:NativeExit

if ($buildExit -ne 0) {
    Write-ErrLine 'build failed.'
    # The engine's exit code is propagated unchanged, as required by the
    # build contract. It is not remapped and nothing is added to it.
    Stop-Setup $buildExit
}

$jarPath = ''
foreach ($line in $buildOutput) {
    $text = [string]$line
    if ($text.StartsWith('BIZAPP_JAR=')) {
        $jarPath = $text.Substring('BIZAPP_JAR='.Length).Trim()
        break
    }
}

if ([string]::IsNullOrEmpty($jarPath)) {
    Write-ErrLine 'the build reported success but did not say where it put nordpos.jar.'
    Write-Hint 'Run the build engine on its own to see what happened:'
    Write-Hint ('    powershell -NoProfile -ExecutionPolicy Bypass -File "' + $buildScript + '" --verify')
    Stop-Setup 7
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    Write-ErrLine ('the build reported ' + $jarPath + ' but that file does not exist.')
    Write-Hint 'Try again with:  install.cmd --clean'
    Stop-Setup 7
}

# The engine reports whatever --dist-dir it was given, which may be relative to
# the directory setup was started from. The shortcuts and --launch run with a
# different working directory, so it is made absolute here, once, and every
# later use is of the absolute form.
try {
    $resolvedJar = (Resolve-Path -LiteralPath $jarPath).Path
    if (-not [string]::IsNullOrEmpty($resolvedJar)) { $jarPath = $resolvedJar }
} catch {
    Write-Log ('could not resolve ' + $jarPath + ' to a full path: ' + $_.Exception.Message)
}

Write-Info ('Built: ' + $jarPath)

# ---------------------------------------------------------------------------
# Port preflight.
# ---------------------------------------------------------------------------

Write-Step 'Checking network port 1527 (the bundled database)'

$listeners = @()
try { $listeners = @(Get-PortListenerName 1527) } catch { $listeners = @() }

if ($listeners.Count -gt 0) {
    Write-WarnLine ('TCP port 1527 is in use by: ' + ($listeners -join ', '))
    Write-Hint 'If that is another copy of BIZAPP POS or an Apache Derby server, this is fine -'
    Write-Hint 'they share one database.'
    Write-Hint 'If it is anything else, BIZAPP POS will hang at startup with no window.'
    Write-Hint 'Stop that program first.'
    if (-not (Read-YesNoDefaultYes 'Continue with the installation?')) {
        Write-Info 'Stopped at your request. Nothing was installed; the build output was kept.'
        Stop-Setup 9
    }
}

# ---------------------------------------------------------------------------
# Look and feel.
# ---------------------------------------------------------------------------

# NOT %USERPROFILE%. The JVM builds user.home from GetUserProfileDirectory() on
# the process token, and ServerDatabase.java derives the database location from
# user.home, so where the two disagree - a runas, some roaming and redirected
# setups - %USERPROFILE% names a folder the application never opens. That is not
# cosmetic: the first-run advice below reads Test-Path on this path, and getting
# it wrong prints "A dialog asks whether to create the database. Click Yes." to
# somebody whose live database is sitting in the other folder, waiting to be
# offered the UPGRADE that deletes every parked ticket. GetFolderPath first,
# %USERPROFILE% only as the fallback - the same pair the logging fallback near
# the top of this script already uses.
$jvmUserHome = ''
try { $jvmUserHome = [System.Environment]::GetFolderPath('UserProfile') } catch { $jvmUserHome = '' }
if ([string]::IsNullOrEmpty($jvmUserHome)) { $jvmUserHome = $userProfile }

$propertiesPath = Join-Path $jvmUserHome 'nordpos.properties'
$databasePath   = Join-Path $jvmUserHome '.derby-db'

# Set when the application is known to be unable to start. It is repeated at the
# top of the summary and it suppresses --launch: -Dswing.defaultlaf cannot save
# this case, because AppConfig.load() overlays the saved file on top of the
# defaults, so the file's value always wins.
$lafBlocked = $false

# Set by Repair-LafFile, and only when it really rewrote the file. The summary
# reads it: a run that just edited nordpos.properties must not go on to say that
# setup never touches anything in the home folder.
$script:LafRepaired = $false

if ($optRepairLaf) {
    Write-Step 'Repairing the saved look-and-feel setting'
    if (-not (Repair-LafFile $propertiesPath)) { Stop-Setup 7 }
} else {
    $lafInfo = Get-LafMatch $propertiesPath
    if (($null -ne $lafInfo) -and $lafInfo.Value.StartsWith('org.pushingpixels.')) {
        $lafBlocked = $true
        Write-WarnLine 'Your saved settings select a Substance look and feel, which crashes on Java 9 and newer.'
        Write-Hint 'BIZAPP POS will not start until this is changed.'
        Write-Hint 'Re-run this installer with --repair-laf to fix just that one setting'
        Write-Hint '(a backup is made first):'
        Write-Hint '    install.cmd --repair-laf'
    }
}

# ---------------------------------------------------------------------------
# Shortcuts.
# ---------------------------------------------------------------------------

$startMenuLink = $null
$desktopLink   = $null

if ($optNoShortcuts) {
    Write-Step 'Skipping shortcuts (--no-shortcuts)'
} else {
    Write-Step 'Creating Start Menu and Desktop shortcuts'

    # Said out loud rather than ignored in silence: the variable works, but not
    # here. Without this the setting simply appears to do nothing on Windows.
    $shortcutJavaOptions = Get-Env 'BIZAPP_JAVA_OPTS'
    if (-not [string]::IsNullOrEmpty($shortcutJavaOptions)) {
        Write-WarnLine 'BIZAPP_JAVA_OPTS is set, but a shortcut stores a fixed argument string and cannot read it.'
        Write-Hint 'The shortcuts keep the options built in below. To use it, start BIZAPP POS with:'
        Write-Hint ('    "' + (Join-Path $Root 'install\windows\run.cmd') + '"')
    }

    if ($null -ne $script:LogDir) {
        try { [void][System.IO.Directory]::CreateDirectory($script:LogDir) } catch { }
    }

    $iconPath = Join-Path $Root 'install\windows\lib\bizapp.ico'
    if (-not (Test-Path -LiteralPath $iconPath -PathType Leaf)) { $iconPath = '' }

    # No BIZAPP_JAVA_OPTS here: a .lnk stores a fixed argument string and cannot
    # read an environment variable, so baking today's value in would be a lie.
    $launchArgs = Get-BizappLaunchArgument -RepoRoot $Root -JarPath $jarPath `
        -LogDirectory $script:LogDir -LoggingConfig $script:LogConfig
    Write-Log ('shortcut arguments: ' + $launchArgs)

    $targets = New-Object System.Collections.ArrayList

    # APPDATA is read once and defaulted, because Join-Path throws on a null path.
    $roaming = Get-Env 'APPDATA'
    if ([string]::IsNullOrEmpty($roaming)) { $roaming = Join-Path $userProfile 'AppData\Roaming' }
    $startMenuDir = Join-Path $roaming 'Microsoft\Windows\Start Menu\Programs'
    [void]$targets.Add((Join-Path $startMenuDir 'BIZAPP POS.lnk'))

    # GetFolderPath is mandatory here: OneDrive Known Folder Move relocates the
    # Desktop, so %USERPROFILE%\Desktop is often the wrong place.
    $desktopDir = ''
    try {
        $desktopDir = [System.Environment]::GetFolderPath('Desktop')
        if ([string]::IsNullOrEmpty($desktopDir)) {
            $desktopDir = [System.Environment]::GetFolderPath('DesktopDirectory')
        }
    } catch {
        $desktopDir = ''
    }
    if (-not [string]::IsNullOrEmpty($desktopDir)) {
        [void]$targets.Add((Join-Path $desktopDir 'BIZAPP POS.lnk'))
    }

    foreach ($linkPath in $targets) {
        $readBack = New-BizappShortcut -LinkPath $linkPath -TargetPath $jdkJavaw `
            -Arguments $launchArgs -WorkingDirectory $Root -IconPath $iconPath `
            -Description 'BIZAPP POS point of sale'

        if ($null -eq $readBack) { continue }

        if ($readBack -cne $launchArgs) {
            # Known risk area: .lnk argument round-tripping through COM can
            # mangle nested quotes. Retry with the two arguments that other
            # settings already cover.
            Write-WarnLine 'the shortcut mangled its arguments; falling back to a simpler form.'
            Write-Log ('wrote: ' + $launchArgs)
            Write-Log ('read : ' + $readBack)

            $simpleArgs = Get-BizappLaunchArgument -RepoRoot $Root -JarPath $jarPath `
                -LogDirectory $script:LogDir -LoggingConfig $script:LogConfig -Minimal
            $readBack = New-BizappShortcut -LinkPath $linkPath -TargetPath $jdkJavaw `
                -Arguments $simpleArgs -WorkingDirectory $Root -IconPath $iconPath `
                -Description 'BIZAPP POS point of sale'

            if (($null -eq $readBack) -or ($readBack -cne $simpleArgs)) {
                Write-WarnLine ('the shortcut at ' + $linkPath + ' could not be written correctly, so it was removed.')
                Write-Hint 'Start BIZAPP POS from a command prompt instead:'
                Write-Hint ('    "' + (Join-Path $Root 'install\windows\run.cmd') + '"')
                try { Remove-Item -LiteralPath $linkPath -Force -ErrorAction SilentlyContinue } catch { }
                continue
            }
        }

        Write-Info ('Shortcut: ' + $linkPath)
        if ($linkPath.StartsWith($startMenuDir)) { $startMenuLink = $linkPath } else { $desktopLink = $linkPath }
    }
}

# ---------------------------------------------------------------------------
# Summary.
# ---------------------------------------------------------------------------

Write-Step 'Done'
Write-Info ''

# A known blocker is repeated HERE, at the top of the summary, because the
# mid-run warning has scrolled away by now and "BIZAPP POS is installed." on its
# own would be a lie: the application exits with no window and no log entry.
if ($lafBlocked) {
    Write-WarnLine 'BIZAPP POS is installed but WILL NOT START until you run:  install.cmd --repair-laf'
    Write-Hint 'Your saved settings select a Substance look and feel. On Java 9 and newer it'
    Write-Hint 'throws a NullPointerException before any window is drawn, and the application'
    Write-Hint 'then exits reporting success: no window, no dialog, nothing in the log.'
    Write-Hint '--repair-laf changes that one setting after taking a backup of the file.'
} else {
    Write-Info 'BIZAPP POS is installed.'
}
Write-Info ''
Write-Info ('  Application     ' + $jarPath)
if ($null -ne $startMenuLink) { Write-Info ('  Start Menu      ' + $startMenuLink) }
if ($null -ne $desktopLink)   { Write-Info ('  Desktop         ' + $desktopLink) }
if ($null -ne $script:LogDir) { Write-Info ('  Log files       ' + $script:LogDir) }
Write-Info ('  Console launch  ' + (Join-Path $Root 'install\windows\run.cmd'))
Write-Info ''
# "never touched by setup" is false on a --repair-laf run, which has just
# rewritten nordpos.properties. Same branch as install/macos/install.command.
if ($script:LafRepaired) {
    Write-Info 'Your data lives outside this folder. The only thing setup changed there is the'
    Write-Info 'single look-and-feel line in nordpos.properties, after taking the backup named'
    Write-Info 'above:'
} else {
    Write-Info 'Your data lives outside this folder and is never touched by setup:'
}
Write-Info ('  Settings        ' + $propertiesPath)
Write-Info ('  Database        ' + $databasePath)
Write-Info 'Back up the .derby-db folder. It is your live point-of-sale database:'
Write-Info 'products, prices, customers and every sale ever recorded.'
# And "not created yet" is false whenever the file is there - this same run may
# have just read it, or repaired it.
if (Test-Path -LiteralPath $propertiesPath) {
    Write-Info 'The settings file already exists. Back it up along with the database folder.'
} else {
    Write-Info 'The settings file is not created until you use Configuration then Save. That is normal.'
}
Write-Info ''
Write-Info 'First run:'
# The dialog the user actually meets depends on whether a database is already
# there. With one present it is not "create?" but the UPGRADE dialog, whose own
# text reads DATA MAY BE LOST: the upgrade runs DELETE FROM SHAREDTICKETS, so
# every parked ticket is discarded, and it cannot be undone or re-run.
if (Test-Path -LiteralPath $databasePath) {
    Write-Info '  1. You already have a database. If BIZAPP POS offers to UPGRADE it, that dialog'
    Write-Info '     warns DATA MAY BE LOST - and it does delete every parked (suspended) ticket.'
    Write-Info ('     Copy ' + $databasePath)
    Write-Info '     somewhere safe BEFORE you answer Yes. There is no undo.'
} else {
    Write-Info '  1. A dialog asks whether to create the database. Click Yes.'
}
Write-Info '  2. The login screen shows four buttons. Click Administrator; there is no password.'
Write-Info '  3. Go straight to Maintenance then Users and set passwords. All four accounts'
Write-Info '     (Administrator, Manager, Employee, Guest) ship with no password.'
Write-Info ''
Write-Info 'Notes:'
Write-Info '  The shortcuts point at this folder rather than copying it. If you move, rename'
Write-Info ('  or delete ' + $Root + ', run install.cmd again.')
Write-Info '  Windows may show a firewall prompt naming "Java(TM) Platform SE binary". That is'
Write-Info '  the check for an already-running copy, on port 1099. Cancel is safe. Setup never'
Write-Info '  changes firewall settings.'
Write-Info '  Do not choose a Substance skin under Configuration then General: it crashes on'
Write-Info '  Java 9 and newer. Recover with:  install.cmd --repair-laf'
Write-Info ''
Write-Info 'Full instructions, troubleshooting and uninstall: INSTALL.md'

$port1099 = @()
try { $port1099 = @(Get-PortListenerName 1099) } catch { $port1099 = @() }
if ($port1099.Count -gt 0) {
    Write-Info ''
    Write-Info ('For information: TCP port 1099 is already in use by ' + ($port1099 -join ', ') + '.')
    Write-Info 'BIZAPP POS uses that port to notice a copy of itself that is already running.'
}

# ---------------------------------------------------------------------------
# Optional launch.
# ---------------------------------------------------------------------------

if ($optLaunch -and $lafBlocked) {
    # Starting it here would open nothing and write nothing: refusing says so.
    Write-WarnLine 'not starting BIZAPP POS: with the saved Substance look and feel it exits'
    Write-Hint 'immediately, with no window and no error anywhere.'
    Write-Hint 'Run  install.cmd --repair-laf  first, then start it from the Start Menu.'
} elseif ($optLaunch) {
    Write-Step 'Starting BIZAPP POS'
    # BIZAPP_JAVA_OPTS is honoured on this path because setup can read it right
    # now and put it on the command line. The shortcuts cannot: a .lnk stores a
    # fixed argument string.
    $extraJavaOptions = Get-Env 'BIZAPP_JAVA_OPTS'
    if (-not [string]::IsNullOrEmpty($extraJavaOptions)) {
        Write-Info ('Adding BIZAPP_JAVA_OPTS: ' + $extraJavaOptions)
    }
    $launchArgs = Get-BizappLaunchArgument -RepoRoot $Root -JarPath $jarPath `
        -LogDirectory $script:LogDir -LoggingConfig $script:LogConfig `
        -ExtraJavaOptions $extraJavaOptions
    Write-Log ('launch arguments: ' + $launchArgs)
    try {
        # A single pre-quoted string is passed rather than an array: PowerShell
        # 5.1 joins an -ArgumentList array with spaces without adding any
        # quoting of its own, which would break every path containing a space.
        Start-Process -FilePath $jdkJavaw -ArgumentList $launchArgs -WorkingDirectory $Root | Out-Null
    } catch {
        Write-WarnLine 'BIZAPP POS could not be started automatically.'
        Write-Hint $_.Exception.Message
        Write-Hint ('Start it with:  "' + (Join-Path $Root 'install\windows\run.cmd') + '"')
    }
}

Write-Log ('--- exit 0 at ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
exit 0
