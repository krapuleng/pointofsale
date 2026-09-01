#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# The macOS installer for BIZAPP POS.
#
# It builds the application with the shared build engine (install/build.sh),
# generates an application icon, and installs a small launcher bundle
# (~/Applications/BIZAPP POS.app) that points at this checkout.
#
# This MUST stay /bin/sh and must never be run under zsh: zsh does not
# word-split unquoted variables, which would collapse flag lists into a single
# argv entry.
#
# It NEVER writes to ~/nordpos.properties or ~/.derby-db -- those are the
# operator's live settings and point-of-sale database. The single exception is
# the explicit --repair-laf flag, which rewrites exactly one key after taking a
# timestamped backup.

set -u

BIZAPP_INSTALLER_VERSION="1.0.0"
BIZAPP_APP_VERSION="4.0"
BIZAPP_APP_NAME="BIZAPP POS"
BIZAPP_BUNDLE_ID="com.nordpos.bizapp.pos"
BIZAPP_EXEC_NAME="bizapp-pos"
BIZAPP_ICON_NAME="bizapp.icns"
BIZAPP_JDK_MIN=11

# ---------------------------------------------------------------- output ----
opt_quiet=0
say()  { [ "$opt_quiet" -eq 1 ] || printf '%s\n' "$1" >&2; }
warn() { printf '[warn] %s\n' "$1" >&2; }
err()  { printf '[error] %s\n' "$1" >&2; }
hint() { printf '        %s\n' "$1" >&2; }
note() { printf '%s\n' "$1" >&2; }

STAGE=""
ORPHAN=""
# Set only while the destination is mid-swap and holds no application; cleanup
# uses them to put the previous version back. set -u makes declaring them here
# mandatory, since cleanup can run before the swap ever starts.
APP_OLD_SAVED=""
APP_TARGET=""
laf_blocked=0
laf_repaired=0
port_declined=0

# Run one command, with sudo only when installing into /Applications. A function
# rather than a $SUDO variable: an unquoted variable would not word-split under
# zsh, and this file has to behave identically under either shell.
bizapp_run() {
    if [ "${opt_system:-0}" -eq 1 ]; then
        sudo "$@"
    else
        "$@"
    fi
}

cleanup() {
    [ -n "$STAGE" ] && [ -d "$STAGE" ] && rm -rf "$STAGE"
    # Put the previous version back FIRST, before deleting anything. The swap is
    # two renames (see step 8); a signal that lands between them leaves the
    # destination holding only the .bizapp-old copy, and without this the till
    # is left with no application at all and nothing printed. Restoring before
    # the delete below is also the order the --system consent text promises.
    if [ -n "$APP_OLD_SAVED" ] && [ -e "$APP_OLD_SAVED" ] && [ -n "$APP_TARGET" ] && [ ! -e "$APP_TARGET" ]; then
        if bizapp_run mv "$APP_OLD_SAVED" "$APP_TARGET" >/dev/null 2>&1; then
            note "Cancelled. The previous version has been put back."
        else
            note "Cancelled. The previous version is intact at $APP_OLD_SAVED -"
            note "rename it to \"$APP_TARGET\" in Finder to restore it."
        fi
    fi
    # A bundle already moved into the destination under its temporary name is
    # not a finished install; never leave it lying beside the real application.
    [ -n "$ORPHAN" ] && [ -e "$ORPHAN" ] && bizapp_run rm -rf "$ORPHAN" >/dev/null 2>&1
    return 0
}
trap cleanup EXIT
# A handler that only returns is not enough: after it runs, sh resumes at
# the interrupted point and carries on into the swap with the staged bundle
# already deleted. Cancelling must stop the script, with the conventional
# 128+SIGINT status.
trap "cleanup; exit 130" HUP INT TERM

usage() {
    cat >&2 <<'USAGE'
BIZAPP POS - macOS installer

Usage: ./install.command [options]

Options:
  --launch              Start BIZAPP POS when the install finishes.
  --no-shortcut         Build only; do not create the application bundle.
  --system              Install into /Applications instead of ~/Applications.
                        Needs an administrator password; you will be asked first.
  --dest <dir>          Install the application bundle into <dir> instead of
                        ~/Applications. Useful for testing; no admin rights needed.
  --jdk <java_home>     Build with the JDK in <java_home> (the folder that
                        contains bin/javac). Overrides BIZAPP_JDK_HOME and
                        JAVA_HOME.
  --repair-laf          Repair a saved Substance look and feel in
                        ~/nordpos.properties (a timestamped backup is made).
  --clean               Discard previous build output before building.
  --build-dir <dir>     Intermediate build output (default <checkout>/build).
  --dist-dir <dir>      Final build output (default <checkout>/dist).
  --yes                 Answer yes to consent prompts (never to "DELETE MY DATA").
  --quiet               Suppress progress lines. Warnings and errors still print.
  --version             Print the installer version and exit.
  --help                Show this help and exit.

Every option also accepts the --name=value form.

Exit codes: 0 ok | 2 usage | 3 no JDK | 4 JDK too old | 5 bad checkout |
            6 compile failed | 7 packaging failed | 8 self-check failed |
            9 consent declined | 10 environment problem
USAGE
}

