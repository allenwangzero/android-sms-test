#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
fi
if ! .venv/bin/python -c 'import qrcode' >/dev/null 2>&1; then
    .venv/bin/python -m pip install -r desktop/requirements.txt
fi
exec .venv/bin/python desktop/server.py "$@"
