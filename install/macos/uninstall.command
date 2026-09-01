#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# Remove the macOS BIZAPP POS launcher, logs and build output.
#
# ABSOLUTE PROHIBITION: never use a wildcard anywhere near "dist". The commands
# `rm -rf dist*` and `rm -rf "$REPO_ROOT"/dist*` would destroy the committed
# repository directory `dist - bizpapp/` and the committed archive dist.rar.
# Only the exact quoted literals "$REPO_ROOT/build" and "$REPO_ROOT/dist" are
# ever removed.
#
# By default this keeps ~/nordpos.properties and ~/.derby-db, which are the
# operator's settings and live point-of-sale database.

set -u

BIZAPP_INSTALLER_VERSION="1.0.0"
BIZAPP_APP_VERSION="4.0"
BIZAPP_APP_NAME="BIZAPP POS"
BIZAPP_BUNDLE_ID="com.nordpos.bizapp.pos"

opt_quiet=0
say()  { [ "$opt_quiet" -eq 1 ] || printf '%s\n' "$1" >&2; }
warn() { printf '[warn] %s\n' "$1" >&2; }
err()  { printf '[error] %s\n' "$1" >&2; }
hint() { printf '        %s\n' "$1" >&2; }
note() { printf '%s\n' "$1" >&2; }

usage() {
    cat >&2 <<'USAGE'
BIZAPP POS - macOS uninstaller

Usage: ./install/macos/uninstall.command [options]

Options:
  --purge-data     Also delete your settings and your point-of-sale database.
                   You must type DELETE MY DATA to confirm. --yes cannot skip it.
  --keep-build     Keep <checkout>/build and <checkout>/dist.
  --dest <dir>     Also look for the application bundle in <dir>
                   (use this if you installed with install.command --dest <dir>).
  --yes            Skip the "Continue?" question. Never skips DELETE MY DATA.
  --version        Print the version and exit.
  --help           Show this help and exit.

Exit codes: 0 ok | 1 BIZAPP POS is still running | 2 usage |
            5 bad checkout | 9 cancelled or not interactive
USAGE
}

opt_purge=0
opt_keep_build=0
opt_yes=0
opt_dest="${BIZAPP_APP_DEST:-}"

need_value() {
    if [ "$2" -lt 2 ]; then
        err "option $1 needs a value."
        hint "Example: $1 /path/to/directory"
        exit 2
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --purge-data)  opt_purge=1 ;;
        --remove-data) opt_purge=1 ;;
        --keep-build)  opt_keep_build=1 ;;
        --yes|-y)      opt_yes=1 ;;
        --quiet)       opt_quiet=1 ;;
        --dest)        need_value "$1" $#; opt_dest=$2; shift ;;
        --dest=*)      opt_dest=${1#--dest=} ;;
        --version)     printf '%s\n' "$BIZAPP_INSTALLER_VERSION"; exit 0 ;;
        --help|-h)     usage; exit 0 ;;
        *)
            err "unknown option: $1"
            usage
            exit 2
            ;;
    esac
    shift
done

say "==> BIZAPP POS setup $BIZAPP_INSTALLER_VERSION  ($BIZAPP_APP_NAME $BIZAPP_APP_VERSION)"

SELF=$(cd -P -- "$(dirname -- "$0")" && pwd -P) || {
    err "could not determine where this script lives."
    exit 10
}
REPO_ROOT=$(cd -P -- "$SELF/../.." && pwd -P) || {
    err "could not locate the BIZAPP POS checkout above $SELF."
    exit 5
}

USER_APP="$HOME/Applications/$BIZAPP_APP_NAME.app"
SYS_APP="/Applications/$BIZAPP_APP_NAME.app"
DEST_APP=""
[ -n "$opt_dest" ] && DEST_APP="$opt_dest/$BIZAPP_APP_NAME.app"
CLI_LINK="$HOME/.local/bin/bizapp-pos"
LOGDIR="$HOME/Library/Logs/$BIZAPP_APP_NAME"
SAVED_STATE="$HOME/Library/Saved Application State/$BIZAPP_BUNDLE_ID.savedState"
BUILD_DIR="$REPO_ROOT/build"
DIST_DIR="$REPO_ROOT/dist"
CONFIG="$HOME/nordpos.properties"
DATA_DIR="$HOME/.derby-db"

# --------------------------------------------------------------- manifest ----
present() { if [ -e "$1" ]; then printf '%s\n' "  will remove : $1" >&2; else printf '%s\n' "  not present : $1" >&2; fi; }

note ""
note "This will remove:"
present "$USER_APP"
present "$SYS_APP"
[ -n "$DEST_APP" ] && present "$DEST_APP"
present "$CLI_LINK"
present "$LOGDIR"
present "$SAVED_STATE"
note "  will remove : the saved window preferences for $BIZAPP_BUNDLE_ID"
if [ "$opt_keep_build" -eq 1 ]; then
    note "  will keep   : $BUILD_DIR  (--keep-build)"
    note "  will keep   : $DIST_DIR  (--keep-build)"