# ------------------------------------------------------------ arg parsing ----
opt_system=0
opt_no_shortcut=0
opt_launch=0
opt_repair_laf=0
opt_clean=0
opt_yes=0
opt_build_dir=""
opt_dist_dir=""
opt_dest=""
opt_dest_flag=""
opt_dest_set=0
opt_jdk=""

need_value() {
    if [ "$2" -lt 2 ]; then
        err "option $1 needs a value."
        hint "Example: $1 /path/to/directory"
        exit 2
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --system)        opt_system=1 ;;
        --no-shortcut)   opt_no_shortcut=1 ;;
        --no-shortcuts)  opt_no_shortcut=1 ;;
        --launch)        opt_launch=1 ;;
        --repair-laf)    opt_repair_laf=1 ;;
        --clean)         opt_clean=1 ;;
        --yes|-y)        opt_yes=1 ;;
        --quiet)         opt_quiet=1 ;;
        --build-dir)     need_value "$1" $#; opt_build_dir=$2; shift ;;
        --build-dir=*)   opt_build_dir=${1#--build-dir=} ;;
        --dist-dir)      need_value "$1" $#; opt_dist_dir=$2; shift ;;
        --dist-dir=*)    opt_dist_dir=${1#--dist-dir=} ;;
        --dest)          need_value "$1" $#; opt_dest_flag=$2; opt_dest_set=1; shift ;;
        --dest=*)        opt_dest_flag=${1#--dest=}; opt_dest_set=1 ;;
        --jdk)           need_value "$1" $#; opt_jdk=$2; shift ;;
        --jdk=*)         opt_jdk=${1#--jdk=} ;;
        --version)       printf '%s\n' "$BIZAPP_INSTALLER_VERSION"; exit 0 ;;
        --help|-h)       usage; exit 0 ;;
        *)
            err "unknown option: $1"
            usage
            exit 2
            ;;
    esac
    shift
done

# Only an explicit --dest on the command line conflicts with --system. An
# exported BIZAPP_APP_DEST (INSTALL.md recommends exporting it) is a default,
# not a request, so it loses silently to --system exactly as it already loses
# to the --dest flag. Refusing there would name a flag the operator never typed.
if [ "$opt_system" -eq 1 ] && [ "$opt_dest_set" -eq 1 ]; then
    err "--system and --dest cannot be used together."
    hint "Use --system to install into /Applications, or --dest <dir> for anywhere else."
    exit 2
fi

# Precedence: --system > --dest flag > BIZAPP_APP_DEST.
if [ "$opt_dest_set" -eq 1 ]; then
    opt_dest=$opt_dest_flag
elif [ "$opt_system" -eq 0 ]; then
    opt_dest="${BIZAPP_APP_DEST:-}"
fi

# --------------------------------------------------------- 1. banner/root ----
say "==> BIZAPP POS setup $BIZAPP_INSTALLER_VERSION  ($BIZAPP_APP_NAME $BIZAPP_APP_VERSION)"

SELF=$(cd -P -- "$(dirname -- "$0")" && pwd -P) || {
    err "could not determine where this script lives."
    hint "Run it from the checkout: sh install/macos/install.command"
    exit 10
}
REPO_ROOT=$(cd -P -- "$SELF/../.." && pwd -P) || {
    err "could not locate the BIZAPP POS checkout above $SELF."
    exit 5
}

# The launcher bakes REPO_ROOT into a shell script and an XML plist. Double
# quotes, dollar signs, backticks and newlines cannot be injected safely, so
# they are refused outright rather than silently producing a broken launcher.
case "$REPO_ROOT" in
    *'"'*|*'$'*|*'`'*)
        err "the folder path contains a character that cannot be used safely: $REPO_ROOT"
        hint "Double quotes, dollar signs and backtick characters are not supported."
        hint "Rename or move the folder, for example to \"\$HOME/pointofsale\", and run this again."
        exit 10
        ;;
esac
if [ "$REPO_ROOT" != "$(printf '%s' "$REPO_ROOT" | tr -d '\n')" ]; then
    err "the folder path contains a line break, which cannot be used safely."
    hint "Rename the folder and run this again."
    exit 10
fi

# ------------------------------------------------------------ 2. preflight ----
say "==> Checking this machine"

if [ "$(uname -s)" != "Darwin" ]; then
    err "this installer only runs on macOS (this machine reports $(uname -s))."
    hint "On Windows run install.cmd instead. See INSTALL.md for both platforms."
    exit 10
fi

if [ ! -d "$HOME" ] || [ ! -w "$HOME" ]; then
    err "your home folder $HOME is not writable."
    hint "BIZAPP POS stores its settings and database there and cannot continue."
    exit 10
