@echo off
REM BIZAPP POS - installer / build tooling
REM Copyright (C) 2026
REM This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
REM This program is free software: you can redistribute it and/or modify it under
REM the terms of the GNU General Public License as published by the Free Software
REM Foundation, either version 3 of the License, or (at your option) any later
REM version.  See https://www.gnu.org/licenses/
REM
REM (Angle brackets around the URL are omitted deliberately: cmd.exe processes
REM  < and > as redirection operators even on a REM line.)
REM
REM AUTHORED WITHOUT A WINDOWS TEST RUN. If this script misbehaves, please report
REM the exact console text together with the output of:  ver
REM and:  powershell -NoProfile -Command "$PSVersionTable.PSVersion"
REM
REM PURPOSE: Windows one-command installer entry point. This cmd shim is what
REM makes a double-click work: the default shell association for .ps1 is "edit",
REM so double-clicking install\windows\lib\Install-BizappPos.ps1 would open
REM Notepad no matter what the execution policy says.

setlocal EnableExtensions

REM %~dp0 always ends in a backslash - use it as "%HERE%rest", never as "%~dp0rest".
set "HERE=%~dp0"
set "PAYLOAD=%HERE%lib\Install-BizappPos.ps1"

if not exist "%PAYLOAD%" goto :nopayload

REM Locate Windows PowerShell. PATH is preferred; the absolute path is the
REM fallback for machines with a damaged PATH.
set "PSEXE="
where powershell.exe >nul 2>&1 && set "PSEXE=powershell.exe"
if not defined PSEXE if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not defined PSEXE goto :nops

REM -NoProfile   : a user $PROFILE must not be able to break setup.
REM -File        : propagates the script's "exit N" into %ERRORLEVEL%
REM                (-Command would not).
REM -ExecutionPolicy Bypass : covers the Restricted client default and the
REM                Mark-of-the-Web that a downloaded GitHub ZIP puts on files.
"%PSEXE%" -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%PAYLOAD%" %*
set "RC=%ERRORLEVEL%"

REM LOCALAPPDATA is empty in some service and runas contexts. Install-BizappPos
REM and Uninstall-BizappPos both fall back to %USERPROFILE%\AppData\Local and
REM run.cmd now does the same, so the path named below is the one setup really
REM wrote to. Resolved BEFORE the block: %LAD% inside a parenthesised block is
REM expanded when the block is parsed, so it has to be set on an earlier line.
set "LAD="
if defined LOCALAPPDATA set "LAD=%LOCALAPPDATA%"
if not defined LAD set "LAD=%USERPROFILE%\AppData\Local"

if not "%RC%"=="0" (
  echo.
  echo [FAILED] BIZAPP POS setup exited with code %RC%.
  echo          Full log: "%LAD%\BIZAPP POS\logs\install.log"
)
goto :finish

:nopayload
echo [error] install\windows\lib\Install-BizappPos.ps1 is missing.
echo         This does not look like a complete BIZAPP POS checkout.
echo         Re-clone it with:
echo             git clone https://github.com/krapuleng/pointofsale.git
set "RC=5"
goto :finish

:nops
echo [error] Windows PowerShell was not found.
echo         BIZAPP POS setup needs Windows PowerShell 5.1, which is built into
echo         Windows 7 SP1 and every later version of Windows.
echo         Expected it at:
echo             %SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe
echo         Restore that component, or add it to PATH, then run install.cmd again.
set "RC=9009"
goto :finish

:finish
REM EVERY path ends here, the failure labels above included. Pause only when
REM there are no arguments, i.e. an interactive double-click or a bare
REM "install.cmd"; CI and "install.cmd --quiet" never hang here.
REM Deviation from the drafted snippet, on purpose: the pause fires on failure
REM too. Without it a double-clicked window closes instantly and the user never
REM sees the error, which contradicts the honest-failure requirement. A missing
REM payload and a missing powershell.exe are the two failures a first-time user
REM is most likely to hit, so they must not be the two that flash past.
if "%~1"=="" (
  echo.
  echo Press any key to close...
  pause >nul
)
exit /b %RC%
