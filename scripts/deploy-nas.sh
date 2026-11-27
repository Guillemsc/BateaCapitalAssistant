#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
nas=BateaCapital-admin@192.168.1.152
port=5552
stage=$(ssh -p "$port" "$nas" 'mktemp -d /tmp/batea-deploy.XXXXXXXX')
if [[ ! "$stage" =~ ^/tmp/batea-deploy\.[A-Za-z0-9]+$ ]]; then
    echo "Unexpected staging path from NAS" >&2
    exit 1
fi
trap 'ssh -p "$port" "$nas" "rm -f $stage/compose.opencode.yaml && rmdir $stage" || true' EXIT

tar -C "$repo" -cf - compose.opencode.yaml |
    ssh -p "$port" "$nas" "tar -C $stage -xf -"

# Allocate a terminal for the NAS administrator's sudo prompt.
remote_script=$(cat <<'REMOTE'
set -eu
stage=$1
cd /volume1/docker/opencode/app
compose() {
    /usr/local/bin/docker compose -p batea-opencode --project-directory "$PWD" --env-file "$PWD/.env" "$@"
}
compose -f "$stage/compose.opencode.yaml" config --quiet
compose -f "$stage/compose.opencode.yaml" pull bateacapitalassistant
install -m 0644 "$stage/compose.opencode.yaml" compose.opencode.yaml
compose -f compose.opencode.yaml up -d --pull never
compose -f compose.opencode.yaml ps
REMOTE
)
# Quote the script for the remote shell, then pass the validated staging path separately.
remote_script=${remote_script//\'/\'\\\'\'}
ssh -t -p "$port" "$nas" "sudo sh -c '$remote_script' sh '$stage'"