fi

have_icon_tools=1
if [ ! -x /usr/bin/sips ] || [ ! -x /usr/bin/iconutil ]; then
    warn "sips or iconutil is missing, so the application will have no custom icon."
    hint "Everything else will work normally."
    have_icon_tools=0
fi

if [ ! -f "$REPO_ROOT/install/build.sh" ]; then
    err "this does not look like a BIZAPP POS checkout: $REPO_ROOT/install/build.sh is missing"
    hint "Clone the repository again: git clone https://github.com/krapuleng/pointofsale.git"
    exit 5
fi
if [ ! -f "$REPO_ROOT/install/lib/jdk-find.sh" ]; then
    err "this does not look like a BIZAPP POS checkout: $REPO_ROOT/install/lib/jdk-find.sh is missing"
    hint "Clone the repository again: git clone https://github.com/krapuleng/pointofsale.git"
    exit 5
fi

# ----------------------------------------------------------------- 3. JDK ----
say "==> Looking for a Java Development Kit (JDK 11 or newer)"

# shellcheck disable=SC1090
. "$REPO_ROOT/install/lib/jdk-find.sh"

bizapp_no_jdk_help() {
    hint "BIZAPP POS needs a JDK (which includes the compiler), not just a Java runtime."
    hint "If 'java -version' works but this still fails, you have a runtime only."
    hint "Install one:  brew install --cask temurin@21      (macOS)"
    hint "              or download from https://adoptium.net"
    hint "Already installed? Point JAVA_HOME at the folder that contains bin/javac and try again."
}

bizapp_confirm() {
    printf '%s [y/N] ' "$1" >&2
    IFS= read -r _answer || _answer=""
    case "$_answer" in
        [yY]|[yY][eE][sS]) return 0 ;;
        *) return 1 ;;
    esac
}

# Project-wide JDK precedence, identical to install/build.sh: an explicit --jdk
# beats BIZAPP_JDK_HOME, which beats JAVA_HOME, which beats searching the
# machine. bizapp_find_jdk is the search step only -- it ranks by highest major
# version, so calling it first would silently discard the operator's choice.
JDK_HOME=""
JDK_SRC="auto-discovery"
if [ -n "$opt_jdk" ]; then
    JDK_HOME=$opt_jdk
    JDK_SRC="--jdk"
elif [ -n "${BIZAPP_JDK_HOME:-}" ]; then
    JDK_HOME=$BIZAPP_JDK_HOME
    JDK_SRC="BIZAPP_JDK_HOME"
elif [ -n "${JAVA_HOME:-}" ]; then
    JDK_HOME=$JAVA_HOME
    JDK_SRC="JAVA_HOME"
else
    JDK_HOME=$(bizapp_find_jdk --min "$BIZAPP_JDK_MIN" --need-javac 2>/dev/null) || JDK_HOME=""
fi

# Only the search can come up empty; an explicit location that is wrong is an
# error, not an invitation to install a second JDK behind the operator's back.
if [ -z "$JDK_HOME" ]; then
    err "No Java Development Kit (JDK) was found."
    bizapp_no_jdk_help
    if command -v brew >/dev/null 2>&1; then
        if [ "$opt_yes" -eq 1 ] || [ -t 0 ]; then
            note ""
            note "This installer can run:  brew install --cask temurin@21"
            note "That installs software system-wide and Homebrew will ask you for your"
            note "administrator password. Nothing else on your machine is changed."
            if [ "$opt_yes" -eq 1 ] || bizapp_confirm "Install Temurin 21 with Homebrew now?"; then
                say "==> Installing Temurin 21 with Homebrew (this can take several minutes)"
                if ! brew install --cask temurin@21 >&2; then
                    err "Homebrew could not install Temurin 21."
                    hint "Install it manually from https://adoptium.net and run this installer again."
                    exit 3
                fi
                JDK_HOME=$(bizapp_find_jdk --min "$BIZAPP_JDK_MIN" --need-javac 2>/dev/null) || JDK_HOME=""
                if [ -z "$JDK_HOME" ]; then
                    err "the JDK was installed but this installer still cannot see it."
                    hint "Close this window, open a new Terminal, and run ./install.command again."
                    exit 3
                fi
            else
                note "No JDK was installed. Nothing on your machine was changed."
                exit 9
            fi
        else
            hint "Re-run this installer in a Terminal window to be offered an automatic install."
            exit 3
        fi
    else
        exit 3
    fi
fi

# Validate whatever we ended up with, using the build engine's own vocabulary.
JDK_HOME=${JDK_HOME%/}
if [ ! -d "$JDK_HOME" ]; then
    err "the JDK location from $JDK_SRC does not exist: $JDK_HOME"
    hint "Point it at the folder that contains bin/javac, or unset it to let setup search."
    exit 3
