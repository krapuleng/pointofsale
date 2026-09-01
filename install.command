#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# Double-clickable shim: install BIZAPP POS on macOS.
# All of the real work lives in install/macos/install.command.

set -u

# A Finder double-click starts this with the current directory set to your home
# folder, so the script's own location is the only reliable anchor.
SELF=$(cd -P -- "$(dirname -- "$0")" && pwd -P) || {
    printf '[error] could not determine where install.command lives.\n' >&2
    exit 10
}

TARGET="$SELF/install/macos/install.command"

if [ ! -f "$TARGET" ]; then
    printf '[error] install/macos/install.command is missing.\n' >&2
    printf '        This does not look like a complete BIZAPP POS checkout.\n' >&2
    printf '        Clone it again: git clone https://github.com/krapuleng/pointofsale.git\n' >&2
    exit 5
fi

# A GitHub ZIP download loses every executable bit (git clone does not). That is
# not a reason to refuse: the real installer is a /bin/sh script, and everything
# it calls in turn is already invoked as "sh <script>", so running it the same
# way here works exactly as well as exec'ing it.
if [ -x "$TARGET" ]; then
    exec "$TARGET" "$@"
fi

printf '[warn] install/macos/install.command has lost its executable bit (a GitHub ZIP download does that); running it with sh.\n' >&2
exec sh "$TARGET" "$@"
