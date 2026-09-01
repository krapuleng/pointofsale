#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# Generate a macOS .icns from the repository's brand artwork using only tools
# that ship with macOS (sips + iconutil). There is no .icns, .ico or .svg
# anywhere in this repository, so generation is mandatory.
#
# NOTE ON QUALITY: the default source is 600x300, so the square master is only
# 300px. The 512x512 and 1024x1024 (512@2x) entries are therefore upscaled and
# will look soft in Finder's largest icon view and in Quick Look. Dropping a
# 1024x1024 master into src-pos/com/openbravo/pos/templates/Window.DescLogo.png
# fixes that with no change to this script.
#
# Usage: make-icns.sh <output.icns> [source.png]
# Exit:  0 ok | 2 usage | 7 conversion failed | 10 sips/iconutil unavailable

set -u

BIZAPP_INSTALLER_VERSION="1.0.0"

SELF=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(cd -P -- "$SELF/../../.." && pwd -P)

say()  { printf '%s\n' "$1" >&2; }
warn() { printf '[warn] %s\n' "$1" >&2; }
err()  { printf '[error] %s\n' "$1" >&2; }
hint() { printf '        %s\n' "$1" >&2; }

WORKDIR=""
cleanup() { [ -n "$WORKDIR" ] && [ -d "$WORKDIR" ] && rm -rf "$WORKDIR"; return 0; }
trap cleanup EXIT HUP INT TERM

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    err "usage: make-icns.sh <output.icns> [source.png]"
    hint "Example: sh \"$SELF/make-icns.sh\" /tmp/bizapp.icns"
    exit 2
fi

OUT=$1
SRC=${2:-$REPO_ROOT/src-pos/com/openbravo/pos/templates/Window.DescLogo.png}

if [ ! -x /usr/bin/sips ] || [ ! -x /usr/bin/iconutil ]; then
    err "the macOS image tools sips and iconutil were not found."
    hint "Both ship with macOS at /usr/bin/sips and /usr/bin/iconutil."
    hint "BIZAPP POS will still install; it just will not have a custom icon."
    exit 10
fi

if [ ! -f "$SRC" ]; then
    err "the icon source image was not found: $SRC"
    hint "Pass a different PNG: sh \"$SELF/make-icns.sh\" \"$OUT\" /path/to/logo.png"
    exit 2
fi

OUTDIR=$(dirname -- "$OUT")
if [ ! -d "$OUTDIR" ]; then
    err "the output directory does not exist: $OUTDIR"
    hint "Create it first: mkdir -p \"$OUTDIR\""
    exit 2
fi

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/bizapp-icns.$$.XXXXXX") || {
    err "could not create a temporary working directory."
    hint "Check that ${TMPDIR:-/tmp} exists and is writable."
    exit 7
}

say "==> Generating application icon from $(basename -- "$SRC")"

# 1. Read the source dimensions.
DIMS=$(/usr/bin/sips -g pixelWidth -g pixelHeight "$SRC" 2>/dev/null) || {
    err "sips could not read the image $SRC"
    hint "Make sure it is a valid PNG."
    exit 7
}
W=$(printf '%s\n' "$DIMS" | sed -n 's/.*pixelWidth: *\([0-9][0-9]*\).*/\1/p'  | head -n 1)
H=$(printf '%s\n' "$DIMS" | sed -n 's/.*pixelHeight: *\([0-9][0-9]*\).*/\1/p' | head -n 1)

if [ -z "$W" ] || [ -z "$H" ] || [ "$W" -le 0 ] 2>/dev/null || [ "$H" -le 0 ] 2>/dev/null; then
    err "could not determine the pixel dimensions of $SRC"
    hint "sips reported: $DIMS"
    exit 7
fi

# 2. Centre-crop to a square.
if [ "$W" -lt "$H" ]; then S=$W; else S=$H; fi
/usr/bin/sips -c "$S" "$S" "$SRC" --out "$WORKDIR/sq.png" >/dev/null 2>&1 || {
    err "sips failed to crop $SRC to ${S}x${S}."
    hint "Try passing a square PNG explicitly as the second argument."
    exit 7
}

# 3. Pad so the artwork does not touch the edges of the Dock tile.
#    Apple's own icons occupy roughly 78% of the tile.
P=$(( S * 100 / 78 ))
# --padColor writes a "<CGColor ...>" line to stderr even on success, so this
# one call has its stderr discarded rather than surfaced as a failure.
/usr/bin/sips -p "$P" "$P" --padColor FFFFFF "$WORKDIR/sq.png" --out "$WORKDIR/pad.png" >/dev/null 2>&1 || {
    err "sips failed to pad the icon to ${P}x${P}."
    hint "This is unusual; re-run with a different source image."
    exit 7
}

# 4. Build the .iconset. These ten names are exactly what iconutil expects.
ICONSET="$WORKDIR/bizapp.iconset"
mkdir -p "$ICONSET" || { err "could not create $ICONSET"; exit 7; }

bizapp_scale() {
    /usr/bin/sips -z "$1" "$1" "$WORKDIR/pad.png" --out "$ICONSET/$2.png" >/dev/null 2>&1 || {
        err "sips failed to produce the ${1}x${1} icon ($2.png)."
        hint "Check that ${TMPDIR:-/tmp} has free space."
        exit 7
    }
}

bizapp_scale 16   icon_16x16
bizapp_scale 32   icon_16x16@2x
bizapp_scale 32   icon_32x32
bizapp_scale 64   icon_32x32@2x
bizapp_scale 128  icon_128x128
bizapp_scale 256  icon_128x128@2x
bizapp_scale 256  icon_256x256
bizapp_scale 512  icon_256x256@2x
bizapp_scale 512  icon_512x512
bizapp_scale 1024 icon_512x512@2x

# 5. Pack the iconset.
if ! /usr/bin/iconutil -c icns "$ICONSET" -o "$OUT" >/dev/null 2>&1; then
    err "iconutil could not build the icon file $OUT"
    hint "Check that $OUTDIR is writable."
    exit 7
fi

# 6. Verify.
if [ ! -f "$OUT" ] || [ ! -s "$OUT" ]; then
    err "the icon file $OUT was not created, or is empty."
    hint "Re-run with a different source image, or install without an icon."
    exit 7
fi

say "==> Icon written to $OUT"
exit 0
