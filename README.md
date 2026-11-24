[![Test](https://github.com/Guillemsc/BateaCapitalAssistant/actions/workflows/test.yml/badge.svg)](https://github.com/Guillemsc/BateaCapitalAssistant/actions/workflows/test.yml)

# BateaCapitalAssistant

Private Telegram command bot for Sonarr, Radarr, qBittorrent, and the NAS tools service. Built with Java 21 and Maven, following Likearr's executable JAR and two-stage Docker build.

Build and test with `mvn clean verify`. The executable is `target/bateacapitalassistant-1.0.0.jar`.

For Docker Compose, set `BATEA_SECRETS_HOST` and `BATEA_STATE_HOST` to the existing host directories and run `docker compose up --build -d`. The Java bot connects directly to the NAS services. Set `BATEA_VOLUME_HOST` to a path on the NAS volume to report its free space. The integrations use the existing NAS address and ports in the source.

Mount these existing files under `/secrets`:

| File | Contents |
| --- | --- |
| `telegram-bot-token` | Bot token |
| `telegram-access.json` | JSON object with an `allowed_user_ids` array of numeric Telegram IDs |
| `homeassistant-token`, `tdarr-api-key`, `seerr-api-key`, `downtify-device-token` | Credentials for the respective integrations |
| `sonarr-api-key`, `radarr-api-key` | The respective API keys |
| `qbittorrent.json` | JSON object with `url`, `username`, and `password` |

Reuse the writable `/state` directory to carry over the Telegram update offset and Decluttarr cursor. Stop the Python bot before starting the Java bot with the same token. For Decluttarr notifications, bind mount the log directory at `/decluttarr-logs` (containing `logs.txt`) and optionally mount the config file at `/decluttarr-config.yaml`. Both mounts can be read-only.

`/disk activity` reports disk rates and the busiest readable processes using a separate two-second sample of `/proc/<pid>/io`. Mount the NAS host's `/proc` read-only at `/host-proc` and grant `SYS_PTRACE`, as in `compose.yaml`. This capability gives the container broad access to process information. NAS security settings may still restrict some counters; the response indicates when the sample is incomplete. Rates are totals across devices and may omit cached I/O or kernel activity.

The disk watchdog checks physical-disk busy time every 10 seconds. It notifies allowed Telegram users when a disk remains above 80% busy for one minute and sends a recovery notice after it remains below 60% for one minute. It starts sampling process I/O only after 40 seconds of high activity. Process rates cover all disks and do not establish which disk a process used. Active incidents and pending notifications are stored in `/state/disk-watchdog.json`, and failed sends are retried. An interruption after Telegram accepts a message but before its state is saved may result in a duplicate notification.

Disk reports include a script or JAR filename for recognized runtimes when available, and a short container ID when the process cgroup identifies a container. They do not include complete command lines. These hints are best effort; resolving a container ID to a Docker name requires Docker access.

## Publish for Synology URL import

The manual **Publish image** workflow publishes to Docker Hub. To set up publishing:

1. Create the `bateacapitalassistant` repository on Docker Hub. Use public visibility if you want Synology to import without credentials.
2. In the GitHub repository's **Settings → Secrets and variables → Actions**, add the repository secrets `DOCKERHUB_USERNAME` with your Docker Hub username and `DOCKERHUB_TOKEN` with a Docker Hub access token that can write to the repository.
3. In **Actions → Publish image → Run workflow**, start the workflow. It publishes `latest` and the selected version tag to Docker Hub.
4. In Synology **Container Manager → Image → Action → Import → Add From URL**, enter `https://hub.docker.com/r/<docker-hub-username>/bateacapitalassistant`.
