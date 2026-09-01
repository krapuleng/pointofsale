@echo off
REM BIZAPP POS - installer / build tooling
REM Copyright (C) 2026
REM This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
REM This program is free software: you can redistribute it and/or modify it under
REM the terms of the GNU General Public License as published by the Free Software
REM Foundation, either version 3 of the License, or (at your option) any later
REM version.  See https://www.gnu.org/licenses/
REM
REM (The GPL notice normally wraps that URL in angle brackets. They are omitted
REM  here on purpose: cmd.exe still processes < and > as redirection operators on
REM  a REM line, which would create stray files named "https:" in the checkout.)
REM
REM AUTHORED WITHOUT A WINDOWS TEST RUN. If this script misbehaves, please report
REM the exact console text together with the output of:  ver
REM and:  powershell -NoProfile -Command "$PSVersionTable.PSVersion"
REM
REM PURPOSE: repo-root double-click shim. All real work lives in
REM          install\windows\install.cmd - do not duplicate logic here.

setlocal EnableExtensions

REM %~dp0 always ends in a backslash, so it is used as "%HERE%rest\of\path"
REM and never as "%~dp0" immediately followed by more text (the trailing
REM backslash would escape the closing quote).
set "HERE=%~dp0"

if not exist "%HERE%install\windows\install.cmd" goto :missing

REM No pause on this path: the script called here does its own, and pausing
REM twice would make a double-click ask to be closed two times.
call "%HERE%install\windows\install.cmd" %*
exit /b %ERRORLEVEL%

:missing
echo [error] install\windows\install.cmd is missing.
echo         This does not look like a complete BIZAPP POS checkout.
echo         Re-clone it with:
echo             git clone https://github.com/krapuleng/pointofsale.git
set "RC=5"
goto :finish

:finish
REM Only this shim's own failure reaches here, and it is the one a partial ZIP
REM extraction produces. Without the pause an Explorer double-click shows the
REM error for a few milliseconds and then closes the window.
if "%~1"=="" (
  echo.
  echo Press any key to close...
  pause >nul
)
exit /b %RC%
