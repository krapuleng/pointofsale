# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# The shared BIZAPP POS build engine, Windows PowerShell 5.1 port.
#
# This is a straight port of install/build.sh and MUST stay behaviourally
# identical to it: same flags, same stdout, same exit codes, same jar contents.
# Apache Ant and NetBeans are never used and are not required.
#
# STREAM CONTRACT
#   stderr : everything a human reads.  Written with [Console]::Error.WriteLine
#            so it is real stderr - Write-Host, Write-Output and Write-Verbose
#            would all end up somewhere the caller cannot separate.
#   stdout : machine-readable only.  On success, exactly two lines:
#              BIZAPP_JAR=<absolute path to the jar>
#              BIZAPP_CLASSPATH_FILE=<absolute path to classpath.txt>
#
# EXIT CODES
#   0 success                    5 repository layout invalid
#   2 usage / missing artifact   6 compilation failed
#   3 no suitable JDK found      7 packaging failed
#   4 JDK too old (< 11)         8 self-check failed
#                               10 environment problem
#
# Run it as:
#   powershell.exe -NoProfile -NoLogo -ExecutionPolicy Bypass -File install\build.ps1 [flags]

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

# $LASTEXITCODE does not exist until the first native command runs, and reading an
# undefined variable is an error under Set-StrictMode 2.0.
$global:LASTEXITCODE = 0

$BizappInstallerVersion = '1.0.0'
$BizappAppVersion = '4.0'
$BizappMainClass = 'com.openbravo.pos.forms.StartPOS'
$BizappJarName = 'nordpos.jar'
$BizappJdkMin = 11
$BizappJdkTestedMax = 24
$BizappExpectedCpEntries = 86

$script:BizappQuiet = $false

# --------------------------------------------------------------- output ---

function Write-BizappStep {
    param([string]$Message)
    if (-not $script:BizappQuiet) { [Console]::Error.WriteLine('==> ' + $Message) }
}
function Write-BizappWarn {
    param([string]$Message)
    [Console]::Error.WriteLine('[warn] ' + $Message)
}
function Write-BizappError {
    param([string]$Message)
    [Console]::Error.WriteLine('[error] ' + $Message)
}
function Write-BizappHint {
    param([string]$Message)
    [Console]::Error.WriteLine('        ' + $Message)
}
function Write-BizappUsage {
    [Console]::Error.WriteLine('usage: powershell -NoProfile -ExecutionPolicy Bypass -File install\build.ps1 [options]')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('  --build-dir <path>   intermediate output   (default <repo>\build, env BIZAPP_BUILD_DIR)')
    [Console]::Error.WriteLine('  --dist-dir <path>    final output          (default <repo>\dist,  env BIZAPP_DIST_DIR)')
    [Console]::Error.WriteLine('  --jdk <java_home>    JDK to build with     (env BIZAPP_JDK_HOME, then JAVA_HOME)')
    [Console]::Error.WriteLine('  --release <n>        javac --release level (default 11)')
    [Console]::Error.WriteLine('  --clean              delete this engine''s own build artifacts first')
    [Console]::Error.WriteLine('                       (files it did not write are always left alone)')
    [Console]::Error.WriteLine('  --quiet              suppress "==>" progress lines')
    [Console]::Error.WriteLine('  --verify             print extra self-check detail')
    [Console]::Error.WriteLine('  --print-classpath    print the resolved classpath and exit')
    [Console]::Error.WriteLine('  --print-jar          print the jar path that would be produced and exit')
    [Console]::Error.WriteLine('  --version            print the build engine version and exit')
    [Console]::Error.WriteLine('  --help               this text')
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('Every option also accepts the --name=value form.')
}

function Write-BizappNoJdk {
    Write-BizappError 'No Java Development Kit (JDK) was found.'
    Write-BizappHint 'BIZAPP POS needs a JDK (which includes the compiler), not just a Java runtime.'
    Write-BizappHint 'If "java -version" works but this still fails, you have a runtime only - on Windows'
    Write-BizappHint 'that is usually C:\ProgramData\Oracle\Java\javapath, which has no javac.exe.'
    Write-BizappHint 'Install one:  winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)'
    Write-BizappHint '              brew install --cask temurin@21                          (macOS)'
    Write-BizappHint '              or download from https://adoptium.net'
    Write-BizappHint 'Already installed? Point JAVA_HOME at the folder that contains bin\javac.exe:'
    Write-BizappHint '  setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17"'
    Write-BizappHint 'then open a new window and try again.'
}

# -------------------------------------------------------------- helpers ---

function Get-BizappEnv {
    param([string]$Name)
    $v = [Environment]::GetEnvironmentVariable($Name)
    if ($null -eq $v) { return '' }
    return $v
}

