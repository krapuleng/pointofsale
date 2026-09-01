#!/bin/sh
# BIZAPP POS - installer / build tooling
# Copyright (C) 2026
# This file is part of BIZAPP POS, a fork of NORD POS / Openbravo POS.
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.  See <https://www.gnu.org/licenses/>.
#
# Shared POSIX JDK/JRE discovery.
#
#   Sourced:     . install/lib/jdk-find.sh
#                defines bizapp_jdk_major and bizapp_find_jdk, and does nothing else.
#   Standalone:  sh install/lib/jdk-find.sh [--min N] [--need-javac] [--need-jpackage] [--list]
#                prints the chosen JAVA_HOME on stdout (exit 0) or an error on stderr (exit 3).
#
# Why this exists instead of just calling /usr/libexec/java_home:
#   On this very machine /usr/libexec/java_home exits 1 with "Unable to locate a Java
#   Runtime" while a perfectly good JDK 11 sits on PATH, because Homebrew JDKs are
#   keg-only and are never registered under /Library/Java/JavaVirtualMachines.
#   Also, /usr/bin/java is an Apple stub that merely honours JAVA_HOME, so a working
#   "java -version" proves nothing about javac - always gate on the real bin/javac file.

# ---------------------------------------------------------------- helpers ---

# bizapp_jdk_resolve_link <path> -> echo the path with every symlink hop followed.
bizapp_jdk_resolve_link() {
    bjrl_p=${1:-}
    bjrl_n=0
    while [ -L "$bjrl_p" ] && [ "$bjrl_n" -lt 40 ]; do
        bjrl_t=$(readlink "$bjrl_p" 2>/dev/null) || break
        [ -n "$bjrl_t" ] || break
        case $bjrl_t in
            /*) bjrl_p=$bjrl_t ;;
            *)  bjrl_p=$(dirname -- "$bjrl_p")/$bjrl_t ;;
        esac
        bjrl_n=$((bjrl_n + 1))
    done
    printf '%s\n' "$bjrl_p"
}

# bizapp_jdk_canon <dir> -> echo the fully resolved directory, or nothing (return 1).
bizapp_jdk_canon() {
    bjc_d=${1:-}
    [ -n "$bjc_d" ] || return 1
    ( cd -P -- "$bjc_d" 2>/dev/null && pwd -P ) 2>/dev/null
}

# ------------------------------------------------------- public functions ---

# bizapp_jdk_major <java_home>
#   Echoes the major version integer (1.8.0_412 -> 8, 11.0.28 -> 11, 24.0.2 -> 24).
#   Returns 1 when the version cannot be determined.
bizapp_jdk_major() {
    bjm_home=${1:-}
    [ -n "$bjm_home" ] || return 1
    [ -x "$bjm_home/bin/java" ] || return 1
    bjm_out=$("$bjm_home/bin/java" -version 2>&1) || return 1
    [ -n "$bjm_out" ] || return 1
    # The documented form is the first line; fall back to any line carrying a
    # quoted version, so a stray "Picked up JAVA_TOOL_OPTIONS" banner is harmless.
    bjm_ver=$(printf '%s\n' "$bjm_out" | sed -n '1s/.*"\([0-9][^"]*\)".*/\1/p')
    if [ -z "$bjm_ver" ]; then
        bjm_ver=$(printf '%s\n' "$bjm_out" | sed -n 's/.*version "\([0-9][^"]*\)".*/\1/p' | sed -n '1p')
    fi
    [ -n "$bjm_ver" ] || return 1
    case $bjm_ver in
        1.*) bjm_maj=$(printf '%s\n' "$bjm_ver" | cut -d. -f2) ;;
        *)   bjm_maj=$(printf '%s\n' "$bjm_ver" | cut -d. -f1) ;;
    esac
    case ${bjm_maj:-} in
        ''|*[!0-9]*) return 1 ;;
    esac
    printf '%s\n' "$bjm_maj"
}

