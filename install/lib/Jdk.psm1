# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# Shared Windows PowerShell 5.1 JDK discovery, imported by install\build.ps1 and
# install\windows\lib\Install-BizappPos.ps1.
#
# Exports exactly two functions: Test-JdkCandidate and Find-Jdk.
# Importing this module prints nothing and changes nothing.
#
# Why the javac gate is the whole point:
#   C:\ProgramData\Oracle\Java\javapath\ is on PATH on a great many machines and
#   contains java.exe, javaw.exe and javaws.exe but NO javac.exe.  "where java"
#   therefore succeeds on a machine that cannot compile anything, and the build
#   then fails much later with a confusing message.  A candidate is only ever
#   accepted here when the real bin\javac.exe file exists and runs.

Set-StrictMode -Version 2.0

# ------------------------------------------------------------- private ---

function Get-BizappJdkVersionText {
    param([string]$JavacPath)
    # javac writes its banner to stdout on 9+ and to stderr on 8.  Under a caller
    # running with $ErrorActionPreference = 'Stop', a native command's stderr
    # captured by 2>&1 can be raised as a NativeCommandError, so the preference
    # is relaxed for the duration of the call.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $text = ''
    try {
        $raw = & $JavacPath -version 2>&1
        $text = ($raw | Out-String)
    } catch {
        $text = ''
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($null -eq $text) { return '' }
    return $text.Trim()
}

function Get-BizappJdkRank {
    param([int]$Major)
    # Long-term-support releases first, in the order this application is happiest on.
    if ($Major -eq 17) { return 0 }
    if ($Major -eq 21) { return 1 }
    if ($Major -eq 11) { return 2 }
    return 3
}

function Add-BizappCandidatePath {
    param($Bag, $Value)
    if ($null -eq $Value) { return }
    $s = [string]$Value
    if ($s.Trim().Length -eq 0) { return }
    $s = $s.Trim().Trim('"')
    $s = $s.TrimEnd('\')
    if ($s.Length -eq 0) { return }
    [void]$Bag.Add($s)
}

# -------------------------------------------------------------- public ---

function Test-JdkCandidate {
    <#
    .SYNOPSIS
    Returns $null when the given folder is not a usable JDK, otherwise an object
    describing it (Home, Major, Version, Javac, Java, Javaw, Jar, Jpackage).
    #>
    param(
        [Parameter(Mandatory = $true, Position = 0)]
        [Alias('Home')]
        [AllowNull()]
        [AllowEmptyString()]
        [string]$JdkHome
    )

    # The parameter is deliberately not called $Home: that is a read-only automatic
    # variable in Windows PowerShell.  The -Home alias keeps the documented call form.
    if ([string]::IsNullOrEmpty($JdkHome)) { return $null }
    $h = $JdkHome.Trim().Trim('"').TrimEnd('\')
    if ($h.Length -eq 0) { return $null }
    if (-not (Test-Path -LiteralPath $h -PathType Container)) { return $null }

    $javac = Join-Path $h 'bin\javac.exe'
    $java = Join-Path $h 'bin\java.exe'
    $javaw = Join-Path $h 'bin\javaw.exe'
    $jar = Join-Path $h 'bin\jar.exe'
    $jpackage = Join-Path $h 'bin\jpackage.exe'

    foreach ($required in @($javac, $java, $javaw, $jar)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { return $null }
    }

    # Never infer a version from a folder name - ask the compiler itself.
    $text = Get-BizappJdkVersionText -JavacPath $javac
    if ([string]::IsNullOrEmpty($text)) { return $null }
    $major = 0
    if ($text -match 'javac\s+(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        $minor = $Matches[2]
        if ($major -eq 1) {
            # javac 1.8.0_412 -> 8
            if ($null -ne $minor -and ([string]$minor).Length -gt 0) {
                $major = [int]$minor
            } else {
                return $null
            }
        }
    } else {
        return $null
    }
    if ($major -le 0) { return $null }

    $jp = $null
    if (Test-Path -LiteralPath $jpackage -PathType Leaf) { $jp = $jpackage }

    return New-Object PSObject -Property @{
        Home     = $h
        Major    = $major
        Version  = $text
        Javac    = $javac
        Java     = $java
        Javaw    = $javaw
        Jar      = $jar
        Jpackage = $jp
    }
}

function Find-Jdk {
    <#
    .SYNOPSIS
    Returns zero or more usable JDKs, best first.
    #>
    param(
        [int]$MinMajor = 11,
        [switch]$RequireJavac,
        [switch]$RequireJpackage
    )

    $bag = New-Object System.Collections.ArrayList

    # 1. explicit overrides
    Add-BizappCandidatePath $bag ([Environment]::GetEnvironmentVariable('BIZAPP_JDK_HOME'))
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
    if (-not [string]::IsNullOrEmpty($javaHome)) {
        Add-BizappCandidatePath $bag $javaHome
        try {
            Add-BizappCandidatePath $bag (Split-Path -Parent ($javaHome.TrimEnd('\')))
        } catch { }
    }

    # 2. whatever java/javac on PATH really point at
    $commands = @()
    try {
        $commands = @(Get-Command -Name 'java.exe', 'javac.exe' -CommandType Application -ErrorAction SilentlyContinue)
    } catch { $commands = @() }
    foreach ($c in $commands) {
        if ($null -eq $c) { continue }
        $p = $null
        try { $p = $c.Source } catch { $p = $null }
        if ([string]::IsNullOrEmpty($p)) {
            try { $p = $c.Path } catch { $p = $null }
        }
        if ([string]::IsNullOrEmpty($p)) { continue }
        try {
            $item = Get-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue
            if ($null -ne $item) {
                $target = $null
                try { $target = $item.Target } catch { $target = $null }
                if ($null -ne $target) {
                    foreach ($t in @($target)) {
                        if (-not [string]::IsNullOrEmpty([string]$t)) { $p = [string]$t; break }
                    }
                }
            }
        } catch { }
        try {
            $binDir = Split-Path -Parent $p
            if (-not [string]::IsNullOrEmpty($binDir)) {
                Add-BizappCandidatePath $bag (Split-Path -Parent $binDir)
            }
        } catch { }
    }

    # 3. the registry.  Enumerated recursively rather than schema-by-schema,
    #    because vendors disagree about where the version key sits.
    $vendorKeys = @(
        'SOFTWARE\JavaSoft\JDK',
        'SOFTWARE\JavaSoft\Java Development Kit',
        'SOFTWARE\JavaSoft\JRE',
        'SOFTWARE\JavaSoft\Java Runtime Environment',
        'SOFTWARE\Eclipse Adoptium\JDK',
        'SOFTWARE\Eclipse Foundation\JDK',
        'SOFTWARE\AdoptOpenJDK\JDK',
        'SOFTWARE\Semeru\JDK',
        'SOFTWARE\IBM\Semeru',
        'SOFTWARE\Azul Systems\Zulu',
        'SOFTWARE\Microsoft\JDK',
        'SOFTWARE\BellSoft\Liberica',
        'SOFTWARE\Amazon Corretto'
    )
    $valueNames = @('JavaHome', 'Path', 'InstallationPath', 'JAVA_HOME')
    foreach ($vk in $vendorKeys) {
        foreach ($prefix in @('HKLM:\', 'HKLM:\SOFTWARE\WOW6432Node\')) {
            if ($prefix -eq 'HKLM:\SOFTWARE\WOW6432Node\') {
                $full = 'HKLM:\SOFTWARE\WOW6432Node\' + $vk.Substring('SOFTWARE\'.Length)
            } else {
                $full = 'HKLM:\' + $vk
            }
            $keys = New-Object System.Collections.ArrayList
            try {
                $rootKey = Get-Item -LiteralPath $full -ErrorAction SilentlyContinue
                if ($null -ne $rootKey) { [void]$keys.Add($rootKey) }
                foreach ($child in @(Get-ChildItem -LiteralPath $full -Recurse -ErrorAction SilentlyContinue)) {
                    if ($null -ne $child) { [void]$keys.Add($child) }
                }
            } catch { }
            foreach ($k in $keys) {
                foreach ($vn in $valueNames) {
                    $v = $null
                    try { $v = $k.GetValue($vn) } catch { $v = $null }
                    if ($null -ne $v) { Add-BizappCandidatePath $bag $v }
                }
            }
        }
    }

    # 4. the filesystem, as a backstop for machines where nothing is registered
    $bases = New-Object System.Collections.ArrayList
    $pf = [Environment]::GetEnvironmentVariable('ProgramFiles')
    $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    $pd = [Environment]::GetEnvironmentVariable('ProgramData')
    $lad = [Environment]::GetEnvironmentVariable('LOCALAPPDATA')
    $up = [Environment]::GetEnvironmentVariable('USERPROFILE')
    $sd = [Environment]::GetEnvironmentVariable('SystemDrive')
    if (-not [string]::IsNullOrEmpty($pf)) {
        foreach ($n in @('Java', 'Eclipse Adoptium', 'Eclipse Foundation', 'AdoptOpenJDK', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft', 'Semeru', 'RedHat')) {
            [void]$bases.Add((Join-Path $pf $n))
        }
    }
    if (-not [string]::IsNullOrEmpty($pf86)) { [void]$bases.Add((Join-Path $pf86 'Java')) }
    if (-not [string]::IsNullOrEmpty($pd)) { [void]$bases.Add((Join-Path $pd 'chocolatey\lib')) }
    if (-not [string]::IsNullOrEmpty($lad)) {
        [void]$bases.Add((Join-Path $lad 'Programs\Eclipse Adoptium'))
        [void]$bases.Add((Join-Path $lad 'Programs\Microsoft'))
        [void]$bases.Add((Join-Path $lad 'Microsoft\WinGet\Packages'))
    }
    if (-not [string]::IsNullOrEmpty($up)) { [void]$bases.Add((Join-Path $up 'scoop\apps')) }
    if (-not [string]::IsNullOrEmpty($sd)) { [void]$bases.Add((Join-Path $sd '\Java')) }

    foreach ($base in $bases) {
        if ([string]::IsNullOrEmpty($base)) { continue }
        if (-not (Test-Path -LiteralPath $base -PathType Container)) { continue }
        $dirs = @()
        try {
            $dirs = @(Get-ChildItem -LiteralPath $base -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue)
        } catch { $dirs = @() }
        foreach ($d in $dirs) {
            if ($null -eq $d) { continue }
            $probe = Join-Path $d.FullName 'bin\javac.exe'
            if (Test-Path -LiteralPath $probe -PathType Leaf) {
                Add-BizappCandidatePath $bag $d.FullName
            }
        }
    }

    # de-duplicate on Home, keeping first-seen order, then validate
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $results = New-Object System.Collections.ArrayList
    $order = 0
    foreach ($candidateHome in $bag) {
        if ([string]::IsNullOrEmpty($candidateHome)) { continue }
        if (-not $seen.Add($candidateHome)) { continue }
        $cand = Test-JdkCandidate -Home $candidateHome
        if ($null -eq $cand) { continue }
        if ($cand.Major -lt $MinMajor) { continue }
        if ($RequireJavac -and (-not (Test-Path -LiteralPath $cand.Javac -PathType Leaf))) { continue }
        if ($RequireJpackage -and ($null -eq $cand.Jpackage)) { continue }
        Add-Member -InputObject $cand -MemberType NoteProperty -Name 'Rank' -Value (Get-BizappJdkRank -Major $cand.Major)
        Add-Member -InputObject $cand -MemberType NoteProperty -Name 'Order' -Value $order
        [void]$results.Add($cand)
        $order = $order + 1
    }

    if ($results.Count -eq 0) { return }

    # Sort-Object is not a stable sort in Windows PowerShell, so Order is carried
    # as an explicit final key to keep the result deterministic.
    $sorted = $results | Sort-Object -Property `
        @{ Expression = { $_.Rank }; Ascending = $true }, `
        @{ Expression = { $_.Major }; Descending = $true }, `
        @{ Expression = { $_.Order }; Ascending = $true }
    return $sorted
}

Export-ModuleMember -Function Test-JdkCandidate, Find-Jdk