else
    present "$BUILD_DIR"
    present "$DIST_DIR"
fi
note ""
if [ "$opt_purge" -eq 1 ]; then
    note "You asked for --purge-data, so this will ALSO delete, after you type a confirmation:"
    note "  $CONFIG   your settings"
    note "  $DATA_DIR            your point-of-sale database - products, prices,"
    note "                                       customers and every sale ever recorded"
else
    note "This will KEEP your data:"
    note "  $CONFIG   your settings"
    note "  $DATA_DIR            your point-of-sale database - products, prices,"
    note "                                       customers and every sale ever recorded"
fi
note ""
note "The source folder $REPO_ROOT is never removed."
note ""

if [ "$opt_yes" -eq 0 ]; then
    if [ ! -t 0 ]; then
        err "this uninstaller needs your confirmation, but this is not an interactive session."
        hint "Re-run it in a Terminal window, or add --yes."
        exit 9
    fi
    printf 'Continue? [y/N] ' >&2
    IFS= read -r _ans || _ans=""
    case "$_ans" in
        [yY]|[yY][eE][sS]) ;;
        *) note "Cancelled. Nothing was changed."; exit 0 ;;
    esac
fi

# ----------------------------------------------------- running-app guard ----
say "==> Checking that BIZAPP POS is not running"
RUNNING=""
if command -v pgrep >/dev/null 2>&1; then
    RUNNING=$(pgrep -f 'nordpos\.jar' 2>/dev/null | tr '\n' ' ' | sed 's/ *$//')
fi
if [ -z "$RUNNING" ]; then
    RUNNING=$(ps -Ao pid=,command= 2>/dev/null \
        | grep 'nordpos\.jar' \
        | grep -v 'uninstall\.command' \
        | grep -v '[g]rep' \
        | awk '{print $1}' | tr '\n' ' ' | sed 's/ *$//')
fi
if [ -n "$RUNNING" ]; then
    err "BIZAPP POS is still running (PID $RUNNING). Quit it and try again."
    hint "The database is locked while the application is open, and removing files now"
    hint "could corrupt it. Quit BIZAPP POS, then run this uninstaller again."
    exit 1
fi

# ----------------------------------------------------------------- remove ----
removed=0
drop() {
    # Remove one exact path. Never a wildcard - see the header.
    if [ -e "$1" ]; then
        if rm -rf "$1"; then
            note "  removed : $1"
            removed=$((removed + 1))
        else
            warn "could not remove $1"
            hint "Check the folder permissions, or remove it yourself in Finder."
        fi
    fi
}

say "==> Removing the BIZAPP POS launcher and logs"
drop "$USER_APP"
[ -n "$DEST_APP" ] && drop "$DEST_APP"

if [ -e "$SYS_APP" ]; then
    if [ -w "/Applications" ] && [ -w "$SYS_APP" ]; then
        drop "$SYS_APP"
    else
        warn "$SYS_APP needs administrator rights to remove, so it was left alone."
        hint "Remove it yourself with:"
        hint "  sudo rm -rf \"$SYS_APP\""
    fi
fi

drop "$CLI_LINK"
drop "$LOGDIR"
drop "$SAVED_STATE"
defaults delete "$BIZAPP_BUNDLE_ID" >/dev/null 2>&1 || true

if [ "$opt_keep_build" -eq 0 ]; then
    say "==> Removing the build output"
    # Exact quoted literals only. A glob here would destroy `dist - bizpapp/`.
    drop "$BUILD_DIR"
    drop "$DIST_DIR"
fi

# ------------------------------------------------------------- user data ----
if [ "$opt_purge" -eq 1 ]; then
    note ""
    note "You are about to permanently delete your BIZAPP POS data:"
    note "  $CONFIG"
    note "  $DATA_DIR"
    note "That includes every product, price, customer and sale ever recorded."
    note "There is no undo. Make a copy of $DATA_DIR first if you are not sure."
    note ""
    printf 'Type exactly DELETE MY DATA to confirm: ' >&2
    IFS= read -r _typed || _typed=""
    if [ "$_typed" = "DELETE MY DATA" ]; then
        drop "$CONFIG"
        drop "$DATA_DIR"
        note ""
        note "Your BIZAPP POS data has been deleted."
    else
        note "Aborted. Your data was kept."
        note ""
        note "Everything else listed above was removed."
        # 9 is the declined-confirmation code (see INSTALL.md). A support
        # script has to be able to tell "data deleted" from "not confirmed".
        exit 9
    fi
else
    note ""
    note "Your data was kept:"
    note "  $CONFIG   your settings"
    note "  $DATA_DIR            your point-of-sale database"
    note "Re-running the installer picks these up again exactly as they are."
fi

note ""
note "Done. $removed item(s) removed."
note "The source folder was not removed. Delete it yourself if you no longer want it:"
note "  rm -rf \"$REPO_ROOT\""
exit 0