fi
for jdk_tool in javac jar java; do
    if [ ! -x "$JDK_HOME/bin/$jdk_tool" ]; then
        err "the JDK at $JDK_HOME (from $JDK_SRC) has no usable bin/$jdk_tool."
        hint "That location is a Java runtime, not a full JDK."
        hint "Install a JDK:  brew install --cask temurin@21   or   https://adoptium.net"
        hint "Then point JAVA_HOME at the folder that contains bin/javac."
        exit 3
    fi
done

JDK_MAJOR=$(bizapp_jdk_major "$JDK_HOME" 2>/dev/null) || JDK_MAJOR=""
case ${JDK_MAJOR:-} in
    ''|*[!0-9]*)
        err "cannot read the version of the JDK at $JDK_HOME (from $JDK_SRC)."
        hint "Check that \"$JDK_HOME/bin/javac\" -version works."
        exit 3 ;;
esac
if [ "$JDK_MAJOR" -lt "$BIZAPP_JDK_MIN" ]; then
    err "JDK $BIZAPP_JDK_MIN or newer is required (found $JDK_MAJOR at $JDK_HOME)."
    hint "Install a newer one:  brew install --cask temurin@21      (macOS)"
    hint "                      or download from https://adoptium.net"
    hint "Then re-run with:  --jdk \"/path/to/that/jdk\"   (or set JAVA_HOME)"
    exit 4
fi
say "==> Using JDK $JDK_MAJOR at $JDK_HOME (from $JDK_SRC)"

# --------------------------------------------------------------- 4. build ----
say "==> Building BIZAPP POS (this takes about 30 seconds)"

set -- --jdk "$JDK_HOME"
[ -n "$opt_build_dir" ] && set -- "$@" --build-dir "$opt_build_dir"
[ -n "$opt_dist_dir" ]  && set -- "$@" --dist-dir "$opt_dist_dir"
[ "$opt_clean" -eq 1 ]  && set -- "$@" --clean
[ "$opt_quiet" -eq 1 ]  && set -- "$@" --quiet

BUILD_OUT=$(sh "$REPO_ROOT/install/build.sh" "$@")
BUILD_RC=$?
if [ "$BUILD_RC" -ne 0 ]; then
    err "build failed."
    exit "$BUILD_RC"
fi

JAR=$(printf '%s\n' "$BUILD_OUT" | sed -n 's/^BIZAPP_JAR=//p' | head -n 1)
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    err "the build finished but did not report a usable application file."
    hint "Run the build on its own to see what happened:"
    hint "  sh \"$REPO_ROOT/install/build.sh\" --verify"
    exit 7
fi
say "==> Built $JAR"

if [ "$opt_no_shortcut" -eq 1 ]; then
    say "==> Skipping the application bundle (--no-shortcut)"
fi

