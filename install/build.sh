#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# The shared BIZAPP POS build engine, POSIX sh port.
#
# Compiles every source root with plain javac, stages the non-Java resources,
# writes a manifest whose Class-Path points back at lib/, packages dist/nordpos.jar
# and self-checks the result.  Apache Ant and NetBeans are never used and are not
# required.  install/build.ps1 is the PowerShell port of this file and MUST behave
# identically: same flags, same stdout, same exit codes.
#
# STREAM CONTRACT
#   stderr : everything a human reads (banner, "==>" steps, [warn], [error], tool output)
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

set -u

BIZAPP_INSTALLER_VERSION=1.0.0
BIZAPP_APP_VERSION=4.0
BIZAPP_MAIN_CLASS=com.openbravo.pos.forms.StartPOS
BIZAPP_JAR_NAME=nordpos.jar
BIZAPP_JDK_MIN=11
BIZAPP_JDK_TESTED_MAX=24
BIZAPP_EXPECTED_CP_ENTRIES=86

bizapp_quiet=0
bizapp_verify=0
bizapp_clean=0
bizapp_release=11
bizapp_opt_jdk=
bizapp_opt_build_dir=
bizapp_opt_dist_dir=
bizapp_action=build
bizapp_tmpdir=

# --------------------------------------------------------------- output ---

bizapp_step()  { [ "$bizapp_quiet" -eq 1 ] || printf '==> %s\n' "$1" >&2; }
bizapp_warn()  { printf '[warn] %s\n' "$1" >&2; }
bizapp_error() { printf '[error] %s\n' "$1" >&2; }
bizapp_hint()  { printf '        %s\n' "$1" >&2; }

bizapp_usage() {
    printf '%s\n' "usage: sh install/build.sh [options]" >&2
    printf '%s\n' "" >&2
    printf '%s\n' "  --build-dir <path>   intermediate output   (default <repo>/build, env BIZAPP_BUILD_DIR)" >&2
    printf '%s\n' "  --dist-dir <path>    final output          (default <repo>/dist,  env BIZAPP_DIST_DIR)" >&2
    printf '%s\n' "  --jdk <java_home>    JDK to build with     (env BIZAPP_JDK_HOME, then JAVA_HOME)" >&2
    printf '%s\n' "  --release <n>        javac --release level (default 11)" >&2
    printf '%s\n' "  --clean              delete this engine's own build artifacts first" >&2
    printf '%s\n' "                       (files it did not write are always left alone)" >&2
    printf '%s\n' "  --quiet              suppress '==>' progress lines" >&2
    printf '%s\n' "  --verify             print extra self-check detail" >&2
    printf '%s\n' "  --print-classpath    print the resolved classpath and exit" >&2
    printf '%s\n' "  --print-jar          print the jar path that would be produced and exit" >&2
    printf '%s\n' "  --version            print the build engine version and exit" >&2
    printf '%s\n' "  --help               this text" >&2
    printf '%s\n' "" >&2
    printf '%s\n' "Every option also accepts the --name=value form." >&2
}

bizapp_cleanup() {
    if [ -n "$bizapp_tmpdir" ] && [ -d "$bizapp_tmpdir" ]; then
        rm -rf "$bizapp_tmpdir"
    fi
}
trap bizapp_cleanup EXIT
trap 'bizapp_cleanup; exit 130' HUP INT TERM

# ------------------------------------------------------------- helpers ---

# bizapp_canon <dir> -> the fully resolved absolute directory (must already exist).
bizapp_canon() {
    ( cd -P -- "$1" 2>/dev/null && pwd -P ) 2>/dev/null
}

# bizapp_relpath <from-dir> <to-path>  (both absolute and canonical)
bizapp_relpath() {
    LC_ALL=C awk -v from="$1" -v to="$2" '
    BEGIN {
        nf = split(from, F, "/");
        nt = split(to, T, "/");
        i = 1;
        while (i <= nf && i <= nt && F[i] == T[i]) i++;
        out = "";
        for (j = i; j <= nf; j++) out = out "../";
        for (j = i; j <= nt; j++) { out = out T[j]; if (j < nt) out = out "/"; }
        sub(/\/$/, "", out);
        if (out == "") out = ".";
        print out;
    }'
}

