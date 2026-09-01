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
REM PURPOSE: console launcher for BIZAPP POS. This is the diagnostic path to use
REM when the Start Menu / Desktop shortcut appears to do nothing: it keeps a
REM console window open so JVM errors and stack traces stay visible.
REM
REM Application logger output goes to the log file, not to this console -
REM see the "logging to" line printed below.
REM
REM Extra JVM options can be added through the BIZAPP_JAVA_OPTS environment
REM variable, for example:  set BIZAPP_JAVA_OPTS=-Xmx3072m
REM The Start Menu and Desktop shortcuts cannot read it - a .lnk stores a fixed
REM argument string - so this launcher is the place to use it.

setlocal EnableExtensions

REM %~dp0 always ends in a backslash. "%HERE%..\.." plus %%~fI canonicalises the
REM repository root and strips the trailing backslash; plain "%~dp0..\.." does not.
set "HERE=%~dp0"
for %%I in ("%HERE%..\..") do set "ROOT=%%~fI"

REM ---- locate the application jar --------------------------------------------
REM dist\ is the build engine's default, and BIZAPP_DIST_DIR is honoured because
REM the engine honours it. A one-off  install.cmd --dist-dir <path>  is recorded
REM nowhere this launcher can read, so :nojar names that flag rather than
REM claiming the application was never built.
set "JAR=%ROOT%\dist\nordpos.jar"
if defined BIZAPP_DIST_DIR if exist "%BIZAPP_DIST_DIR%\nordpos.jar" set "JAR=%BIZAPP_DIST_DIR%\nordpos.jar"
if not exist "%JAR%" goto :nojar

REM ---- locate a Java runtime -------------------------------------------------
set "JAVA_EXE="
if defined BIZAPP_JAVA_HOME if exist "%BIZAPP_JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%BIZAPP_JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
if not defined JAVA_EXE goto :nojava

REM ---- log directory and logging configuration -------------------------------
REM java.util.logging's FileHandler does NOT create parent directories: without
REM this it throws NoSuchFileException on its .lck file and logs nothing at all.
REM
REM THIS LAUNCHER NEVER WRITES logging.properties. install.cmd is its only
REM writer, on purpose. The file has to carry the log directory as a
REM .properties VALUE, and a directory holding a non-ASCII character - the
REM profile of a user named Mueller-with-an-umlaut, say - has to be escaped as
REM \uXXXX there. Only the installer can do that (ConvertTo-BizappPropertiesValue
REM in install\windows\lib\Install-BizappPos.ps1); a copy generated here would be
REM wrong on such a profile. The parenthesised echo block that wrote it also
REM broke outright on a path holding a shell metacharacter.
REM Worse, it would OVERWRITE the installer's correct copy, which both .lnk
REM shortcuts point at, so one run of this launcher would disable logging for
REM the Start Menu and Desktop shortcuts too. This script only SELECTS a file
REM that already exists; it never creates, rewrites or deletes one.
REM
REM LOCALAPPDATA is empty in some service and runas contexts. Install-BizappPos
REM and Uninstall-BizappPos both fall back to %USERPROFILE%\AppData\Local, so
REM this does the same and all three name one directory.
set "LAD="
if defined LOCALAPPDATA set "LAD=%LOCALAPPDATA%"
if not defined LAD set "LAD=%USERPROFILE%\AppData\Local"
set "LOGBASE=%LAD%\BIZAPP POS"
set "LOGDIR=%LOGBASE%\logs"
set "LOGCFG=%LOGBASE%\logging.properties"

REM First choice is the installer's copy, which has this machine's real log
REM directory resolved into it. Failing that, the checked-in template - but that
REM one can only name the directory as %h/AppData/Local/..., where %h is the
REM JVM's user.home, so the directory created and reported below has to follow it
REM there. Otherwise FileHandler writes where nobody looks and the "logging to"
REM line printed below would name a folder that stays empty.
set "LOGARG=%LOGCFG%"
if not exist "%LOGCFG%" set "LOGARG=%HERE%lib\logging.properties"
if not exist "%LOGCFG%" set "LOGDIR=%USERPROFILE%\AppData\Local\BIZAPP POS\logs"
if not exist "%LOGDIR%" md "%LOGDIR%" >nul 2>&1

echo [BIZAPP POS] java:        "%JAVA_EXE%"
echo [BIZAPP POS] repository:  "%ROOT%"
echo [BIZAPP POS] application: "%JAR%"
echo [BIZAPP POS] logging to:  "%LOGDIR%"
if defined BIZAPP_JAVA_OPTS echo [BIZAPP POS] extra options: %BIZAPP_JAVA_OPTS%
echo.

REM ---- launch ----------------------------------------------------------------
REM cwd MUST be the repository root: StartPOS resolves new File("webapps/")
REM against the current directory while its class is being initialised.
pushd "%ROOT%"

REM -Dfile.encoding=UTF-8 is mandatory here: the default charset on a typical
REM en-US Windows JDK up to 17 is windows-1252 and the app ships non-ASCII
REM resources. -Ddirname.path tells AppConfig where lib-jdbc\derbyclient.jar is.
REM -Dswing.defaultlaf avoids the Substance look-and-feel crash on Java 9+.
REM %%p reaches the JVM as %p, its process-id placeholder.
REM There is deliberately no -Dderby.system.home: ServerDatabase.java:40 sets
REM that property itself from user.home on every start, so it never had any
REM effect. The database is always <user home>\.derby-db.
REM %BIZAPP_JAVA_OPTS% is unquoted on purpose, so several options word-split,
REM and it comes last so a value such as -Xmx3072m overrides the -Xmx above.
"%JAVA_EXE%" ^
 -Xms256m ^
 -Xmx1024m ^
 -Dfile.encoding=UTF-8 ^
 -Ddirname.path="%ROOT%" ^
 -Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel ^
 -DKETTLE_PLUGIN_BASE_FOLDERS="%ROOT%\lib-ext\data-integration\plugins" ^
 -Djava.util.logging.config.file="%LOGARG%" ^
 -XX:ErrorFile="%LOGDIR%\hs_err_pid%%p.log" ^
 %BIZAPP_JAVA_OPTS% ^
 -jar "%JAR%" %*

REM Capture the exit code before popd: popd resets ERRORLEVEL.
set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" (
  echo.
  echo [BIZAPP POS] exited with code %RC%
  echo              Log files: "%LOGDIR%"
)
goto :finish

:nojar
echo [error] BIZAPP POS has not been built yet, or it was built somewhere else.
echo         Looked for: "%JAR%"
echo         Run install.cmd first.
echo         If you built it with  install.cmd --dist-dir ^<path^>, this launcher
echo         does not know that path. Either set BIZAPP_DIST_DIR to that folder
echo         first, or use the Start Menu shortcut, which has the real path in it.
set "RC=2"
goto :finish

:nojava
echo [error] Java was not found.
echo         BIZAPP POS needs Java 11 or newer to run.
echo         Install it with:
echo             winget install --id EclipseAdoptium.Temurin.17.JDK -e
echo         or download it from https://adoptium.net
echo         Already installed? Set JAVA_HOME to the folder that contains
echo         bin\java.exe, open a new window, and try again.
set "RC=3"
goto :finish

:finish
REM Every failure path ends here, so a double-clicked window stays open long
REM enough to read the error. A clean exit closes as before; arguments mean a
REM script or a console started this, and neither should ever be made to wait.
if not "%RC%"=="0" if "%~1"=="" (
  echo.
  echo Press any key to close...
  pause >nul
)
exit /b %RC%