# ---------------------------------------------------------------- 5-8. app ----
DEST=""
APP=""
if [ "$opt_no_shortcut" -eq 0 ]; then
    if [ -n "$opt_dest" ]; then
        DEST=$opt_dest
    elif [ "$opt_system" -eq 1 ]; then
        DEST="/Applications"
    else
        DEST="$HOME/Applications"
    fi

    if [ "$opt_system" -eq 1 ]; then
        # The exact inventory of what runs as root, in the order it runs. The new
        # bundle is copied in beside the old one and only then swapped, so the
        # expensive copy happens while the old bundle is still in place, so the
        # only moment /Applications holds no application is between the two
        # renames in step 8. An interrupt inside that window is caught: cleanup()
        # puts the previous version back before it deletes anything.
        note ""
        note "Installing into /Applications requires administrator rights."
        note "This installer will run these commands with sudo, and nothing else:"
        [ -d "$DEST" ] || note "  sudo mkdir -p \"$DEST\""
        note "  sudo mv <staged bundle> \"$DEST/$BIZAPP_APP_NAME.app.bizapp-new.$$\""
        if [ -e "$DEST/$BIZAPP_APP_NAME.app" ]; then
            note "  sudo mv \"$DEST/$BIZAPP_APP_NAME.app\" \"$DEST/$BIZAPP_APP_NAME.app.bizapp-old.$$\""
        fi
        note "  sudo mv \"$DEST/$BIZAPP_APP_NAME.app.bizapp-new.$$\" \"$DEST/$BIZAPP_APP_NAME.app\""
        if [ -e "$DEST/$BIZAPP_APP_NAME.app" ]; then
            note "  sudo rm -rf \"$DEST/$BIZAPP_APP_NAME.app.bizapp-old.$$\""
        fi
        note "If a step fails, or you cancel with Ctrl-C part-way through, these run"
        note "instead of the ones after it, to put the folder back as it was:"
        note "  sudo rm -rf \"$DEST/$BIZAPP_APP_NAME.app.bizapp-new.$$\""
        if [ -e "$DEST/$BIZAPP_APP_NAME.app" ]; then
            note "  sudo mv \"$DEST/$BIZAPP_APP_NAME.app.bizapp-old.$$\" \"$DEST/$BIZAPP_APP_NAME.app\""
        fi
        note "Only the temporary copies named above are touched, and this installer"
        note "made them itself."
        note "You will be asked for your password. Nothing else is changed."
        if [ "$opt_yes" -eq 0 ]; then
            if [ ! -t 0 ]; then
                err "--system needs your confirmation, but this is not an interactive session."
                hint "Re-run in a Terminal window, or add --yes, or drop --system to install"
                hint "into \"$HOME/Applications\" (no administrator password needed)."
                exit 9
            fi
            if ! bizapp_confirm "Install into /Applications?"; then
                note "Nothing was installed system-wide."
                exit 9
            fi
        fi
        # sudo cannot ask for a password without a terminal. Find that out here,
        # before anything is staged, so the failure is reported as what it is
        # rather than as a packaging error much later on.
        if ! sudo -n true >/dev/null 2>&1 && [ ! -t 0 ]; then
            err "--system needs an administrator password, but this is not an interactive session."
            hint "Re-run in a Terminal window, or drop --system to install into"
            hint "\"$HOME/Applications\" (no administrator password needed)."
            exit 9
        fi
    fi

    STAGE=$(mktemp -d "${TMPDIR:-/tmp}/bizapp-app.$$.XXXXXX") || {
        err "could not create a temporary staging folder."
        hint "Check that ${TMPDIR:-/tmp} exists and is writable."
        exit 7
    }
    BUNDLE="$STAGE/$BIZAPP_APP_NAME.app"
    mkdir -p "$BUNDLE/Contents/MacOS" "$BUNDLE/Contents/Resources" || {
        err "could not stage the application bundle in $STAGE"
        exit 7
    }

    # 5. Icon. A failure here is cosmetic, never fatal.
    icon_value=""
    if [ "$have_icon_tools" -eq 1 ]; then
        say "==> Generating the application icon"
        # make-icns.sh has no --quiet of its own, so its progress lines are
        # dropped here instead. Its exit code still decides success or failure.
        if [ "$opt_quiet" -eq 1 ]; then
            sh "$REPO_ROOT/install/macos/lib/make-icns.sh" "$BUNDLE/Contents/Resources/$BIZAPP_ICON_NAME" 2>/dev/null
        else
            sh "$REPO_ROOT/install/macos/lib/make-icns.sh" "$BUNDLE/Contents/Resources/$BIZAPP_ICON_NAME" >&2
        fi
        if [ $? -eq 0 ]; then
            icon_value=$BIZAPP_ICON_NAME
        else
            warn "the application icon could not be generated; using the default icon."
            hint "This does not affect how BIZAPP POS works."
            rm -f "$BUNDLE/Contents/Resources/$BIZAPP_ICON_NAME"
        fi
    fi

    # 6. Render the templates.
    say "==> Building the $BIZAPP_APP_NAME.app launcher"

    # Escape a value for use as a sed replacement with '|' as the delimiter.
    esc() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }

    e_app=$(esc "$BIZAPP_APP_NAME")
    e_bid=$(esc "$BIZAPP_BUNDLE_ID")
    e_ver=$(esc "$BIZAPP_APP_VERSION")
    e_exec=$(esc "$BIZAPP_EXEC_NAME")
    e_icon=$(esc "$icon_value")
    e_root=$(esc "$REPO_ROOT")
    e_java=$(esc "$JDK_HOME/bin/java")
    e_jar=$(esc "$JAR")
    e_iver=$(esc "$BIZAPP_INSTALLER_VERSION")

    sed -e "s|@@APP_NAME@@|$e_app|g" \
        -e "s|@@BUNDLE_ID@@|$e_bid|g" \
        -e "s|@@VERSION@@|$e_ver|g" \
        -e "s|@@EXEC@@|$e_exec|g" \
        -e "s|@@ICON@@|$e_icon|g" \
        "$REPO_ROOT/install/macos/lib/Info.plist.template" > "$BUNDLE/Contents/Info.plist" || {
        err "could not write the application's Info.plist."
        exit 7
    }

    sed -e "s|@@REPO_ROOT@@|$e_root|g" \
        -e "s|@@JAVA_BIN@@|$e_java|g" \
        -e "s|@@JAR@@|$e_jar|g" \
        -e "s|@@INSTALLER_VERSION@@|$e_iver|g" \
        "$REPO_ROOT/install/macos/lib/launcher-template.sh" > "$BUNDLE/Contents/MacOS/$BIZAPP_EXEC_NAME" || {
        err "could not write the application's launcher script."
        exit 7
    }
    chmod 0755 "$BUNDLE/Contents/MacOS/$BIZAPP_EXEC_NAME" || {
        err "could not make the launcher script executable."
        exit 7
    }

    # PkgInfo is exactly 8 bytes with no trailing newline.
    printf 'APPL????' > "$BUNDLE/Contents/PkgInfo" || {
        err "could not write the application's PkgInfo."
        exit 7
    }

    # 7. Validate before anything is installed.
    if ! plutil -lint "$BUNDLE/Contents/Info.plist" >/dev/null 2>&1; then
        err "the generated Info.plist is not valid."
        hint "This is a bug in the installer. Please report it with this path: $REPO_ROOT"
        exit 7
    fi
    if ! sh -n "$BUNDLE/Contents/MacOS/$BIZAPP_EXEC_NAME" 2>/dev/null; then
        err "the generated launcher script is not valid shell."
        hint "This usually means the folder path contains unusual characters: $REPO_ROOT"
        exit 7
    fi

    # 8. Install idempotently. Never cp -R over a live bundle: stale files
    #    from an older version would survive and be loaded.
    #
    # The mkdir is guarded so that the --system consent text above, which
    # promises an exact list of sudo commands, stays literally true when the
    # destination (normally /Applications) already exists.
    if [ ! -d "$DEST" ]; then
        if ! bizapp_run mkdir -p "$DEST"; then
            err "could not create $DEST"
            if [ "$opt_system" -eq 1 ]; then
                hint "Check that you have administrator rights on this machine."
            else
                hint "Check the folder permissions and try again."
            fi
            exit 7
        fi
    fi
    # Canonicalise so a relative --dest still reports an absolute path, and so
    # the removal below can never resolve to something unexpected.
    DEST=$(cd -P -- "$DEST" && pwd -P) || {
        err "could not resolve the destination folder $DEST"
        hint "Check that it exists and that you can read it."
        exit 7
    }
    APP="$DEST/$BIZAPP_APP_NAME.app"
    say "==> Installing $APP"

    # Stage, then swap. The previous version is removed only after the new one
    # is already sitting in $DEST under a temporary name, so there is no moment
    # in which the till has no application at all. The expensive, failure-prone
    # step (copying across volumes out of TMPDIR) happens first, while the old
    # bundle is still intact; the two renames afterwards are within one
    # directory on one volume.
    APP_NEW="$APP.bizapp-new.$$"
    APP_OLD="$APP.bizapp-old.$$"

    # Arm the orphan BEFORE the mv that creates it, not after: a signal during
    # the mv itself would otherwise leave a .bizapp-new copy that cleanup never
    # removes. A stale value is harmless because cleanup guards on [ -e ].
    ORPHAN="$APP_NEW"
    if ! bizapp_run mv "$BUNDLE" "$APP_NEW"; then
        ORPHAN=""
        err "could not install the application into $DEST"
        if [ "$opt_system" -eq 1 ]; then
            hint "Check that $DEST exists and that you have administrator rights."
        else
            hint "Check that you can write to $DEST and try again."
        fi
        hint "Nothing was removed: any previous version is still in place."
        exit 7
    fi

    had_previous=0
    if [ -e "$APP" ]; then
        if ! bizapp_run mv "$APP" "$APP_OLD"; then
            bizapp_run rm -rf "$APP_NEW" >/dev/null 2>&1
            ORPHAN=""
            err "could not remove the previous version at $APP"
            hint "Quit BIZAPP POS if it is running, then try again."
            hint "The previous version is untouched and still works."
            exit 7
        fi
        had_previous=1
        # From here until the second rename lands, the destination holds no
        # application. These two tell cleanup how to put it back if we are
        # interrupted inside that window.
        APP_OLD_SAVED="$APP_OLD"
        APP_TARGET="$APP"
    fi

    if ! bizapp_run mv "$APP_NEW" "$APP"; then
        err "could not install the application into $DEST"
        if [ "$had_previous" -eq 1 ]; then
            if bizapp_run mv "$APP_OLD" "$APP"; then
                hint "The previous version has been put back and still works."
            else
                hint "The previous version could not be put back automatically."
                hint "It is intact at $APP_OLD - rename it to \"$APP\" in Finder."
            fi
        fi
        bizapp_run rm -rf "$APP_NEW" >/dev/null 2>&1
        ORPHAN=""
        APP_OLD_SAVED=""
        APP_TARGET=""
        exit 7
    fi
    ORPHAN=""
    # The application is back in place under its real name, so there is nothing
    # left for cleanup to restore.
    APP_OLD_SAVED=""
    APP_TARGET=""

    if [ "$had_previous" -eq 1 ]; then
        if ! bizapp_run rm -rf "$APP_OLD"; then
            warn "the previous version could not be deleted; it is left at $APP_OLD"
            hint "BIZAPP POS is installed and working. Drag that folder to the Trash when convenient."
        fi
    fi
    # Let macOS notice the new/changed bundle straight away.
    [ -x /usr/bin/touch ] && touch "$APP" 2>/dev/null
    if [ -x /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister ]; then
        /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP" >/dev/null 2>&1 || true
    fi
