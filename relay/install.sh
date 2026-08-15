#!/usr/bin/env bash
#
# Installs the usage reporter as a systemd user timer.
#
# A timer rather than a daemon: the work is reading a few files and posting the
# result, and a process that sleeps for five minutes at a time is a process
# that can be sitting there wedged. If a run fails, the next one is five
# minutes away and nothing has to be restarted.
#
# Credentials are passed in the environment rather than written into the unit,
# so they live in one file with one owner.
#
#   USAGE_PUSH_URL=... USAGE_PUSH_TOKEN=... \
#   USAGE_PUSH_ACCESS_ID=... USAGE_PUSH_ACCESS_SECRET=... ./install.sh
set -euo pipefail

: "${USAGE_PUSH_URL:?set USAGE_PUSH_URL}"
: "${USAGE_PUSH_TOKEN:?set USAGE_PUSH_TOKEN}"

BIN_DIR="$HOME/bin"
UNIT_DIR="$HOME/.config/systemd/user"
ENV_FILE="$HOME/.config/usage-relay.env"

mkdir -p "$BIN_DIR" "$UNIT_DIR" "$(dirname "$ENV_FILE")"

# Written by this script and read only by systemd; nothing else needs it.
umask 077
cat > "$ENV_FILE" <<EOF
USAGE_PUSH_URL=$USAGE_PUSH_URL
USAGE_PUSH_TOKEN=$USAGE_PUSH_TOKEN
USAGE_PUSH_ACCESS_ID=${USAGE_PUSH_ACCESS_ID:-}
USAGE_PUSH_ACCESS_SECRET=${USAGE_PUSH_ACCESS_SECRET:-}
EOF
# umask only applies to a file being created. Re-running over one that already
# exists truncates it and keeps whatever mode it had, so a file that was once
# world-readable stays world-readable with fresh secrets in it.
chmod 600 "$ENV_FILE"
umask 022

cat > "$UNIT_DIR/usage-relay.service" <<EOF
[Unit]
Description=Report Codex and Claude Code usage to the lifelog Worker
After=network-online.target

[Service]
Type=oneshot
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/python3 $BIN_DIR/usage_relay.py
EOF

cat > "$UNIT_DIR/usage-relay.timer" <<EOF
[Unit]
Description=Report usage every five minutes

[Timer]
# Soon after boot, then on a fixed cadence. Persistent so a machine that was
# asleep reports once when it wakes rather than waiting for the next slot.
OnBootSec=2min
OnUnitActiveSec=5min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now usage-relay.timer

# Survives logout, which a user timer otherwise does not.
loginctl enable-linger "$USER" 2>/dev/null || true

systemctl --user start usage-relay.service
systemctl --user list-timers usage-relay.timer --no-pager
