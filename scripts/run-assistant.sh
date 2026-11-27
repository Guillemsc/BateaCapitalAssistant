#!/usr/bin/env bash
set -euo pipefail

# Both services use private loopback ports. Share fresh credentials for this run.
random_token() {
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
}
export OPENCODE_SERVER_PASSWORD
OPENCODE_SERVER_PASSWORD=${OPENCODE_SERVER_PASSWORD:-$(random_token)}
export BATEA_MCP_TOKEN
BATEA_MCP_TOKEN=${BATEA_MCP_TOKEN:-$(random_token)}

if ! su-exec assistant-ai sh -c 'test -w "$XDG_DATA_HOME" && test -w /workspace'; then
    echo 'OpenCode data and workspace mounts must be writable by UID/GID 4196.' >&2
    exit 1
fi

bot_pid=
opencode_pid=
shutdown() {
    trap - EXIT
    for pid in "$bot_pid" "$opencode_pid"; do
        if [[ -n "$pid" ]]; then kill -TERM "$pid" 2>/dev/null || true; fi
    done
    wait
}
trap shutdown EXIT
trap 'exit 0' TERM INT

(
    cd /workspace
    export HOME=/home/assistant-ai
    exec su-exec assistant-ai opencode serve --hostname 127.0.0.1 --port 4196
) &
opencode_pid=$!
/opt/java/openjdk/bin/java -jar /app/bateacapitalassistant.jar &
bot_pid=$!

# An unexpected exit of either service stops the container so Docker can restart both.
wait -n "$bot_pid" "$opencode_pid" || exit "$?"
exit 1