fi

# ----------------------------------------------------- 9. look and feel -----
BIZAPP_CONFIG="$HOME/nordpos.properties"
BIZAPP_DATA_DIR="$HOME/.derby-db"

bizapp_saved_laf() {
    [ -f "$BIZAPP_CONFIG" ] || return 1
    sed -n 's/^[[:space:]]*swing\.defaultlaf[[:space:]]*[=:][[:space:]]*//p' "$BIZAPP_CONFIG" | head -n 1
}

if [ "$opt_repair_laf" -eq 1 ]; then
    say "==> Checking the saved look and feel setting"
    if [ ! -f "$BIZAPP_CONFIG" ]; then
        say "==> No settings file at $BIZAPP_CONFIG yet; nothing to repair."
    else
        old_laf=$(bizapp_saved_laf || true)
        case "${old_laf:-}" in
            org.pushingpixels.*)
                stamp=$(date '+%Y%m%d%H%M%S')
                backup="$BIZAPP_CONFIG.bizapp-backup-$stamp"
                n=0
                while [ -e "$backup" ]; do
                    n=$((n + 1))
                    backup="$BIZAPP_CONFIG.bizapp-backup-$stamp-$n"
                done
                if ! cp -p "$BIZAPP_CONFIG" "$backup"; then
                    err "could not back up your settings file, so nothing was changed."
                    hint "Check that $HOME is writable and try again."
                    exit 7
                fi

                # Does the original end with a newline?
                had_nl=1
                [ -n "$(tail -c 1 "$BIZAPP_CONFIG")" ] && had_nl=0

                tmpcfg="$BIZAPP_CONFIG.bizapp-tmp.$$"
                if ! awk '
                    {
                      line = $0; cr = ""
                      if (line ~ /\r$/) { cr = "\r"; sub(/\r$/, "", line) }
                      if (line ~ /^[ \t]*swing\.defaultlaf[ \t]*[=:][ \t]*org\.pushingpixels\./) {
                        printf "%s%s\n", "swing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel", cr
                      } else {
                        printf "%s%s\n", line, cr
                      }
                    }
                ' "$BIZAPP_CONFIG" > "$tmpcfg"; then
                    rm -f "$tmpcfg"
                    err "could not rewrite the settings file, so nothing was changed."
                    hint "Your original file is untouched at $BIZAPP_CONFIG"
                    exit 7
                fi

                if [ "$had_nl" -eq 0 ]; then
                    sz=$(wc -c < "$tmpcfg" | tr -d ' ')
                    if [ "$sz" -gt 0 ]; then
                        dd if="$tmpcfg" of="$tmpcfg.trim" bs=1 count=$((sz - 1)) 2>/dev/null \
                            && mv -f "$tmpcfg.trim" "$tmpcfg"
                    fi
                fi

                chmod "$(stat -f '%OLp' "$BIZAPP_CONFIG")" "$tmpcfg" 2>/dev/null || true
                if ! mv -f "$tmpcfg" "$BIZAPP_CONFIG"; then
                    rm -f "$tmpcfg"
                    err "could not replace the settings file, so nothing was changed."
                    hint "Your original file is untouched at $BIZAPP_CONFIG"
                    exit 7
                fi
                note "Look and feel repaired in $BIZAPP_CONFIG"
                note "  was: swing.defaultlaf=$old_laf"
                note "  now: swing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel"
                note "  backup: $backup"
                laf_repaired=1
                ;;
            "")
                say "==> No look and feel is saved in $BIZAPP_CONFIG; nothing to repair."
                ;;
            *)
                say "==> Saved look and feel is already safe ($old_laf); nothing to repair."
                ;;
        esac
    fi
