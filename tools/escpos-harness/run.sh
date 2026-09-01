#!/bin/sh
#
# NORD POS is a fork of Openbravo POS.
#
# Copyright (C) 2009-2013 Nord Trading Ltd. <http://www.nordpos.com>
#
# This file is part of NORD POS.
#
# NORD POS is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.
#
# NORD POS is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
# A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# NORD POS. If not, see <http://www.gnu.org/licenses/>.
#
# ---------------------------------------------------------------------------
# The BIZAPP POS ESC/POS byte level test harness.
#
#   sh tools/escpos-harness/run.sh
#
# Builds the jar, compiles the harness against it, and runs every assertion.
# No hardware is required and nothing is downloaded.
#
# This script lives under tools/, which is NOT one of the six compiled source
# roots (src-beans src-data src-peripheral src-pos src-server src-sync), so
# nothing here can ever reach the shipped jar.
#
# It RUNS install/build.sh. It never edits anything under install/.
# ---------------------------------------------------------------------------

set -eu

BIZAPP_JDK="/opt/homebrew/opt/openjdk@11"
BIZAPP_KEEP="no"
BIZAPP_PASSTHRU=""

usage() {
    cat <<'USAGE'
usage: sh tools/escpos-harness/run.sh [options]

  --jdk <java_home>   JDK to build and run with.
                      Default /opt/homebrew/opt/openjdk@11. JDK 11 is the
                      default because TicketParser imports java.applet and
                      install/build.sh refuses JDKs that removed it.
  --case <name>       Run one case only.
  --print <name>      Dump that case's ACTUAL bytes as annotated hex and exit 0
                      WITHOUT asserting. For eyeballing a new command sequence.
  --keep              Do not delete the temporary build directory.
  --help              This text.

There is deliberately no --update flag. Every golden file is typed from the
published ESC/POS command spec by a human and reviewed. A golden that
regenerates itself from the implementation proves nothing.
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --jdk)
            [ $# -ge 2 ] || { echo "--jdk needs a value" >&2; exit 2; }
            BIZAPP_JDK="$2"; shift 2 ;;
        --case)
            [ $# -ge 2 ] || { echo "--case needs a value" >&2; exit 2; }
            BIZAPP_PASSTHRU="$BIZAPP_PASSTHRU --case $2"; shift 2 ;;
        --print)
            [ $# -ge 2 ] || { echo "--print needs a value" >&2; exit 2; }
            BIZAPP_PASSTHRU="$BIZAPP_PASSTHRU --print $2"; shift 2 ;;
        --keep)
            BIZAPP_KEEP="yes"; shift ;;
        --help|-h)
            usage; exit 0 ;;
        *)
            echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# Resolve the repo root from $0, two levels up, so this works from any cwd.
BIZAPP_HARNESS=$( cd -- "$( dirname -- "$0" )" && pwd )
BIZAPP_ROOT=$( cd -- "$BIZAPP_HARNESS/../.." && pwd )

[ -x "$BIZAPP_JDK/bin/javac" ] || {
    echo "No javac at $BIZAPP_JDK/bin/javac. Pass --jdk <java_home>." >&2
    exit 2
}
[ -f "$BIZAPP_ROOT/install/build.sh" ] || {
    echo "Cannot find $BIZAPP_ROOT/install/build.sh - is this the BIZAPP POS repository?" >&2
    exit 2
}

BIZAPP_TMP=$( mktemp -d "${TMPDIR:-/tmp}/bizapp-escpos-harness.XXXXXX" )

cleanup() {
    if [ "$BIZAPP_KEEP" = "yes" ]; then
        echo "Kept: $BIZAPP_TMP" >&2
    else
        rm -rf "$BIZAPP_TMP"
    fi
}
trap cleanup EXIT

echo "==> Building the jar (JDK: $BIZAPP_JDK)" >&2
BIZAPP_BUILD_OUT="$BIZAPP_TMP/build.stdout"
sh "$BIZAPP_ROOT/install/build.sh" \
    --build-dir "$BIZAPP_TMP/build" \
    --dist-dir "$BIZAPP_TMP/dist" \
    --jdk "$BIZAPP_JDK" > "$BIZAPP_BUILD_OUT"

# build.sh puts human output on stderr and BIZAPP_JAR=<path> on stdout.
BIZAPP_JAR=$( sed -n 's/^BIZAPP_JAR=//p' "$BIZAPP_BUILD_OUT" | tail -n 1 )
[ -n "$BIZAPP_JAR" ] && [ -f "$BIZAPP_JAR" ] || {
    echo "install/build.sh did not report a jar on stdout. It printed:" >&2
    cat "$BIZAPP_BUILD_OUT" >&2
    exit 1
}
echo "==> Jar: $BIZAPP_JAR" >&2

echo "==> Compiling the harness" >&2
mkdir -p "$BIZAPP_TMP/classes"
find "$BIZAPP_HARNESS/src" -name '*.java' > "$BIZAPP_TMP/sources.txt"
"$BIZAPP_JDK/bin/javac" \
    -source 8 -target 8 -nowarn -encoding UTF-8 \
    -cp "$BIZAPP_JAR" \
    -d "$BIZAPP_TMP/classes" \
    @"$BIZAPP_TMP/sources.txt"

echo "==> Running the harness" >&2
set +e
# Not headless: the regression group constructs DevicePrinterPanel and
# DevicePrinterPrinter. On a headless box those cases SKIP rather than fail,
# which means a headless run proves strictly less - the summary says so.
"$BIZAPP_JDK/bin/java" \
    -cp "$BIZAPP_JAR:$BIZAPP_TMP/classes" \
    -Djava.awt.headless=false \
    com.nordpos.tools.escpos.EscPosHarness \
    --golden "$BIZAPP_HARNESS/golden" \
    --fixtures "$BIZAPP_HARNESS/fixtures" \
    $BIZAPP_PASSTHRU
BIZAPP_RC=$?
set -e

exit $BIZAPP_RC