# bizapp_is_ancestor <candidate-dir> <path>  -> 0 when candidate is a strict ancestor
bizapp_is_ancestor() {
    case "$2" in
        "$1"/*) return 0 ;;
    esac
    return 1
}

# --------------------------------------------------------- option parse ---

while [ $# -gt 0 ]; do
    case $1 in
        --build-dir)
            if [ $# -lt 2 ]; then bizapp_error "option --build-dir requires a value"; bizapp_usage; exit 2; fi
            bizapp_opt_build_dir=$2; shift 2 ;;
        --build-dir=*) bizapp_opt_build_dir=${1#--build-dir=}; shift ;;
        --dist-dir)
            if [ $# -lt 2 ]; then bizapp_error "option --dist-dir requires a value"; bizapp_usage; exit 2; fi
            bizapp_opt_dist_dir=$2; shift 2 ;;
        --dist-dir=*) bizapp_opt_dist_dir=${1#--dist-dir=}; shift ;;
        --jdk)
            if [ $# -lt 2 ]; then bizapp_error "option --jdk requires a value"; bizapp_usage; exit 2; fi
            bizapp_opt_jdk=$2; shift 2 ;;
        --jdk=*) bizapp_opt_jdk=${1#--jdk=}; shift ;;
        --release)
            if [ $# -lt 2 ]; then bizapp_error "option --release requires a value"; bizapp_usage; exit 2; fi
            bizapp_release=$2; shift 2 ;;
        --release=*) bizapp_release=${1#--release=}; shift ;;
        --clean)            bizapp_clean=1; shift ;;
        --quiet)            bizapp_quiet=1; shift ;;
        --verify)           bizapp_verify=1; shift ;;
        --print-classpath)  bizapp_action=print-classpath; shift ;;
        --print-jar)        bizapp_action=print-jar; shift ;;
        --version)          bizapp_action=version; shift ;;
        --help|-h)          bizapp_usage; exit 0 ;;
        *)
            bizapp_error "unknown option: $1"
            bizapp_usage
            exit 2 ;;
    esac
done

case ${bizapp_release:-} in
    ''|*[!0-9]*)
        bizapp_error "--release needs a whole number, got '$bizapp_release'."
        bizapp_hint "Example: --release 11"
        exit 2 ;;
esac

if [ "$bizapp_action" = version ]; then
    printf 'BIZAPP POS build engine %s\n' "$BIZAPP_INSTALLER_VERSION"
    exit 0
fi

bizapp_step "BIZAPP POS build engine $BIZAPP_INSTALLER_VERSION  (BIZAPP POS $BIZAPP_APP_VERSION)"

# ------------------------------------------------- step 1: repo layout ---

bizapp_self=$(cd -P -- "$(dirname -- "$0")" 2>/dev/null && pwd -P) || bizapp_self=
if [ -z "$bizapp_self" ]; then
    bizapp_error "cannot determine where this script lives."
    bizapp_hint "Run it as: sh /full/path/to/install/build.sh"
    exit 10
fi
BIZAPP_REPO_ROOT=$(bizapp_canon "$bizapp_self/..") || BIZAPP_REPO_ROOT=
if [ -z "$BIZAPP_REPO_ROOT" ]; then
    bizapp_error "cannot determine the repository root from $bizapp_self."
    bizapp_hint "Keep build.sh inside the checkout, in the install/ directory."
    exit 10
fi

bizapp_step "Checking the checkout at $BIZAPP_REPO_ROOT"
for bizapp_p in nbproject/project.properties lib lib-jdbc services/META-INF/services \
                src-beans src-data src-peripheral src-pos src-server src-sync \
                locales reports templates transformations; do
    if [ ! -e "$BIZAPP_REPO_ROOT/$bizapp_p" ]; then
        bizapp_error "this does not look like a BIZAPP POS checkout: $BIZAPP_REPO_ROOT/$bizapp_p is missing"
        bizapp_hint "Clone the whole repository: git clone https://github.com/krapuleng/pointofsale.git"
        bizapp_hint "A partial copy or a downloaded sub-folder will not build."
        exit 5
    fi
done

# ------------------------------------------ step 2: output directories ---

BIZAPP_BUILD_DIR=${bizapp_opt_build_dir:-${BIZAPP_BUILD_DIR:-$BIZAPP_REPO_ROOT/build}}
BIZAPP_DIST_DIR=${bizapp_opt_dist_dir:-${BIZAPP_DIST_DIR:-$BIZAPP_REPO_ROOT/dist}}

# Reject a path inside the committed 'dist - bizpapp' tree BEFORE creating anything.
# Every other guarded location (the repository root, $HOME, /, an ancestor of the
# repository) already exists, so mkdir -p there creates nothing; this is the only
# case where the guard below could otherwise fire after a directory was made.
for bizapp_d in "$BIZAPP_BUILD_DIR" "$BIZAPP_DIST_DIR"; do
    case $bizapp_d in
        *"dist - bizpapp"*)
            bizapp_error "refusing to use '$bizapp_d' as an output directory: it is inside the committed 'dist - bizpapp' directory."
            bizapp_hint "The build engine deletes and rewrites its output directories."
            bizapp_hint "Use something like: --build-dir \"$BIZAPP_REPO_ROOT/build\" --dist-dir \"$BIZAPP_REPO_ROOT/dist\""
            exit 2 ;;
    esac
done

if ! mkdir -p "$BIZAPP_BUILD_DIR" 2>/dev/null; then
    bizapp_error "cannot create the build directory '$BIZAPP_BUILD_DIR'."
    bizapp_hint "Choose a writable location, for example: --build-dir \"\$HOME/bizapp-build\""
    exit 10
fi
if ! mkdir -p "$BIZAPP_DIST_DIR" 2>/dev/null; then
    bizapp_error "cannot create the output directory '$BIZAPP_DIST_DIR'."
    bizapp_hint "Choose a writable location, for example: --dist-dir \"\$HOME/bizapp-dist\""
    exit 10
fi
BIZAPP_BUILD_DIR=$(bizapp_canon "$BIZAPP_BUILD_DIR")
BIZAPP_DIST_DIR=$(bizapp_canon "$BIZAPP_DIST_DIR")

# Canonicalise HOME the same way the output directories were canonicalised just
# above, or the refusal is trivially defeated: HOME=/Users/me/ (trailing slash)
# or a HOME that resolves through a symlink compares unequal to the resolved
# output path and the "it is your home directory" guard never fires.
bizapp_home_canon=$(bizapp_canon "${HOME:-/nonexistent}")

for bizapp_d in "$BIZAPP_BUILD_DIR" "$BIZAPP_DIST_DIR"; do
    bizapp_bad=
    [ "$bizapp_d" != "$BIZAPP_REPO_ROOT" ] || bizapp_bad="it is the repository root itself"
    [ "$bizapp_d" != "$bizapp_home_canon" ] || bizapp_bad="it is your home directory"
    [ "$bizapp_d" != "/" ] || bizapp_bad="it is the root of the filesystem"
    if bizapp_is_ancestor "$bizapp_d" "$BIZAPP_REPO_ROOT"; then
        bizapp_bad="it contains the repository"
    fi
    case $bizapp_d in
        *"dist - bizpapp"*) bizapp_bad="it is inside the committed 'dist - bizpapp' directory" ;;
    esac
    if [ -n "$bizapp_bad" ]; then
        bizapp_error "refusing to use '$bizapp_d' as an output directory: $bizapp_bad."
        bizapp_hint "The build engine deletes and rewrites its output directories."
        bizapp_hint "Use something like: --build-dir \"$BIZAPP_REPO_ROOT/build\" --dist-dir \"$BIZAPP_REPO_ROOT/dist\""
        exit 2
    fi
done

bizapp_tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/bizapp-build-$$.XXXXXX") || bizapp_tmpdir=
if [ -z "$bizapp_tmpdir" ]; then
    bizapp_error "cannot create a temporary directory in ${TMPDIR:-/tmp}."
    bizapp_hint "Check that the directory exists and is writable, or set TMPDIR to one that is."
    exit 10
fi

# ------------------------------------------------------- step 3: JDK ---

bizapp_step "Looking for a Java Development Kit"
BIZAPP_JH=
if [ -n "$bizapp_opt_jdk" ]; then
    BIZAPP_JH=$bizapp_opt_jdk
    bizapp_jdk_src="--jdk"
elif [ -n "${BIZAPP_JDK_HOME:-}" ]; then
    BIZAPP_JH=$BIZAPP_JDK_HOME
    bizapp_jdk_src="BIZAPP_JDK_HOME"
elif [ -n "${JAVA_HOME:-}" ]; then
    BIZAPP_JH=$JAVA_HOME
    bizapp_jdk_src="JAVA_HOME"
else
    bizapp_jdk_src="auto-discovery"
    if [ -r "$BIZAPP_REPO_ROOT/install/lib/jdk-find.sh" ]; then
        . "$BIZAPP_REPO_ROOT/install/lib/jdk-find.sh"
        BIZAPP_JH=$(bizapp_find_jdk --min "$BIZAPP_JDK_MIN" --need-javac) || BIZAPP_JH=
    fi
fi

bizapp_no_jdk() {
    bizapp_error "No Java Development Kit (JDK) was found."
    bizapp_hint "BIZAPP POS needs a JDK (which includes the compiler), not just a Java runtime."
    bizapp_hint "If 'java -version' works but this still fails, you have a runtime only."
    bizapp_hint "Install one:  brew install --cask temurin@21      (macOS)"
    bizapp_hint "              winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)"
    bizapp_hint "              or download from https://adoptium.net"
    bizapp_hint "Already installed? Point JAVA_HOME at the folder that contains bin/javac and try again."
    exit 3
}

[ -n "$BIZAPP_JH" ] || bizapp_no_jdk
BIZAPP_JH=${BIZAPP_JH%/}
if [ ! -d "$BIZAPP_JH" ]; then
    bizapp_error "the JDK location from $bizapp_jdk_src does not exist: $BIZAPP_JH"
    bizapp_hint "Point it at the folder that contains bin/javac, or unset it to let setup search."
    exit 3
fi
for bizapp_tool in javac jar java; do
    if [ ! -x "$BIZAPP_JH/bin/$bizapp_tool" ]; then
        bizapp_error "the JDK at $BIZAPP_JH (from $bizapp_jdk_src) has no usable bin/$bizapp_tool."
        bizapp_hint "That location is a Java runtime, not a full JDK."
        bizapp_hint "Install a JDK:  brew install --cask temurin@21   or   https://adoptium.net"
        bizapp_hint "Then point JAVA_HOME at the folder that contains bin/javac."
        exit 3
    fi
done

bizapp_javac_banner=$("$BIZAPP_JH/bin/javac" -version 2>&1 | sed -n '1p')
BIZAPP_JDK_MAJOR=$(printf '%s\n' "$bizapp_javac_banner" | LC_ALL=C sed -n 's/^javac[ 	][ 	]*\([0-9][0-9]*\).*/\1/p')
if [ -z "$BIZAPP_JDK_MAJOR" ]; then
    BIZAPP_JDK_MAJOR=$(printf '%s\n' "$bizapp_javac_banner" | LC_ALL=C sed -n 's/^javac[ 	][ 	]*1\.\([0-9][0-9]*\).*/\1/p')
fi
if [ "${BIZAPP_JDK_MAJOR:-}" = "1" ]; then
    BIZAPP_JDK_MAJOR=$(printf '%s\n' "$bizapp_javac_banner" | LC_ALL=C sed -n 's/^javac[ 	][ 	]*1\.\([0-9][0-9]*\).*/\1/p')
fi
case ${BIZAPP_JDK_MAJOR:-} in
    ''|*[!0-9]*)
        bizapp_error "cannot read the version of the JDK at $BIZAPP_JH ('$bizapp_javac_banner')."
        bizapp_hint "Check that \"$BIZAPP_JH/bin/javac\" -version works."
        exit 3 ;;