else
    if [ -f "$BIZAPP_CONFIG" ]; then
        cur_laf=$(bizapp_saved_laf || true)
        case "${cur_laf:-}" in
            org.pushingpixels.*)
                warn "Your saved settings select a Substance look and feel, which crashes on Java 9 and newer."
                hint "BIZAPP POS will not start until this is changed."
                hint "Re-run this installer with --repair-laf to fix just that one setting (a backup is made first)."
                # Remembered so the final summary leads with it, --launch is
                # refused, and the run is not reported as a plain success. The
                # app would exit with status 0 and no window at all.
                laf_blocked=1
                ;;
        esac
    fi
fi

# ---------------------------------------------------------- 10. port 1527 ----
say "==> Checking that the database port (1527) is free"
port_users=""
if command -v lsof >/dev/null 2>&1; then
    port_users=$(lsof -nP -iTCP:1527 -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $1}' | sort -u | tr '\n' ' ' | sed 's/ *$//')
fi
if [ -n "$port_users" ]; then
    warn "TCP port 1527 is in use by: $port_users"
    hint "If that is another copy of BIZAPP POS or an Apache Derby server, this is fine - they share one database."
    hint "If it is anything else, BIZAPP POS will hang at startup with no window. Stop that program first."
    if [ "$opt_yes" -eq 0 ] && [ -t 0 ]; then
        printf 'Continue anyway? [Y/n] ' >&2
        IFS= read -r _pans || _pans=""
        case "$_pans" in
            [nN]|[nN][oO])
                # The build and the install are already complete at this point,
                # so this is an advisory declined, not a consent declined: it
                # must not be reported as a failed install, and the operator
                # still needs the first-run guidance below.
                port_declined=1
                ;;
        esac
    fi
