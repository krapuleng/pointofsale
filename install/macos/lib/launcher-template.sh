#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# Template for Contents/MacOS/bizapp-pos inside the generated BIZAPP POS.app.
# install/macos/install.command substitutes the four @@...@@ placeholders below.
#
# This MUST stay /bin/sh and must never be run under zsh: zsh does not
# word-split unquoted variables, which would collapse the JVM flag list into a
# single argv entry.

set -u

# A Finder-launched application inherits a minimal environment. Append (never
# prepend) the standard system directories so date/sed/cut/mkdir are always
# resolvable, while still letting a user's own PATH win when looking for java.
if [ -n "${PATH:-}" ]; then
    PATH="$PATH:/usr/bin:/bin:/usr/sbin:/sbin"
else
    PATH="/usr/bin:/bin:/usr/sbin:/sbin"
fi
export PATH

BIZAPP_ROOT="@@REPO_ROOT@@"
BIZAPP_JAVA_BIN="@@JAVA_BIN@@"
BIZAPP_JAR="@@JAR@@"
BIZAPP_LAUNCHER_VERSION="@@INSTALLER_VERSION@@"

# Contents/ of the enclosing bundle, so Resources/bizapp.icns can be found even
# if the bundle is renamed or moved.
CONTENTS=$(cd -P -- "$(dirname -- "$0")/.." && pwd -P)

# A Finder-launched application has no stderr at all. Every diagnostic has to
# land in this file or a failure is completely invisible.
LOGDIR="$HOME/Library/Logs/BIZAPP POS"
LOG="$LOGDIR/bizapp-pos.log"
mkdir -p "$LOGDIR" 2>/dev/null || true
[ -w "$LOGDIR" ] || LOG="/dev/null"

bizapp_log() {
    printf '%s\n' "$1" >>"$LOG" 2>/dev/null || true
}

# die <exit code> <message>: record it, show it, and stop.
die() {
    _code=$1
    _msg=$2
    bizapp_log "[error] $_msg"
    # AppleScript string literals escape backslash and double quote.
    _esc=$(printf '%s' "$_msg" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')
    /usr/bin/osascript -e "display alert \"BIZAPP POS\" message \"$_esc\" as critical" >/dev/null 2>&1 || true
    exit "$_code"
}

bizapp_log "--- $(date '+%Y-%m-%d %H:%M:%S') launcher $BIZAPP_LAUNCHER_VERSION starting"

# Major version of the JDK/JRE at $1 (echoes the integer, returns 1 if unknown).
bizapp_java_major() {
    _v=$("$1" -version 2>&1 | sed -n '1s/.*"\([0-9][^"]*\)".*/\1/p')
    [ -n "$_v" ] || return 1
    case "$_v" in
        1.*) printf '%s\n' "$_v" | cut -d. -f2 ;;
        *)   printf '%s\n' "$_v" | cut -d. -f1 ;;
    esac
}

# Java resolution, in preference order.
JAVA=""
if [ -n "${BIZAPP_JAVA_HOME:-}" ] && [ -x "${BIZAPP_JAVA_HOME}/bin/java" ]; then
    JAVA="${BIZAPP_JAVA_HOME}/bin/java"
elif [ -x "$BIZAPP_JAVA_BIN" ]; then
    JAVA="$BIZAPP_JAVA_BIN"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA="${JAVA_HOME}/bin/java"
else
    _c=$(command -v java 2>/dev/null || true)
    if [ -n "$_c" ] && [ -x "$_c" ]; then
        JAVA="$_c"
    else
        _h=$(/usr/libexec/java_home 2>/dev/null || true)
        if [ -n "$_h" ] && [ -x "$_h/bin/java" ]; then
            JAVA="$_h/bin/java"
        fi
    fi
fi

[ -n "$JAVA" ] || die 3 "No Java 11 or newer was found. Install one with: brew install --cask temurin@21  or from https://adoptium.net, then re-run the installer."

if [ ! -f "$BIZAPP_JAR" ]; then
    die 2 "BIZAPP POS has not been built yet, or the checkout has moved. Re-run install.command in $BIZAPP_ROOT."
fi

JAVA_MAJOR=$(bizapp_java_major "$JAVA" 2>/dev/null || true)
bizapp_log "--- $(date '+%Y-%m-%d %H:%M:%S') starting: java=${JAVA_MAJOR:-unknown} home=$JAVA"

if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 11 ] 2>/dev/null; then
    die 4 "BIZAPP POS needs Java 11 or newer, but $JAVA is Java $JAVA_MAJOR. Install a newer one with: brew install --cask temurin@21  or from https://adoptium.net"
fi

# Required: StartPOS resolves new File(\"webapps/\").getAbsolutePath() at class
# initialisation, relative to the current working directory.
#
# Deliberately NOT passed: -Dderby.system.home. ServerDatabase.java:40 overwrites
# it at runtime with <user.home>/.derby-db, so setting it here only pretends the
# database location can be moved. The database always lives in <user.home>.
cd "$BIZAPP_ROOT" || die 2 "The BIZAPP POS folder $BIZAPP_ROOT no longer exists. Re-install from a fresh checkout."

set -- \
    -Xms256m \
    -Xmx1024m \
    -Dfile.encoding=UTF-8 \
    -Ddirname.path="$BIZAPP_ROOT" \
    -Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel \
    -DKETTLE_PLUGIN_BASE_FOLDERS="$BIZAPP_ROOT/lib-ext/data-integration/plugins" \
    -Dapple.laf.useScreenMenuBar=true \
    -Xdock:name="BIZAPP POS" \
    -XX:ErrorFile="$LOGDIR/hs_err_pid%p.log"

if [ -f "$CONTENTS/Resources/bizapp.icns" ]; then
    set -- "$@" -Xdock:icon="$CONTENTS/Resources/bizapp.icns"
fi

exec "$JAVA" "$@" ${BIZAPP_JAVA_OPTS:-} -jar "$BIZAPP_JAR" >>"$LOG" 2>&1