function ConvertTo-BizappArgToken {
    param([string]$Token)
    # Measured rules for a javac @argfile:
    #   * a token goes in bare only when every character is on the conservative
    #     whitelist [A-Za-z0-9_./:=+-];
    #   * anything else is double-quoted, and inside quotes a single backslash is
    #     eaten as an escape ("C:\out\classes" becomes a folder named C:outclasses),
    #     so backslashes are doubled and a double quote is written as \";
    #   * separators are written as forward slashes, which javac accepts on Windows.
    #   * ";" is NOT a token separator inside an argfile, but it is not on the
    #     whitelist either, so the classpath is quoted and javac reads it back whole.
    # A whitelist, not a "contains a space" test, because the tokenizer also treats
    # ' and " as quote characters and treats # as a comment introducer ANYWHERE in
    # a token, not only at its start.
    # Measured on OpenJDK 11.0.28: the bare token
    # C:/Users/O'Brien/pos/build/classes reaches javac as
    # C:/Users/OBrien/pos/build/classes (the apostrophe opens a quote that closes
    # at end of line), and a bare token that contains # at any position - such as
    # C:/repo#1/pos/build/classes - produces NO argument at all: the # and the
    # rest of the line are discarded, so the preceding flag silently loses its
    # value.  Quoted, both round-trip intact.
    # This predicate MUST stay identical to the awk one in install/build.sh.
    $t = $Token.Replace('\', '/')
    if ($t -match '^[A-Za-z0-9_./:=+-]+$') { return $t }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    foreach ($ch in $t.ToCharArray()) {
        if ($ch -eq '\') { [void]$sb.Append('\\') }
        elseif ($ch -eq '"') { [void]$sb.Append('\"') }
        else { [void]$sb.Append($ch) }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Get-BizappRelativePath {
    param([string]$FromDir, [string]$ToPath)
    $f = $FromDir.Replace('\', '/').TrimEnd('/')
    $t = $ToPath.Replace('\', '/')
    $fp = $f.Split('/')
    $tp = $t.Split('/')
    $i = 0
    while ($i -lt $fp.Length -and $i -lt $tp.Length -and $fp[$i] -eq $tp[$i]) { $i = $i + 1 }
    if ($i -eq 0) { return $null }
    $out = ''
    for ($j = $i; $j -lt $fp.Length; $j++) { $out = $out + '../' }
    for ($j = $i; $j -lt $tp.Length; $j++) {
        $out = $out + $tp[$j]
        if ($j -lt ($tp.Length - 1)) { $out = $out + '/' }
    }
    if ($out.EndsWith('/')) { $out = $out.Substring(0, $out.Length - 1) }
    if ($out -eq '') { $out = '.' }
    return $out
}

function Split-BizappManifestLine {
    param([string]$Logical)
    # Fold on BYTES: no physical manifest line may exceed 72 bytes or `jar` dies
    # with "line too long" and produces no jar at all.
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Logical)
    $lines = New-Object System.Collections.ArrayList
    if ($bytes.Length -le 72) {
        [void]$lines.Add($Logical)
        return $lines
    }
    [void]$lines.Add([System.Text.Encoding]::UTF8.GetString($bytes, 0, 72))
    $i = 72
    while ($i -lt $bytes.Length) {
        $n = [Math]::Min(71, ($bytes.Length - $i))
        [void]$lines.Add(' ' + [System.Text.Encoding]::UTF8.GetString($bytes, $i, $n))
        $i = $i + $n
    }
    return $lines
}

function Write-BizappTextFile {
    param([string]$Path, [string[]]$Lines)
    # UTF-8 with no byte-order mark and LF endings.  PowerShell 5.1's ">",
    # Out-File and Set-Content -Encoding UTF8 all emit a BOM or UTF-16LE; a BOM
    # in a javac argfile makes it report "file not found".
    $text = [string]::Join("`n", $Lines) + "`n"
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# --------------------------------------------------------- option parse ---

$optBuildDir = ''
$optDistDir = ''
$optJdk = ''
$optRelease = '11'
$optClean = $false
$optVerify = $false
$optAction = 'build'

$argList = @($args)
$i = 0
while ($i -lt $argList.Count) {
    $a = [string]$argList[$i]
    $name = $a
    $inline = $null
    if ($a.StartsWith('--')) {
        $eqPos = $a.IndexOf('=')
        if ($eqPos -gt 2) {
            $name = $a.Substring(0, $eqPos)
            $inline = $a.Substring($eqPos + 1)
        }
    }
    switch ($name) {
        '--build-dir' {
            if ($null -ne $inline) { $optBuildDir = $inline }
            else {
                $i = $i + 1
                if ($i -ge $argList.Count) { Write-BizappError 'option --build-dir requires a value'; Write-BizappUsage; exit 2 }
                $optBuildDir = [string]$argList[$i]
            }
        }
        '--dist-dir' {
            if ($null -ne $inline) { $optDistDir = $inline }
            else {
                $i = $i + 1
                if ($i -ge $argList.Count) { Write-BizappError 'option --dist-dir requires a value'; Write-BizappUsage; exit 2 }
                $optDistDir = [string]$argList[$i]
            }
        }
        '--jdk' {
            if ($null -ne $inline) { $optJdk = $inline }
            else {
                $i = $i + 1
                if ($i -ge $argList.Count) { Write-BizappError 'option --jdk requires a value'; Write-BizappUsage; exit 2 }
                $optJdk = [string]$argList[$i]
            }
        }
        '--release' {
            if ($null -ne $inline) { $optRelease = $inline }
            else {
                $i = $i + 1
                if ($i -ge $argList.Count) { Write-BizappError 'option --release requires a value'; Write-BizappUsage; exit 2 }
                $optRelease = [string]$argList[$i]
            }
        }
        '--clean'           { $optClean = $true }
        '--quiet'           { $script:BizappQuiet = $true }
        '--verify'          { $optVerify = $true }
        '--print-classpath' { $optAction = 'print-classpath' }
        '--print-jar'       { $optAction = 'print-jar' }
        '--version'         { $optAction = 'version' }
        '--help'            { Write-BizappUsage; exit 0 }
        '-h'                { Write-BizappUsage; exit 0 }
        default {
            Write-BizappError ('unknown option: ' + $a)
            Write-BizappUsage
            exit 2
        }
    }
    $i = $i + 1
}

if ($optRelease -notmatch '^[0-9]+$') {
    Write-BizappError ("--release needs a whole number, got '" + $optRelease + "'.")
    Write-BizappHint 'Example: --release 11'
    exit 2
}

if ($optAction -eq 'version') {
    [Console]::Out.WriteLine('BIZAPP POS build engine ' + $BizappInstallerVersion)
    exit 0
}

Write-BizappStep ('BIZAPP POS build engine ' + $BizappInstallerVersion + '  (BIZAPP POS ' + $BizappAppVersion + ')')

# ------------------------------------------------- step 1: repo layout ---

if ([string]::IsNullOrEmpty($PSScriptRoot)) {
    Write-BizappError 'cannot determine where this script lives.'
    Write-BizappHint 'Run it as: powershell -NoProfile -ExecutionPolicy Bypass -File C:\path\to\install\build.ps1'
    exit 10
}
try {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
} catch {
    Write-BizappError ('cannot determine the repository root from ' + $PSScriptRoot + '.')
    Write-BizappHint 'Keep build.ps1 inside the checkout, in the install\ folder.'
    exit 10
}

if ($RepoRoot.Length -gt 140) {
    Write-BizappError ('the folder path is ' + $RepoRoot.Length + ' characters long. Windows limits most paths to 260 characters and BIZAPP POS needs about 120 of them. Move the checkout somewhere shorter, e.g. C:\BizappPOS, and try again.')
    Write-BizappHint ('Current location: ' + $RepoRoot)
    exit 10
}

Write-BizappStep ('Checking the checkout at ' + $RepoRoot)
$requiredPaths = @(
    'nbproject\project.properties', 'lib', 'lib-jdbc', 'services\META-INF\services',
    'src-beans', 'src-data', 'src-peripheral', 'src-pos', 'src-server', 'src-sync',
    'locales', 'reports', 'templates', 'transformations'
)
foreach ($rp in $requiredPaths) {
    $full = Join-Path $RepoRoot $rp
    if (-not (Test-Path -LiteralPath $full)) {
        Write-BizappError ('this does not look like a BIZAPP POS checkout: ' + $full + ' is missing')
        Write-BizappHint 'Clone the whole repository: git clone https://github.com/krapuleng/pointofsale.git'
        Write-BizappHint 'A partial copy or a downloaded sub-folder will not build.'
        exit 5
    }
}

# ------------------------------------------ step 2: output directories ---

$buildDir = $optBuildDir
if ([string]::IsNullOrEmpty($buildDir)) { $buildDir = Get-BizappEnv 'BIZAPP_BUILD_DIR' }
if ([string]::IsNullOrEmpty($buildDir)) { $buildDir = Join-Path $RepoRoot 'build' }
$distDir = $optDistDir
if ([string]::IsNullOrEmpty($distDir)) { $distDir = Get-BizappEnv 'BIZAPP_DIST_DIR' }
if ([string]::IsNullOrEmpty($distDir)) { $distDir = Join-Path $RepoRoot 'dist' }

# Reject a path inside the committed 'dist - bizpapp' tree BEFORE creating anything.
# Every other guarded location (the repository root, the user folder, a drive root,
# an ancestor of the repository) already exists, so CreateDirectory there creates
# nothing; this is the only case where the guard below could otherwise fire after a
# directory was made.
foreach ($d in @($buildDir, $distDir)) {
    if ($d.ToLowerInvariant().Contains('dist - bizpapp')) {
        Write-BizappError ("refusing to use '" + $d + "' as an output directory: it is inside the committed 'dist - bizpapp' folder.")
        Write-BizappHint 'The build engine deletes and rewrites its output directories.'
        Write-BizappHint ('Use something like: --build-dir "' + $RepoRoot + '\build" --dist-dir "' + $RepoRoot + '\dist"')
        exit 2
    }
}

try {
    [void][System.IO.Directory]::CreateDirectory($buildDir)
} catch {
    Write-BizappError ("cannot create the build directory '" + $buildDir + "'.")
    Write-BizappHint 'Choose a writable location, for example: --build-dir "%USERPROFILE%\bizapp-build"'
    exit 10
}
try {
    [void][System.IO.Directory]::CreateDirectory($distDir)
} catch {
    Write-BizappError ("cannot create the output directory '" + $distDir + "'.")
    Write-BizappHint 'Choose a writable location, for example: --dist-dir "%USERPROFILE%\bizapp-dist"'
    exit 10
}
$buildDir = (Resolve-Path -LiteralPath $buildDir).Path.TrimEnd('\')
$distDir = (Resolve-Path -LiteralPath $distDir).Path.TrimEnd('\')

$userProfile = (Get-BizappEnv 'USERPROFILE').TrimEnd('\')
foreach ($d in @($buildDir, $distDir)) {
    $bad = ''
    if ($d -eq $RepoRoot) { $bad = 'it is the repository root itself' }
    elseif (($userProfile.Length -gt 0) -and ($d -eq $userProfile)) { $bad = 'it is your user folder' }
    elseif ($d -match '^[A-Za-z]:$') { $bad = 'it is the root of a drive' }
    elseif ($RepoRoot.StartsWith($d + '\', [StringComparison]::OrdinalIgnoreCase)) { $bad = 'it contains the repository' }
    elseif ($d.ToLowerInvariant().Contains('dist - bizpapp')) { $bad = "it is inside the committed 'dist - bizpapp' folder" }
    if ($bad.Length -gt 0) {
        Write-BizappError ("refusing to use '" + $d + "' as an output directory: " + $bad + '.')
        Write-BizappHint 'The build engine deletes and rewrites its output directories.'
        Write-BizappHint ('Use something like: --build-dir "' + $RepoRoot + '\build" --dist-dir "' + $RepoRoot + '\dist"')
        exit 2
    }
}

$jarPath = Join-Path $distDir $BizappJarName
$cpFilePath = Join-Path $distDir 'classpath.txt'

# ------------------------------------------------------- step 3: JDK ---

Write-BizappStep 'Looking for a Java Development Kit'
$modulePath = Join-Path $RepoRoot 'install\lib\Jdk.psm1'
if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) {
    Write-BizappError ('this does not look like a BIZAPP POS checkout: ' + $modulePath + ' is missing')
    Write-BizappHint 'Clone the whole repository: git clone https://github.com/krapuleng/pointofsale.git'
    exit 5
}
Import-Module -Name $modulePath -Force

$jdk = $null
$jdkSource = ''
$explicitHome = ''
if ($optJdk.Length -gt 0) { $explicitHome = $optJdk; $jdkSource = '--jdk' }
if ($explicitHome.Length -eq 0) {
    $v = Get-BizappEnv 'BIZAPP_JDK_HOME'
    if ($v.Length -gt 0) { $explicitHome = $v; $jdkSource = 'BIZAPP_JDK_HOME' }
}
if ($explicitHome.Length -eq 0) {
    $v = Get-BizappEnv 'JAVA_HOME'
    if ($v.Length -gt 0) { $explicitHome = $v; $jdkSource = 'JAVA_HOME' }
}

if ($explicitHome.Length -gt 0) {
    if (-not (Test-Path -LiteralPath ($explicitHome.Trim('"').TrimEnd('\')) -PathType Container)) {
        Write-BizappError ('the JDK location from ' + $jdkSource + ' does not exist: ' + $explicitHome)
        Write-BizappHint 'Point it at the folder that contains bin\javac.exe, or clear it to let setup search.'
        exit 3
    }
    $jdk = Test-JdkCandidate -Home $explicitHome
    if ($null -eq $jdk) {
        Write-BizappError ('the JDK at ' + $explicitHome + ' (from ' + $jdkSource + ') is not usable.')
        Write-BizappHint 'It must contain bin\javac.exe, bin\java.exe, bin\javaw.exe and bin\jar.exe.'
        Write-BizappHint 'A Java runtime (JRE) is not enough - install a full JDK:'
        Write-BizappHint '  winget install --id EclipseAdoptium.Temurin.17.JDK -e'
        Write-BizappHint '  or download from https://adoptium.net'
        exit 3
    }
} else {
    $jdkSource = 'auto-discovery'
    $found = @(Find-Jdk -MinMajor $BizappJdkMin -RequireJavac)
    if ($found.Count -gt 0) { $jdk = $found[0] }
    if ($null -eq $jdk) {
        Write-BizappNoJdk
        exit 3
    }
}

if ($jdk.Major -lt $BizappJdkMin) {
    Write-BizappError ('JDK ' + $BizappJdkMin + ' or newer is required (found ' + $jdk.Major + ' at ' + $jdk.Home + ').')
    Write-BizappHint 'Install a newer one:  winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)'
    Write-BizappHint '                      brew install --cask temurin@21                          (macOS)'
    Write-BizappHint '                      or download from https://adoptium.net'
    Write-BizappHint 'Then re-run with:  --jdk "C:\path\to\that\jdk"   (or set JAVA_HOME)'
    exit 4
}
if ($jdk.Major -gt $BizappJdkTestedMax) {
    Write-BizappWarn ('JDK ' + $jdk.Major + ' is newer than any version this installer has been tested against (' + $BizappJdkMin + '-' + $BizappJdkTestedMax + '). Continuing.')
}
Write-BizappStep ('Using JDK ' + $jdk.Major + ' at ' + $jdk.Home)

# ------------------------------------------- step 4: classpath from Ant ---

Write-BizappStep 'Deriving the library classpath from nbproject\project.properties'
$propsPath = Join-Path $RepoRoot 'nbproject\project.properties'
$rawLines = [System.IO.File]::ReadAllLines($propsPath)

# Flatten Ant/Java continuation lines: a backslash immediately before the newline
# joins to the next line, whose leading whitespace is dropped.
$logicalLines = New-Object System.Collections.ArrayList
$buffer = ''
$continuing = $false
foreach ($rawLine in $rawLines) {
    $line = [string]$rawLine
    if ($line.EndsWith("`r")) { $line = $line.Substring(0, $line.Length - 1) }
    if ($continuing) { $buffer = $buffer + $line.TrimStart(" `t".ToCharArray()) }
    else { $buffer = $line }
    $trailing = 0
    $probe = $buffer
    while (($probe.Length -gt 0) -and ($probe[$probe.Length - 1] -eq '\')) {
        $trailing = $trailing + 1
        $probe = $probe.Substring(0, $probe.Length - 1)
    }
    if (($trailing % 2) -eq 1) {
        $buffer = $buffer.Substring(0, $buffer.Length - 1)
        $continuing = $true
        continue
    }
    $continuing = $false
    [void]$logicalLines.Add($buffer)
    $buffer = ''
}
if ($continuing -and ($buffer.Length -gt 0)) { [void]$logicalLines.Add($buffer) }

$fileRefs = @{}
$cpValue = $null
foreach ($l in $logicalLines) {
    $line = [string]$l
    $lead = $line.TrimStart(" `t".ToCharArray())
    if ($lead.StartsWith('#') -or $lead.StartsWith('!')) { continue }
    $p = $line.IndexOf('=')
    if ($p -lt 1) { continue }
    $key = $line.Substring(0, $p).Trim()
    $value = $line.Substring($p + 1).TrimStart(" `t".ToCharArray())
    if ($key.StartsWith('file.reference.')) { $fileRefs[$key] = $value }
    elseif ($key -eq 'javac.classpath') { $cpValue = $value }
}

if ($null -eq $cpValue) {
    Write-BizappError 'could not read the library list out of nbproject\project.properties.'
    Write-BizappHint 'javac.classpath was not found in that file.'
    Write-BizappHint 'Restore it from git: git checkout -- nbproject/project.properties'
    exit 5
}

$classpathEntries = New-Object System.Collections.ArrayList
foreach ($token in $cpValue.Split(':')) {
    $t = ([string]$token).Trim()
    if ($t.Length -eq 0) { continue }
    $value = ''
    if ($t.StartsWith('${') -and $t.EndsWith('}')) {
        $refName = $t.Substring(2, $t.Length - 3)
        if (-not $fileRefs.ContainsKey($refName)) {
            Write-BizappError 'could not read the library list out of nbproject\project.properties.'
            Write-BizappHint ('unresolved reference ${' + $refName + '}')
            Write-BizappHint 'Restore it from git: git checkout -- nbproject/project.properties'
            exit 5
        }
        $value = [string]$fileRefs[$refName]
    } else {
        $value = $t
    }
    $value = $value.Replace('\', '/')
    while ($value.Contains('//')) { $value = $value.Replace('//', '/') }
    [void]$classpathEntries.Add($value)
}

$libRoot = Join-Path $RepoRoot 'lib'
$resolvedEntries = New-Object System.Collections.ArrayList
foreach ($entry in $classpathEntries) {
    $e = [string]$entry
    $ok = $false
    if (-not ($e -match '^[A-Za-z]:') -and -not $e.StartsWith('/')) {
        if (Test-Path -LiteralPath (Join-Path $RepoRoot ($e.Replace('/', '\'))) -PathType Leaf) { $ok = $true }
    }
    if ($ok) {
        [void]$resolvedEntries.Add($e)
        continue
    }
    # Self-heal a stale reference (this is what repairs the C:\code\pos substance
    # entry for anyone who has not taken the project.properties fix).
    $baseName = Split-Path -Leaf ($e.Replace('/', '\'))
    $hit = $null
    $matches2 = @(Get-ChildItem -LiteralPath $libRoot -Recurse -File -Filter $baseName -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq $baseName })
    if ($matches2.Count -gt 0) { $hit = $matches2[0] }
    if ($null -eq $hit) {
        Write-BizappError ("classpath entry '" + $e + "' does not exist and no '" + $baseName + "' was found under lib\")
        Write-BizappHint 'The checkout is incomplete. Re-clone it, or restore lib\ from git.'
        exit 5
    }
    $rel = $hit.FullName.Substring($RepoRoot.Length + 1).Replace('\', '/')
    Write-BizappWarn ("remapped stale classpath entry '" + $e + "' -> '" + $rel + "'")
    [void]$resolvedEntries.Add($rel)
}

if ($resolvedEntries.Count -eq 0) {
    Write-BizappError 'no library entries could be derived from nbproject\project.properties.'
    Write-BizappHint 'Restore it from git: git checkout -- nbproject/project.properties'
    exit 5
}
if ($resolvedEntries.Count -ne $BizappExpectedCpEntries) {
    Write-BizappWarn ('expected ' + $BizappExpectedCpEntries + ' classpath entries, derived ' + $resolvedEntries.Count)
}
Write-BizappStep ('Resolved ' + $resolvedEntries.Count + ' library jars')

if ($optAction -eq 'print-classpath') {
    [Console]::Out.WriteLine([string]::Join(';', [string[]]$resolvedEntries))
    exit 0
}
if ($optAction -eq 'print-jar') {
    [Console]::Out.WriteLine($jarPath)
    exit 0
}

# ------------------------------------------------------- step 5: clean ---

# 'bizapp-classes', not 'classes': this is the one folder removed recursively
# (here and on every plain build at the end of this step), so it must be a name
# this engine owns by construction rather than one a caller might already be
# using for their own files.  '--build-dir "%USERPROFILE%\Documents"' must not
# eat %USERPROFILE%\Documents\classes.  Must stay identical to install/build.sh.
$classesDir = Join-Path $buildDir 'bizapp-classes'
if ($optClean) {
    # --clean deletes ONLY the artifacts this engine itself writes, by exact name,
    # inside --build-dir / --dist-dir (or BIZAPP_BUILD_DIR / BIZAPP_DIST_DIR).  It
    # never deletes a caller-named directory recursively, so '--clean --dist-dir
    # "%USERPROFILE%\Documents"' removes those artifacts and leaves every other
    # file untouched.  There is deliberately no "was this folder made by us?"
    # heuristic: a plain build writes bizapp-classes\, javac.args, MANIFEST.MF,
    # classpath.txt, build-info.txt and the jar into whatever folder the caller
    # named, so such a test poisons itself - one build makes a user's Documents
    # look like build output and the next --clean eats it.  Deleting only what we
    # wrote needs no test at all.  The folder itself goes only when our own
    # artifacts were all it held.  The refusals in step 2 (repository root, the
    # user folder, a drive root, an ancestor of the repository, anything inside
    # 'dist - bizpapp') still apply to both folders and are checked there.
    # This MUST stay identical to the clean step in install/build.sh.
    Write-BizappStep ('Cleaning build artifacts in ' + $buildDir + ' and ' + $distDir)
    foreach ($d in @($buildDir, $distDir)) {
        # Exact literals only.  A wildcard here would destroy the committed
        # 'dist - bizpapp' folder and dist.rar.
        Remove-Item -LiteralPath (Join-Path $d 'bizapp-classes') -Recurse -Force -ErrorAction SilentlyContinue
        foreach ($n in @('javac.args', 'MANIFEST.MF', 'classpath.txt', 'build-info.txt', $BizappJarName)) {
            # [File]::Delete, not Remove-Item: these five artifacts are always files.
            # It is a no-op on a missing path and simply refuses a folder that happens
            # to carry the same name, where 'Remove-Item -Force' without -Recurse would
            # stop for a confirmation prompt that no unattended install can answer.
            try { [System.IO.File]::Delete((Join-Path $d $n)) } catch { $null = $_ }
        }
        # The folder itself only when it is empty now - a failure here is fine.
        try {
            $left = @([System.IO.Directory]::EnumerateFileSystemEntries($d))
            if ($left.Count -eq 0) { [System.IO.Directory]::Delete($d, $false) }
        } catch {
            $null = $_
        }
    }
}
Remove-Item -LiteralPath $classesDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $jarPath -Force -ErrorAction SilentlyContinue
[void][System.IO.Directory]::CreateDirectory($classesDir)
[void][System.IO.Directory]::CreateDirectory($distDir)

# ------------------------------------------ step 6: sources and compile ---

Write-BizappStep 'Collecting Java sources'
$sourceRoots = @('src-beans', 'src-data', 'src-peripheral', 'src-pos', 'src-server', 'src-sync')
$sourceFiles = New-Object System.Collections.ArrayList
foreach ($root in $sourceRoots) {
    $rootPath = Join-Path $RepoRoot $root
    $found2 = @(Get-ChildItem -LiteralPath $rootPath -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    foreach ($f in $found2) {
        if ($f.Extension -ne '.java') { continue }
        [void]$sourceFiles.Add($f.FullName.Substring($RepoRoot.Length + 1).Replace('\', '/'))
    }
}
if ($sourceFiles.Count -eq 0) {
    Write-BizappError 'no Java source files were found under src-beans, src-data, src-peripheral, src-pos, src-server or src-sync.'
    Write-BizappHint 'The checkout is incomplete. Re-clone: git clone https://github.com/krapuleng/pointofsale.git'
    exit 5
}
Write-BizappStep ('Found ' + $sourceFiles.Count + ' Java source files')

$argFilePath = Join-Path $buildDir 'javac.args'
$tokens = New-Object System.Collections.ArrayList
[void]$tokens.Add('-nowarn')
[void]$tokens.Add('-encoding')
[void]$tokens.Add('UTF-8')
[void]$tokens.Add('--release')
[void]$tokens.Add($optRelease)
[void]$tokens.Add('-d')
[void]$tokens.Add($classesDir)
[void]$tokens.Add('-cp')
[void]$tokens.Add([string]::Join(';', [string[]]$resolvedEntries))
foreach ($s in $sourceFiles) { [void]$tokens.Add([string]$s) }

$argLines = New-Object System.Collections.ArrayList
foreach ($t in $tokens) { [void]$argLines.Add((ConvertTo-BizappArgToken ([string]$t))) }
Write-BizappTextFile -Path $argFilePath -Lines ([string[]]$argLines)

Write-BizappStep ('Compiling ' + $sourceFiles.Count + ' sources with --release ' + $optRelease + ' (this takes about 30 seconds)')
$previousLocation = Get-Location
$previousEnvCwd = [Environment]::CurrentDirectory
$javacOutput = @()
$javacRc = 0
try {
    # javac is given repository-relative source paths, so it must run with the
    # repository as its working directory.  Both mechanisms are set because
    # Windows PowerShell and .NET track "current directory" separately.
    Set-Location -LiteralPath $RepoRoot
    [Environment]::CurrentDirectory = $RepoRoot
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $javacOutput = @(& $jdk.Javac ('@' + $argFilePath) 2>&1)
        $javacRc = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
} finally {
    Set-Location -LiteralPath $previousLocation
    [Environment]::CurrentDirectory = $previousEnvCwd
}
$javacText = ''
foreach ($line in $javacOutput) {
    $s = [string]$line
    [Console]::Error.WriteLine($s)
    $javacText = $javacText + $s + "`n"
}
if ($javacRc -ne 0) {
    if ($javacText -match 'java\.applet') {
        Write-BizappError ('this JDK has removed the java.applet API, which src-peripheral/com/nordpos/device/ticket/TicketParser.java still uses. Use a JDK between ' + $BizappJdkMin + ' and ' + $BizappJdkTestedMax + '.')
        Write-BizappHint 'Install one:  winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)'
        Write-BizappHint '              brew install --cask temurin@21                          (macOS)'
        Write-BizappHint 'Then re-run with:  --jdk "C:\path\to\that\jdk"'
    } else {
        Write-BizappError ('compilation failed (javac exit ' + $javacRc + '); see the messages above.')
        Write-BizappHint ('The argument file javac was given is at: ' + $argFilePath)
        Write-BizappHint 'If this is a fresh clone, re-run with --clean; if it persists, report the first error above.'
    }
    exit 6
}

# ------------------------------------------- step 7: stage resources ---

Write-BizappStep 'Staging resources (images, reports, scripts, service descriptors)'

function Copy-BizappTree {
    param([string]$SourceRoot, [string]$Destination, [bool]$SkipSources)
    $count = 0
    if (-not (Test-Path -LiteralPath $SourceRoot -PathType Container)) { return 0 }
    $items = @(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -ErrorAction SilentlyContinue)
    foreach ($item in $items) {
        if ($SkipSources) {
            $ext = $item.Extension.ToLowerInvariant()
            if ($ext -eq '.java' -or $ext -eq '.form' -or $ext -eq '.jpa') { continue }
        }
        $rel = $item.FullName.Substring($SourceRoot.Length + 1)
        $target = Join-Path $Destination $rel
        $targetDir = [System.IO.Path]::GetDirectoryName($target)
        [void][System.IO.Directory]::CreateDirectory($targetDir)
        [System.IO.File]::Copy($item.FullName, $target, $true)
        $count = $count + 1
    }
    return $count
}

$stagedCount = 0
try {
    foreach ($root in @('src-beans', 'src-data', 'src-peripheral', 'src-pos', 'src-server', 'src-sync', 'locales', 'reports')) {
        $stagedCount = $stagedCount + (Copy-BizappTree -SourceRoot (Join-Path $RepoRoot $root) -Destination $classesDir -SkipSources $true)
    }
    # fonts\ is deliberately NOT staged: lib\jasperreports-6.2\jasperreports-fonts-6.2.0.jar
    # already carries the same DejaVu faces plus the jasperreports_extension.properties
    # that actually registers them, and the loose tree would only shadow it.
    foreach ($root in @('templates', 'transformations')) {
        $stagedCount = $stagedCount + (Copy-BizappTree -SourceRoot (Join-Path $RepoRoot $root) -Destination $classesDir -SkipSources $false)
    }
} catch {
    Write-BizappError 'could not stage resources into the build directory.'
    Write-BizappHint ('Check that ' + $buildDir + ' is writable and has free space.')
    Write-BizappHint ([string]$_.Exception.Message)
    exit 7
}

$servicesDest = Join-Path $classesDir 'META-INF\services'
[void][System.IO.Directory]::CreateDirectory($servicesDest)
$serviceCount = 0
try {
    $serviceCount = Copy-BizappTree -SourceRoot (Join-Path $RepoRoot 'services\META-INF\services') -Destination $servicesDest -SkipSources $false
} catch {
    Write-BizappError 'could not stage the ServiceLoader descriptors from services\META-INF\services.'
    Write-BizappHint 'Without them every printer, display, scale and payment driver silently disappears.'
    Write-BizappHint ([string]$_.Exception.Message)
    exit 7
}
$stagedCount = $stagedCount + $serviceCount
Write-BizappStep ('Staged ' + $stagedCount + ' resource files (' + $serviceCount + ' of them ServiceLoader descriptors)')

# ---------------------------------------------------- step 8: manifest ---

Write-BizappStep 'Writing the jar manifest'
$relativeEntries = New-Object System.Collections.ArrayList
foreach ($entry in $resolvedEntries) {
    $full = Join-Path $RepoRoot (([string]$entry).Replace('/', '\'))
    $rel = Get-BizappRelativePath -FromDir $distDir -ToPath $full
    $bad = $false
    if ($null -eq $rel) { $bad = $true }
    else {
        foreach ($ch in $rel.ToCharArray()) {
            $code = [int][char]$ch
            if ($code -le 0x20 -or $code -gt 0x7E) { $bad = $true; break }
        }
    }
    if ($bad) {
        Write-BizappError 'the chosen --dist-dir produces a jar Class-Path with spaces or non-ASCII characters. Use a --dist-dir inside the repository.'
        Write-BizappHint ('Offending entry: ' + $entry)
        Write-BizappHint ('Try:  -File install\build.ps1 --build-dir "' + $RepoRoot + '\build" --dist-dir "' + $RepoRoot + '\dist"')
        exit 7
    }
    [void]$relativeEntries.Add($rel)
}

$manifestPath = Join-Path $buildDir 'MANIFEST.MF'
$manifestLines = New-Object System.Collections.ArrayList
[void]$manifestLines.Add('Manifest-Version: 1.0')
[void]$manifestLines.Add('Main-Class: ' + $BizappMainClass)
$classPathLogical = 'Class-Path: ' + [string]::Join(' ', [string[]]$relativeEntries)
foreach ($folded in (Split-BizappManifestLine -Logical $classPathLogical)) {
    [void]$manifestLines.Add([string]$folded)
}
Write-BizappTextFile -Path $manifestPath -Lines ([string[]]$manifestLines)

foreach ($ml in $manifestLines) {
    if ([System.Text.Encoding]::UTF8.GetBytes([string]$ml).Length -gt 72) {
        Write-BizappError 'the generated manifest has a line longer than 72 bytes; jar would reject it.'
        Write-BizappHint ('This is a bug in the build engine. Report the contents of ' + $manifestPath)
        exit 7
    }
}

# ----------------------------------------------------- step 9: package ---

Write-BizappStep ('Packaging ' + $jarPath)
$jarRc = 0
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $jarOutput = @(& $jdk.Jar 'cfm' $jarPath $manifestPath '-C' $classesDir '.' 2>&1)
    $jarRc = $LASTEXITCODE
    foreach ($line in $jarOutput) { [Console]::Error.WriteLine([string]$line) }
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($jarRc -ne 0) {
    Write-BizappError 'packaging the jar failed.'
    Write-BizappHint ('Check free space and that ' + $distDir + ' is writable.')
    exit 7
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    Write-BizappError ('jar reported success but ' + $jarPath + ' was not created.')
    Write-BizappHint ('Check free space in ' + $distDir + '.')
    exit 7
}

# -------------------------------------------------- step 10: self-check ---

Write-BizappStep 'Checking the packaged application'
$listRc = 0
$jarEntries = @()
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $jarEntries = @(& $jdk.Jar 'tf' $jarPath 2>&1)
    $listRc = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($listRc -ne 0) {
    Write-BizappError 'the jar was written but cannot be listed; it is probably corrupt.'
    Write-BizappHint 'Re-run with --clean.'
    exit 8
}
$entrySet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
foreach ($je in $jarEntries) { [void]$entrySet.Add(([string]$je).Trim()) }

$requiredEntries = @(
    'com/openbravo/pos/forms/StartPOS.class',
    'com/openbravo/pos/scripts/Derby-create-nordpos.sql',
    'com/nordpos/templates/Schema.Printer.xsd',
    'com/nordpos/transformations/csv/EXPORT_PRODUCTS.ktr',
    'com/openbravo/pos/templates/Role.Administrator.xml',
    'com/openbravo/images/favicon.png',
    'META-INF/services/com.nordpos.device.display.DisplayInterface',
    'META-INF/services/com.nordpos.device.fiscalprinter.FiscalPrinterInterface',
    'META-INF/services/com.nordpos.device.labelprinter.LabelPrinterInterface',
    'META-INF/services/com.nordpos.device.plu.InputOutputInterface',
    'META-INF/services/com.nordpos.device.receiptprinter.ReceiptPrinterInterface',
    'META-INF/services/com.nordpos.device.scale.ScaleInterface',
    'META-INF/services/com.nordpos.payment.gateway.PaymentGatewayInterface'
)
$missing = 0
foreach ($re in $requiredEntries) {
    if (-not $entrySet.Contains($re)) {
        Write-BizappError ('the packaged jar is missing ' + $re)
        $missing = $missing + 1
    }
}
$jarSize = (Get-Item -LiteralPath $jarPath).Length
if ($jarSize -lt 1000000) {
    Write-BizappError ('the packaged jar is only ' + $jarSize + ' bytes; it should be over 1000000.')
    $missing = $missing + 1
}
if ($missing -ne 0) {
    Write-BizappHint 'The build produced an incomplete application and has been rejected.'
    Write-BizappHint 'Re-run with --clean. If it happens again the checkout is incomplete - re-clone it.'
    exit 8
}
if ($optVerify) {
    Write-BizappStep ('Self-check passed: ' + $jarEntries.Count + ' jar entries, ' + $jarSize + ' bytes, 13 required entries present')
    Write-BizappStep ('Manifest: ' + $manifestPath)
    Write-BizappStep ('Argument file: ' + $argFilePath)
}

# ---------------------------------------------- step 11: outputs, stdout ---

Write-BizappTextFile -Path $cpFilePath -Lines ([string[]]$resolvedEntries)

$gitHead = ''
$gitCmd = Get-Command -Name 'git' -CommandType Application -ErrorAction SilentlyContinue
if ($null -ne $gitCmd) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = @(& git -C $RepoRoot rev-parse --short HEAD 2>&1)
        if ($LASTEXITCODE -eq 0 -and $out.Count -gt 0) { $gitHead = ([string]$out[0]).Trim() }
    } catch {
        $gitHead = ''
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$buildInfo = New-Object System.Collections.ArrayList
[void]$buildInfo.Add('BIZAPP POS build information')
[void]$buildInfo.Add('installer version : ' + $BizappInstallerVersion)
[void]$buildInfo.Add('application       : BIZAPP POS ' + $BizappAppVersion)
[void]$buildInfo.Add('built (UTC)       : ' + [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [System.Globalization.CultureInfo]::InvariantCulture))
[void]$buildInfo.Add('jdk               : ' + ($jdk.Version -replace "`r`n", ' ' -replace "`n", ' ') + ' (major ' + $jdk.Major + ')')
[void]$buildInfo.Add('jdk home          : ' + $jdk.Home)
[void]$buildInfo.Add('javac --release   : ' + $optRelease)
[void]$buildInfo.Add('source files      : ' + $sourceFiles.Count)
[void]$buildInfo.Add('staged resources  : ' + $stagedCount)
[void]$buildInfo.Add('library jars      : ' + $resolvedEntries.Count)
[void]$buildInfo.Add('jar               : ' + $jarPath)
[void]$buildInfo.Add('jar size (bytes)  : ' + $jarSize)
[void]$buildInfo.Add('repository        : ' + $RepoRoot)
if ($gitHead.Length -gt 0) { [void]$buildInfo.Add('git commit        : ' + $gitHead) }
Write-BizappTextFile -Path (Join-Path $distDir 'build-info.txt') -Lines ([string[]]$buildInfo)

Write-BizappStep ('Build complete: ' + $jarPath + ' (' + $jarSize + ' bytes)')

[Console]::Out.WriteLine('BIZAPP_JAR=' + $jarPath)
[Console]::Out.WriteLine('BIZAPP_CLASSPATH_FILE=' + $cpFilePath)
exit 0
