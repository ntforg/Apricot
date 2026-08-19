#!/bin/bash
# ============================================================
#  Thunder Launcher (Linux/macOS)
#  Downloads the Thunder client from GitHub, keeps it up to
#  date, and launches it. Put this file in its own folder;
#  the client is installed into a "Thunder" subfolder.
#
#  Note: the Linux package bundles a Linux x64 Java runtime.
#  On macOS the bundled runtime is ignored and the client
#  falls back to your system Java (17-25).
# ============================================================
set -u
REPO=ntforg/Thunder
INSTALLDIR="$(cd "$(dirname "$0")" && pwd)/Thunder"

echo "Thunder Launcher"
echo "================"

latest=$(curl -sSf "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
             | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
current=""
[ -f "$INSTALLDIR/launcher-version.txt" ] && current=$(cat "$INSTALLDIR/launcher-version.txt")

if [ -z "$latest" ]; then
    if [ -f "$INSTALLDIR/Play_Linux.sh" ]; then
        echo "Could not reach GitHub; launching the installed client ($current)."
    else
        echo "Could not reach GitHub, and no client is installed yet." >&2
        echo "Check your internet connection and try again." >&2
        exit 1
    fi
elif [ "$current" = "$latest" ]; then
    echo "Client is up to date ($current)."
else
    echo "Downloading Thunder $latest..."
    tmp=$(mktemp /tmp/thunder-update-XXXXXX.tar.gz)
    if curl -fL -o "$tmp" "https://github.com/$REPO/releases/download/$latest/Thunder-$latest-linux-x64.tar.gz"; then
        echo "Extracting..."
        mkdir -p "$INSTALLDIR"
        if tar -xzf "$tmp" -C "$INSTALLDIR"; then
            printf '%s\n' "$latest" > "$INSTALLDIR/launcher-version.txt"
            echo "Updated to $latest."
        else
            echo "Extraction failed." >&2
            rm -f "$tmp"
            exit 1
        fi
    else
        echo "Download failed." >&2
        if [ -f "$INSTALLDIR/Play_Linux.sh" ]; then
            echo "Launching the installed client ($current) instead."
        else
            rm -f "$tmp"
            exit 1
        fi
    fi
    rm -f "$tmp"
fi

cd "$INSTALLDIR"
exec bash Play_Linux.sh "$@"