fi

# -------------------------------------------------------------- 11. done ----
note ""
if [ "$laf_blocked" -eq 1 ]; then
    # The blocker leads. Anything less and the operator reads "installed",
    # double-clicks, and gets a Dock bounce, no window and no error.
    note "BIZAPP POS $BIZAPP_APP_VERSION is installed, but WILL NOT START until you run ./install.command --repair-laf"
    note ""
    note "  Your saved settings in $BIZAPP_CONFIG select a Substance look and feel."
    note "  On Java 9 and newer it fails before any window appears: no window, no error message."
    note "  Fix it with:  cd \"$REPO_ROOT\" && ./install.command --repair-laf"
    note "  That changes one line and takes a timestamped backup first. Nothing else is touched."
else
    note "BIZAPP POS $BIZAPP_APP_VERSION is installed."
fi
note ""
if [ -n "$APP" ]; then
    note "  Application : $APP"
fi
note "  Built file  : $JAR"
note "  Source      : $REPO_ROOT"
note "  Logs        : $HOME/Library/Logs/$BIZAPP_APP_NAME/"
note ""
if [ "$port_declined" -eq 1 ]; then
    note "You chose not to continue past the port 1527 check, but the build and the"
    note "install had already finished, so both are complete. Nothing needs re-installing."
    note "Free port 1527 before you start BIZAPP POS, or it will hang with no window."
    note ""
fi
if [ "$laf_repaired" -eq 1 ]; then
    note "Your data lives in your home folder. The only thing this installer changed there"
    note "is the single look-and-feel line in nordpos.properties, after taking the backup"
    note "named above:"
else
    note "Your data lives in your home folder. Nothing here is touched by this installer,"
    note "except --repair-laf, which changes one line of nordpos.properties after taking a backup:"
fi
if [ -f "$BIZAPP_CONFIG" ]; then
    note "  $HOME/nordpos.properties   your settings (this file already exists - back it up along with .derby-db)"
else
    note "  $HOME/nordpos.properties   your settings (created the first time you use Configuration -> Save)"
fi
note "  $HOME/.derby-db            your point-of-sale database - products, prices, customers and every sale"
note ""
note "The first time you start BIZAPP POS:"
if [ -e "$BIZAPP_DATA_DIR" ]; then
    note "  1. You ALREADY have a database at $BIZAPP_DATA_DIR."
    note "     Copy that whole folder somewhere safe now, before you start the application."
    note "     If BIZAPP POS offers to UPGRADE the database, that dialog says DATA MAY BE"
    note "     LOST for a reason: answering Yes deletes every parked (suspended) ticket and"
    note "     cannot be undone or re-run. Only answer Yes once you have that copy."
else
    note "  1. A dialog asks whether to create the database. Click Yes. It takes about a second."
fi
note "  2. The login screen shows four buttons. Click Administrator - there is no password."
note "  3. Go straight to Maintenance -> Users and set passwords. All four accounts"
note "     (Administrator, Manager, Employee, Guest) ship with no password at all."
if [ -e "$BIZAPP_DATA_DIR" ]; then
    note "  4. Your existing products, prices, customers and sales are all still there."
else
    note "  4. The catalogue starts empty. Add products before you try to sell anything."
fi
note ""
note "Important: the application points at this folder rather than copying it."
note "If you move, rename or delete $REPO_ROOT, BIZAPP POS will stop working."
note "Move it first, then run ./install.command again."
note ""
note "Full instructions, troubleshooting and uninstall: $REPO_ROOT/INSTALL.md"

if [ "$opt_launch" -eq 1 ]; then
    if [ "$laf_blocked" -eq 1 ]; then
        warn "--launch was refused: BIZAPP POS would exit immediately, with no window and no error."
        hint "Your saved look and feel crashes on Java 9 and newer, as reported above."
        hint "Run ./install.command --repair-laf first, then start BIZAPP POS."
    elif [ "$port_declined" -eq 1 ]; then
        warn "--launch was refused because you chose not to continue past the port 1527 check."
        hint "Free port 1527, then start BIZAPP POS yourself."
    elif [ -n "$APP" ]; then
        say "==> Starting BIZAPP POS"
        if ! open -a "$APP"; then
            err "could not start BIZAPP POS."
            hint "Open it from $DEST in Finder, or check $HOME/Library/Logs/$BIZAPP_APP_NAME/bizapp-pos.log"
            exit 7
        fi
    else
        warn "--launch was ignored because --no-shortcut means there is no application to launch."
    fi
fi

exit 0
