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
REM PURPOSE: Windows uninstall entry point. Thin shim over the PowerShell
REM payload, for the same reason install.cmd is one: double-clicking a .ps1
REM opens Notepad.
REM
REM This never removes your settings or your database unless you pass
REM --remove-data AND type the confirmation phrase it asks for.

setlocal EnableExtensions

REM %~dp0 always ends in a backslash - use it as "%HERE%rest", never as "%~dp0rest".
set "HERE=%~dp0"
set "PAYLOAD=%HERE%lib\Uninstall-BizappPos.ps1"

REM Same LOCALAPPDATA fallback as Install-BizappPos.ps1, Uninstall-BizappPos.ps1
REM and run.cmd, so the by-hand advice at :nopayload cannot print  rd /s /q
REM "\BIZAPP POS"  - a path at the root of the current drive - on a machine
REM where LOCALAPPDATA is empty.
set "LAD="
if defined LOCALAPPDATA set "LAD=%LOCALAPPDATA%"
if not defined LAD set "LAD=%USERPROFILE%\AppData\Local"

if not exist "%PAYLOAD%" goto :nopayload

set "PSEXE="
where powershell.exe >nul 2>&1 && set "PSEXE=powershell.exe"
if not defined PSEXE if exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not defined PSEXE goto :nops

"%PSEXE%" -NoProfile -NoLogo -ExecutionPolicy Bypass -File "%PAYLOAD%" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo [FAILED] BIZAPP POS uninstall exited with code %RC%.
)
goto :finish

:nopayload
echo [error] install\windows\lib\Uninstall-BizappPos.ps1 is missing.
echo         This does not look like a complete BIZAPP POS checkout.
echo         You can remove BIZAPP POS by hand instead:
echo             del "%APPDATA%\Microsoft\Windows\Start Menu\Programs\BIZAPP POS.lnk"
echo             rd /s /q "%LAD%\BIZAPP POS"
echo         Your settings and database are never touched by that.
set "RC=5"
goto :finish

:nops
echo [error] Windows PowerShell was not found.
echo         BIZAPP POS uninstall needs Windows PowerShell 5.1, which is built
echo         into Windows 7 SP1 and every later version of Windows.
echo         Expected it at:
echo             %SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe
set "RC=9009"
goto :finish

:finish
REM EVERY path ends here, the failure labels above included. Pause on an
REM interactive double-click, success or failure, so the summary of what was
REM removed and what was kept - or the reason nothing happened - can be read.
if "%~1"=="" (
  echo.
  echo Press any key to close...
  pause >nul
)
exit /b %RC%