# bizapp_jdk_candidates -> echo one candidate JAVA_HOME per line, in priority order,
# as "<canonical path><TAB><path to report>". Internal.
bizapp_jdk_candidates() {
    bjcand_out=""

    bizapp_jdk_add() {
        bjadd_d=${1:-}
        [ -n "$bjadd_d" ] || return 0
        [ -d "$bjadd_d" ] || return 0
        bjadd_c=$(bizapp_jdk_canon "$bjadd_d") || return 0
        [ -n "$bjadd_c" ] || return 0
        bjcand_out="${bjcand_out}${bjadd_c}	${bjadd_d}
"
    }

    # 1. explicit override
    bizapp_jdk_add "${BIZAPP_JDK_HOME:-}"

    # 2. JAVA_HOME, and its parent (covers JAVA_HOME pointing at a JRE inside a JDK)
    if [ -n "${JAVA_HOME:-}" ]; then
        bizapp_jdk_add "$JAVA_HOME"
        bjcand_p=$(bizapp_jdk_canon "$JAVA_HOME/.." 2>/dev/null) || bjcand_p=""
        bizapp_jdk_add "$bjcand_p"
    fi

    # 3. the Apple locator (often fails outright on a Homebrew-only machine)
    if [ -x /usr/libexec/java_home ]; then
        bjcand_jh=$(/usr/libexec/java_home 2>/dev/null) || bjcand_jh=""
        bizapp_jdk_add "$bjcand_jh"
        /usr/libexec/java_home -V 2>&1 | sed -n 's|.*[ 	]\(/.*/Contents/Home\)[ 	]*$|\1|p' | while IFS= read -r bjcand_l; do
            printf '%s\n' "$bjcand_l"
        done > "${TMPDIR:-/tmp}/bizapp-jdk-$$.list" 2>/dev/null || :
        if [ -f "${TMPDIR:-/tmp}/bizapp-jdk-$$.list" ]; then
            while IFS= read -r bjcand_l; do
                bizapp_jdk_add "$bjcand_l"
            done < "${TMPDIR:-/tmp}/bizapp-jdk-$$.list"
            rm -f "${TMPDIR:-/tmp}/bizapp-jdk-$$.list"
        fi
    fi

    # 4. the standard macOS locations
    for bjcand_d in /Library/Java/JavaVirtualMachines/*/Contents/Home \
                    "${HOME:-/nonexistent}"/Library/Java/JavaVirtualMachines/*/Contents/Home; do
        bizapp_jdk_add "$bjcand_d"
    done

    # 5. Homebrew (keg-only, therefore invisible to java_home)
    for bjcand_p in /opt/homebrew /usr/local "${HOMEBREW_PREFIX:-}"; do
        [ -n "$bjcand_p" ] || continue
        for bjcand_d in "$bjcand_p"/opt/openjdk*/libexec/openjdk.jdk/Contents/Home; do
            bizapp_jdk_add "$bjcand_d"
        done
    done

    # 6. SDKMAN
    bizapp_jdk_add "${HOME:-/nonexistent}/.sdkman/candidates/java/current"
    for bjcand_d in "${HOME:-/nonexistent}"/.sdkman/candidates/java/*; do
        bizapp_jdk_add "$bjcand_d"
    done

    # 7. whatever "java" on PATH really points at
    bjcand_j=$(command -v java 2>/dev/null) || bjcand_j=""
    if [ -n "$bjcand_j" ]; then
        bjcand_j=$(bizapp_jdk_resolve_link "$bjcand_j")
        bjcand_d=$(dirname -- "$bjcand_j")
        bjcand_d=$(dirname -- "$bjcand_d")
        bizapp_jdk_add "$bjcand_d"
    fi

    printf '%s' "$bjcand_out" | awk 'NF && !seen[$1]++'
}

# bizapp_find_jdk [--min N] [--need-javac] [--need-jpackage] [--list]
#   Echoes the single best JAVA_HOME on stdout and returns 0,
#   or echoes nothing and returns 1.
bizapp_find_jdk() {
    bfj_min=0
    bfj_need_javac=0
    bfj_need_jpackage=0
    bfj_list=0
    while [ $# -gt 0 ]; do
        case $1 in
            --min)            bfj_min=${2:-0}; shift 2 ;;
            --min=*)          bfj_min=${1#--min=}; shift ;;
            --need-javac)     bfj_need_javac=1; shift ;;
            --need-jpackage)  bfj_need_jpackage=1; shift ;;
            --list)           bfj_list=1; shift ;;
            *)                shift ;;
        esac
    done
    case ${bfj_min:-} in
        ''|*[!0-9]*) bfj_min=0 ;;
    esac

    bfj_best=""
    bfj_best_major=-1

    bfj_cands=$(bizapp_jdk_candidates)
    [ -n "$bfj_cands" ] || return 1

    printf '%s\n' "$bfj_cands" | while IFS= read -r bfj_line; do
        [ -n "$bfj_line" ] || continue
        printf '%s\n' "${bfj_line#*	}"
    done > "${TMPDIR:-/tmp}/bizapp-jdkcand-$$.txt"

    while IFS= read -r bfj_home; do
        [ -n "$bfj_home" ] || continue
        [ -x "$bfj_home/bin/java" ] || continue
        if [ -f "$bfj_home/bin/javac" ] && [ -x "$bfj_home/bin/javac" ]; then
            bfj_has_javac=yes
        else
            bfj_has_javac=no
        fi
        if [ -f "$bfj_home/bin/jpackage" ] && [ -x "$bfj_home/bin/jpackage" ]; then
            bfj_has_jpackage=yes
        else
            bfj_has_jpackage=no
        fi
        bfj_major=$(bizapp_jdk_major "$bfj_home") || bfj_major=""
        if [ "$bfj_list" -eq 1 ]; then
            printf 'major=%s javac=%s jpackage=%s %s\n' \
                "${bfj_major:-?}" "$bfj_has_javac" "$bfj_has_jpackage" "$bfj_home" >&2
        fi
        [ -n "$bfj_major" ] || continue
        [ "$bfj_need_javac" -eq 0 ] || [ "$bfj_has_javac" = yes ] || continue
        [ "$bfj_need_jpackage" -eq 0 ] || [ "$bfj_has_jpackage" = yes ] || continue
        [ "$bfj_major" -ge "$bfj_min" ] || continue
        # highest major wins; on a tie the earlier candidate wins
        if [ "$bfj_major" -gt "$bfj_best_major" ]; then
            bfj_best_major=$bfj_major
            bfj_best=$bfj_home
        fi
    done < "${TMPDIR:-/tmp}/bizapp-jdkcand-$$.txt"
    rm -f "${TMPDIR:-/tmp}/bizapp-jdkcand-$$.txt"

    [ -n "$bfj_best" ] || return 1
    printf '%s\n' "$bfj_best"
    return 0
}

# ------------------------------------------------------- standalone mode ---

bizapp_jdk_find_main() {
    bjfm_list=0
    for bjfm_a in "$@"; do
        case $bjfm_a in
            --list) bjfm_list=1 ;;
            --help|-h)
                printf '%s\n' "usage: jdk-find.sh [--min N] [--need-javac] [--need-jpackage] [--list]" >&2
                printf '%s\n' "       prints the best JAVA_HOME on stdout, or exits 3." >&2
                return 0
                ;;
        esac
    done
    if bjfm_home=$(bizapp_find_jdk "$@"); then
        printf '%s\n' "$bjfm_home"
        return 0
    fi
    printf '%s\n' "[error] No Java Development Kit (JDK) was found." >&2
    printf '%s\n' "        BIZAPP POS needs a JDK (which includes the compiler), not just a Java runtime." >&2
    printf '%s\n' "        If 'java -version' works but this still fails, you have a runtime only." >&2
    printf '%s\n' "        Install one:  brew install --cask temurin@21      (macOS)" >&2
    printf '%s\n' "                      winget install --id EclipseAdoptium.Temurin.17.JDK -e   (Windows)" >&2
    printf '%s\n' "                      or download from https://adoptium.net" >&2
    printf '%s\n' "        Already installed? Point JAVA_HOME at the folder that contains bin/javac and try again." >&2
    return 3
}

case ${0##*/} in
    jdk-find.sh)
        bizapp_jdk_find_main "$@"
        exit $?
        ;;
esac