esac
if [ "$BIZAPP_JDK_MAJOR" -lt "$BIZAPP_JDK_MIN" ]; then
    bizapp_error "JDK $BIZAPP_JDK_MIN or newer is required (found $BIZAPP_JDK_MAJOR at $BIZAPP_JH)."
    bizapp_hint "Install a newer one:  brew install --cask temurin@21      (macOS)"
    bizapp_hint "                      winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)"
    bizapp_hint "                      or download from https://adoptium.net"
    bizapp_hint "Then re-run with:  --jdk \"/path/to/that/jdk\"   (or set JAVA_HOME)"
    exit 4
fi
if [ "$BIZAPP_JDK_MAJOR" -gt "$BIZAPP_JDK_TESTED_MAX" ]; then
    bizapp_warn "JDK $BIZAPP_JDK_MAJOR is newer than any version this installer has been tested against ($BIZAPP_JDK_MIN-$BIZAPP_JDK_TESTED_MAX). Continuing."
fi
bizapp_step "Using JDK $BIZAPP_JDK_MAJOR at $BIZAPP_JH"

# ------------------------------------------- step 4: classpath from Ant ---

bizapp_step "Deriving the library classpath from nbproject/project.properties"
if ! LC_ALL=C awk '
function process(l,   key, val, p) {
    if (l ~ /^[ \t]*#/ || l ~ /^[ \t]*!/) return
    p = index(l, "=")
    if (p == 0) return
    key = substr(l, 1, p - 1)
    val = substr(l, p + 1)
    gsub(/^[ \t]+/, "", key); gsub(/[ \t]+$/, "", key)
    gsub(/^[ \t]+/, "", val)
    if (key ~ /^file\.reference\./) ref[key] = val
    else if (key == "javac.classpath") cp = val
}
{
    line = $0
    sub(/\r$/, "", line)
    if (cont) { sub(/^[ \t]+/, "", line); buf = buf line } else { buf = line }
    n = 0; t = buf
    while (length(t) > 0 && substr(t, length(t), 1) == "\\") { n++; t = substr(t, 1, length(t) - 1) }
    if (n % 2 == 1) { buf = substr(buf, 1, length(buf) - 1); cont = 1; next }
    cont = 0
    process(buf)
    buf = ""
}
END {
    if (cont && buf != "") process(buf)
    if (cp == "") { print "javac.classpath was not found" > "/dev/stderr"; exit 5 }
    n = split(cp, A, ":")
    for (i = 1; i <= n; i++) {
        tok = A[i]
        gsub(/^[ \t]+/, "", tok); gsub(/[ \t]+$/, "", tok)
        if (tok == "") continue
        if (tok ~ /^\$\{.*\}$/) {
            k = substr(tok, 3, length(tok) - 3)
            if (!(k in ref)) { print "unresolved reference ${" k "}" > "/dev/stderr"; exit 5 }
            v = ref[k]
        } else v = tok
        o = ""
        m = length(v)
        for (j = 1; j <= m; j++) { c = substr(v, j, 1); o = o ((c == "\\") ? "/" : c) }
        while (o ~ /\/\//) sub(/\/\//, "/", o)
        print o
    }
}
' "$BIZAPP_REPO_ROOT/nbproject/project.properties" > "$bizapp_tmpdir/cp.raw" 2>"$bizapp_tmpdir/cp.err"; then
    bizapp_error "could not read the library list out of nbproject/project.properties."
    while IFS= read -r bizapp_l; do bizapp_hint "$bizapp_l"; done < "$bizapp_tmpdir/cp.err"
    bizapp_hint "Restore that file from git: git checkout -- nbproject/project.properties"
    exit 5
fi

: > "$bizapp_tmpdir/cp.txt"
bizapp_cp_count=0
while IFS= read -r bizapp_e; do
    [ -n "$bizapp_e" ] || continue
    bizapp_r=
    case $bizapp_e in
        /*) : ;;
        *) [ ! -f "$BIZAPP_REPO_ROOT/$bizapp_e" ] || bizapp_r=$bizapp_e ;;
    esac
    if [ -z "$bizapp_r" ]; then
        bizapp_base=${bizapp_e##*/}
        bizapp_r=$( cd "$BIZAPP_REPO_ROOT" && find lib -type f -name "$bizapp_base" -print 2>/dev/null | sed -n '1p' )
        if [ -z "$bizapp_r" ]; then
            bizapp_error "classpath entry '$bizapp_e' does not exist and no '$bizapp_base' was found under lib/"
            bizapp_hint "The checkout is incomplete. Re-clone it, or restore lib/ from git."
            exit 5
        fi
        bizapp_warn "remapped stale classpath entry '$bizapp_e' -> '$bizapp_r'"
    fi
    printf '%s\n' "$bizapp_r" >> "$bizapp_tmpdir/cp.txt"
    bizapp_cp_count=$((bizapp_cp_count + 1))
done < "$bizapp_tmpdir/cp.raw"

if [ "$bizapp_cp_count" -eq 0 ]; then
    bizapp_error "no library entries could be derived from nbproject/project.properties."
    bizapp_hint "Restore that file from git: git checkout -- nbproject/project.properties"
    exit 5
fi
if [ "$bizapp_cp_count" -ne "$BIZAPP_EXPECTED_CP_ENTRIES" ]; then
    bizapp_warn "expected $BIZAPP_EXPECTED_CP_ENTRIES classpath entries, derived $bizapp_cp_count"
fi
bizapp_step "Resolved $bizapp_cp_count library jars"

BIZAPP_JAR_PATH=$BIZAPP_DIST_DIR/$BIZAPP_JAR_NAME
BIZAPP_CP_FILE=$BIZAPP_DIST_DIR/classpath.txt

if [ "$bizapp_action" = print-classpath ]; then
    LC_ALL=C awk 'NR>1{printf ":"} {printf "%s", $0} END{printf "\n"}' "$bizapp_tmpdir/cp.txt"
    exit 0
fi
if [ "$bizapp_action" = print-jar ]; then
    printf '%s\n' "$BIZAPP_JAR_PATH"
    exit 0
fi

# ------------------------------------------------------- step 5: clean ---

# --clean deletes ONLY the artifacts this engine itself writes, by exact name,
# inside --build-dir / --dist-dir (or BIZAPP_BUILD_DIR / BIZAPP_DIST_DIR).  It
# never deletes a caller-named directory recursively, so '--clean --dist-dir
# ~/Documents' removes those artifacts and leaves every other file untouched.
# There is deliberately no "was this directory made by us?" heuristic: a plain
# build writes bizapp-classes/, javac.args, MANIFEST.MF, classpath.txt,
# build-info.txt and the jar into whatever directory the caller named, so such a
# test poisons itself - one build makes a user's Documents look like build
# output and the next --clean eats it.  Deleting only what we wrote needs no
# test at all.
# The compiler output subdirectory is named 'bizapp-classes', not 'classes', for
# exactly this reason: it is the one directory removed recursively (here and on
# every plain build below), so it must be a name this engine owns by
# construction rather than one a caller might already be using for their own
# files.  '--build-dir ~/Documents' must not eat ~/Documents/classes.
# The directory itself goes only when our own artifacts were all it held: a
# plain rmdir that is allowed to fail.  The refusals in step 2 (repository root,
# $HOME, /, an ancestor of the repository, anything inside 'dist - bizpapp')
# still apply to both directories and are checked there.
if [ "$bizapp_clean" -eq 1 ]; then
    bizapp_step "Cleaning build artifacts in $BIZAPP_BUILD_DIR and $BIZAPP_DIST_DIR"
    for bizapp_d in "$BIZAPP_BUILD_DIR" "$BIZAPP_DIST_DIR"; do
        # Exact literals only. A glob here would destroy the committed 'dist - bizpapp'.
        rm -rf "$bizapp_d/bizapp-classes"
        rm -f "$bizapp_d/javac.args"
        rm -f "$bizapp_d/MANIFEST.MF"
        rm -f "$bizapp_d/classpath.txt"
        rm -f "$bizapp_d/build-info.txt"
        rm -f "$bizapp_d/$BIZAPP_JAR_NAME"
        rmdir "$bizapp_d" 2>/dev/null || :
    done
fi
rm -rf "$BIZAPP_BUILD_DIR/bizapp-classes"
rm -f "$BIZAPP_JAR_PATH"
mkdir -p "$BIZAPP_BUILD_DIR/bizapp-classes" "$BIZAPP_DIST_DIR"

# ------------------------------------------ step 6: sources and compile ---

bizapp_step "Collecting Java sources"
( cd "$BIZAPP_REPO_ROOT" && find src-beans src-data src-peripheral src-pos src-server src-sync \
    -name '*.java' -print ) > "$bizapp_tmpdir/sources.txt" 2>/dev/null
bizapp_src_count=$(LC_ALL=C awk 'END{print NR+0}' "$bizapp_tmpdir/sources.txt")
if [ "$bizapp_src_count" -eq 0 ]; then
    bizapp_error "no Java source files were found under src-beans, src-data, src-peripheral, src-pos, src-server or src-sync."
    bizapp_hint "The checkout is incomplete. Re-clone: git clone https://github.com/krapuleng/pointofsale.git"
    exit 5
fi
bizapp_step "Found $bizapp_src_count Java source files"

BIZAPP_ARGFILE=$BIZAPP_BUILD_DIR/javac.args
{
    printf '%s\n' "-nowarn"
    printf '%s\n' "-encoding"
    printf '%s\n' "UTF-8"
    printf '%s\n' "--release"
    printf '%s\n' "$bizapp_release"
    printf '%s\n' "-d"
    printf '%s\n' "$BIZAPP_BUILD_DIR/bizapp-classes"
    printf '%s\n' "-cp"
    LC_ALL=C awk 'NR>1{printf ":"} {printf "%s", $0} END{printf "\n"}' "$bizapp_tmpdir/cp.txt"
    cat "$bizapp_tmpdir/sources.txt"
} > "$bizapp_tmpdir/args.raw"

# Argfile quoting, shared rule with the PowerShell port:
#   a token is written bare only when every character is on the conservative
#   whitelist [A-Za-z0-9_./:=+-];
#   anything else is double-quoted with every backslash doubled (a single
#   backslash inside quotes is eaten by javac as an escape character).
# A whitelist, not a "contains a space" test, because javac's @argfile tokenizer
# also treats ' and " as quote characters and treats # as a comment introducer
# ANYWHERE in a token, not only at its start.  Measured on OpenJDK 11.0.28: the
# bare token /home/O'Brien/pos/build/classes reaches javac as
# /home/OBrien/pos/build/classes (the apostrophe opens a quote that closes at
# end of line), and a bare token that contains # at any position - such as
# /home/repo#1/pos/build/classes - produces NO argument at all: the # and the
# rest of the line are discarded, so the preceding flag silently loses its
# value.  Quoted, both round-trip intact.
# Note: unlike the Windows port this does NOT rewrite backslashes as slashes -
# on POSIX a backslash is an ordinary character in a file name, not a separator.
LC_ALL=C awk '
{
    t = $0
    if (t !~ /^[A-Za-z0-9_.\/:=+-]+$/) {
        o = ""
        n = length(t)
        for (i = 1; i <= n; i++) {
            c = substr(t, i, 1)
            if (c == "\\") o = o "\\\\"
            else if (c == "\"") o = o "\\\""
            else o = o c
        }
        print "\"" o "\""
    } else {
        print t
    }
}
' "$bizapp_tmpdir/args.raw" > "$BIZAPP_ARGFILE"

bizapp_step "Compiling $bizapp_src_count sources with --release $bizapp_release (this takes about 30 seconds)"
bizapp_javac_rc=0
( cd "$BIZAPP_REPO_ROOT" && "$BIZAPP_JH/bin/javac" "@$BIZAPP_ARGFILE" ) \
    > "$bizapp_tmpdir/javac.log" 2>&1 || bizapp_javac_rc=$?
if [ -s "$bizapp_tmpdir/javac.log" ]; then
    cat "$bizapp_tmpdir/javac.log" >&2
fi
if [ "$bizapp_javac_rc" -ne 0 ]; then
    if LC_ALL=C grep -q 'java\.applet' "$bizapp_tmpdir/javac.log" 2>/dev/null; then
        bizapp_error "this JDK has removed the java.applet API, which src-peripheral/com/nordpos/device/ticket/TicketParser.java still uses. Use a JDK between $BIZAPP_JDK_MIN and $BIZAPP_JDK_TESTED_MAX."
        bizapp_hint "Install one:  brew install --cask temurin@21      (macOS)"
        bizapp_hint "              winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)"
        bizapp_hint "Then re-run with:  --jdk \"/path/to/that/jdk\""
    else
        bizapp_error "compilation failed (javac exit $bizapp_javac_rc); see the messages above."
        bizapp_hint "The argument file javac was given is at: $BIZAPP_ARGFILE"
        bizapp_hint "If this is a fresh clone, re-run with --clean; if it persists, report the first error above."
    fi
    exit 6
fi

# ------------------------------------------- step 7: stage resources ---

bizapp_step "Staging resources (images, reports, scripts, service descriptors)"
bizapp_staged=0

# find -exec ... {} + passes each name as its own argv entry, so file names
# containing spaces (src-pos/.../Window.Description - Copy.txt) are safe.
bizapp_stage_root() {
    bsr_root=$1
    bsr_filter=$2
    [ -d "$BIZAPP_REPO_ROOT/$bsr_root" ] || return 0
    if [ "$bsr_filter" -eq 1 ]; then
        ( cd "$BIZAPP_REPO_ROOT/$bsr_root" && find . -type f \
            ! -name '*.java' ! -name '*.form' ! -name '*.jpa' \
            -exec sh -c 'd=$1; shift; for f do t=$d/${f#./}; mkdir -p "${t%/*}"; cp -p "$f" "$t"; done' \
            _ "$BIZAPP_BUILD_DIR/bizapp-classes" {} + ) || return 1
        bsr_n=$( cd "$BIZAPP_REPO_ROOT/$bsr_root" && find . -type f \
            ! -name '*.java' ! -name '*.form' ! -name '*.jpa' -print | LC_ALL=C awk 'END{print NR+0}' )
    else
        ( cd "$BIZAPP_REPO_ROOT/$bsr_root" && find . -type f \
            -exec sh -c 'd=$1; shift; for f do t=$d/${f#./}; mkdir -p "${t%/*}"; cp -p "$f" "$t"; done' \
            _ "$BIZAPP_BUILD_DIR/bizapp-classes" {} + ) || return 1
        bsr_n=$( cd "$BIZAPP_REPO_ROOT/$bsr_root" && find . -type f -print | LC_ALL=C awk 'END{print NR+0}' )
    fi
    bizapp_staged=$((bizapp_staged + bsr_n))
    return 0
}

for bizapp_r in src-beans src-data src-peripheral src-pos src-server src-sync locales reports; do
    if ! bizapp_stage_root "$bizapp_r" 1; then
        bizapp_error "could not stage resources from $bizapp_r into $BIZAPP_BUILD_DIR/bizapp-classes."
        bizapp_hint "Check that $BIZAPP_BUILD_DIR is writable and has free space."
        exit 7
    fi
done
# fonts/ is deliberately NOT staged: lib/jasperreports-6.2/jasperreports-fonts-6.2.0.jar
# already carries the same DejaVu faces plus the jasperreports_extension.properties that
# actually registers them, and the loose tree would only shadow it.
for bizapp_r in templates transformations; do
    if ! bizapp_stage_root "$bizapp_r" 0; then
        bizapp_error "could not stage resources from $bizapp_r into $BIZAPP_BUILD_DIR/bizapp-classes."
        bizapp_hint "Check that $BIZAPP_BUILD_DIR is writable and has free space."
        exit 7
    fi
done

mkdir -p "$BIZAPP_BUILD_DIR/bizapp-classes/META-INF/services"
if ! ( cd "$BIZAPP_REPO_ROOT/services/META-INF/services" && find . -type f \
        -exec sh -c 'd=$1; shift; for f do t=$d/${f#./}; mkdir -p "${t%/*}"; cp -p "$f" "$t"; done' \
        _ "$BIZAPP_BUILD_DIR/bizapp-classes/META-INF/services" {} + ); then
    bizapp_error "could not stage the ServiceLoader descriptors from services/META-INF/services."
    bizapp_hint "Without them every printer, display, scale and payment driver silently disappears."
    exit 7
fi
bizapp_svc=$( cd "$BIZAPP_REPO_ROOT/services/META-INF/services" && find . -type f -print | LC_ALL=C awk 'END{print NR+0}' )
bizapp_staged=$((bizapp_staged + bizapp_svc))
bizapp_step "Staged $bizapp_staged resource files ($bizapp_svc of them ServiceLoader descriptors)"

# ---------------------------------------------------- step 8: manifest ---

bizapp_step "Writing the jar manifest"
: > "$bizapp_tmpdir/cprel.txt"
while IFS= read -r bizapp_e; do
    [ -n "$bizapp_e" ] || continue
    bizapp_rel=$(bizapp_relpath "$BIZAPP_DIST_DIR" "$BIZAPP_REPO_ROOT/$bizapp_e")
    if printf '%s' "$bizapp_rel" | LC_ALL=C grep -q '[^!-~]'; then
        bizapp_error "the chosen --dist-dir produces a jar Class-Path with spaces or non-ASCII characters. Use a --dist-dir inside the repository."
        bizapp_hint "Offending entry: $bizapp_rel"
        bizapp_hint "Try:  sh install/build.sh --build-dir \"$BIZAPP_REPO_ROOT/build\" --dist-dir \"$BIZAPP_REPO_ROOT/dist\""
        exit 7
    fi
    printf '%s\n' "$bizapp_rel" >> "$bizapp_tmpdir/cprel.txt"
done < "$bizapp_tmpdir/cp.txt"

bizapp_cp_line=$(LC_ALL=C awk '{ if (NR > 1) printf " "; printf "%s", $0 } END { printf "\n" }' "$bizapp_tmpdir/cprel.txt")
BIZAPP_MANIFEST=$BIZAPP_BUILD_DIR/MANIFEST.MF
{
    printf '%s\n' "Manifest-Version: 1.0"
    printf '%s\n' "Main-Class: $BIZAPP_MAIN_CLASS"
    # Fold on BYTES: no physical manifest line may exceed 72 bytes or `jar` dies
    # with "line too long" and produces no jar at all.
    printf 'Class-Path: %s\n' "$bizapp_cp_line" | LC_ALL=C awk '
    {
        line = $0
        n = length(line)
        if (n <= 72) { print line; next }
        print substr(line, 1, 72)
        i = 73
        while (i <= n) { print " " substr(line, i, 71); i += 71 }
    }'
} > "$BIZAPP_MANIFEST"

if LC_ALL=C awk '{ if (length($0) > 72) exit 1 }' "$BIZAPP_MANIFEST"; then
    :
else
    bizapp_error "the generated manifest has a line longer than 72 bytes; jar would reject it."
    bizapp_hint "This is a bug in the build engine. Report the contents of $BIZAPP_MANIFEST"
    exit 7
fi

# ----------------------------------------------------- step 9: package ---

bizapp_step "Packaging $BIZAPP_JAR_PATH"
if ! ( cd "$BIZAPP_REPO_ROOT" && "$BIZAPP_JH/bin/jar" cfm "$BIZAPP_JAR_PATH" "$BIZAPP_MANIFEST" \
        -C "$BIZAPP_BUILD_DIR/bizapp-classes" . ) >&2; then
    bizapp_error "packaging the jar failed."
    bizapp_hint "Check free space and that $BIZAPP_DIST_DIR is writable."
    exit 7
fi
if [ ! -f "$BIZAPP_JAR_PATH" ]; then
    bizapp_error "jar reported success but $BIZAPP_JAR_PATH was not created."
    bizapp_hint "Check free space in $BIZAPP_DIST_DIR."
    exit 7
fi

# -------------------------------------------------- step 10: self-check ---

bizapp_step "Checking the packaged application"
if ! "$BIZAPP_JH/bin/jar" tf "$BIZAPP_JAR_PATH" > "$bizapp_tmpdir/jar.list" 2>"$bizapp_tmpdir/jar.err"; then
    bizapp_error "the jar was written but cannot be listed; it is probably corrupt."
    bizapp_hint "Re-run with --clean."
    exit 8
fi
bizapp_missing=0
for bizapp_entry in \
    com/openbravo/pos/forms/StartPOS.class \
    com/openbravo/pos/scripts/Derby-create-nordpos.sql \
    com/nordpos/templates/Schema.Printer.xsd \
    com/nordpos/transformations/csv/EXPORT_PRODUCTS.ktr \
    com/openbravo/pos/templates/Role.Administrator.xml \
    com/openbravo/images/favicon.png \
    META-INF/services/com.nordpos.device.display.DisplayInterface \
    META-INF/services/com.nordpos.device.fiscalprinter.FiscalPrinterInterface \
    META-INF/services/com.nordpos.device.labelprinter.LabelPrinterInterface \
    META-INF/services/com.nordpos.device.plu.InputOutputInterface \
    META-INF/services/com.nordpos.device.receiptprinter.ReceiptPrinterInterface \
    META-INF/services/com.nordpos.device.scale.ScaleInterface \
    META-INF/services/com.nordpos.payment.gateway.PaymentGatewayInterface \
; do
    if ! LC_ALL=C grep -Fxq "$bizapp_entry" "$bizapp_tmpdir/jar.list"; then
        bizapp_error "the packaged jar is missing $bizapp_entry"
        bizapp_missing=$((bizapp_missing + 1))
    fi
done
bizapp_jar_size=$(LC_ALL=C wc -c < "$BIZAPP_JAR_PATH" | tr -d ' ')
if [ "$bizapp_jar_size" -lt 1000000 ]; then
    bizapp_error "the packaged jar is only $bizapp_jar_size bytes; it should be over 1000000."
    bizapp_missing=$((bizapp_missing + 1))
fi
if [ "$bizapp_missing" -ne 0 ]; then
    bizapp_hint "The build produced an incomplete application and has been rejected."
    bizapp_hint "Re-run with --clean. If it happens again the checkout is incomplete - re-clone it."
    exit 8
fi
if [ "$bizapp_verify" -eq 1 ]; then
    bizapp_jar_entries=$(LC_ALL=C awk 'END{print NR+0}' "$bizapp_tmpdir/jar.list")
    bizapp_step "Self-check passed: $bizapp_jar_entries jar entries, $bizapp_jar_size bytes, 13 required entries present"
    bizapp_step "Manifest: $BIZAPP_MANIFEST"
    bizapp_step "Argument file: $BIZAPP_ARGFILE"
fi

# ---------------------------------------------- step 11: outputs, stdout ---

cp "$bizapp_tmpdir/cp.txt" "$BIZAPP_CP_FILE"

bizapp_git_head=
if command -v git >/dev/null 2>&1; then
    bizapp_git_head=$(git -C "$BIZAPP_REPO_ROOT" rev-parse --short HEAD 2>/dev/null) || bizapp_git_head=
fi
{
    printf '%s\n' "BIZAPP POS build information"
    printf '%s\n' "installer version : $BIZAPP_INSTALLER_VERSION"
    printf '%s\n' "application       : BIZAPP POS $BIZAPP_APP_VERSION"
    printf '%s\n' "built (UTC)       : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf '%s\n' "jdk               : $bizapp_javac_banner (major $BIZAPP_JDK_MAJOR)"
    printf '%s\n' "jdk home          : $BIZAPP_JH"
    printf '%s\n' "javac --release   : $bizapp_release"
    printf '%s\n' "source files      : $bizapp_src_count"
    printf '%s\n' "staged resources  : $bizapp_staged"
    printf '%s\n' "library jars      : $bizapp_cp_count"
    printf '%s\n' "jar               : $BIZAPP_JAR_PATH"
    printf '%s\n' "jar size (bytes)  : $bizapp_jar_size"
    printf '%s\n' "repository        : $BIZAPP_REPO_ROOT"
    if [ -n "$bizapp_git_head" ]; then
        printf '%s\n' "git commit        : $bizapp_git_head"
    fi
} > "$BIZAPP_DIST_DIR/build-info.txt"

bizapp_step "Build complete: $BIZAPP_JAR_PATH ($bizapp_jar_size bytes)"

printf 'BIZAPP_JAR=%s\n' "$BIZAPP_JAR_PATH"
printf 'BIZAPP_CLASSPATH_FILE=%s\n' "$BIZAPP_CP_FILE"
exit 0
