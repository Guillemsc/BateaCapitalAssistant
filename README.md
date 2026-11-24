[![Test](https://github.com/Guillemsc/BateaCapitalAssistant/actions/workflows/test.yml/badge.svg)](https://github.com/Guillemsc/BateaCapitalAssistant/actions/workflows/test.yml)

# BateaCapitalAssistant

Private Telegram command bot for Sonarr, Radarr, qBittorrent, and the NAS tools service. Built with Java 21 and Maven, following Likearr's executable JAR and two-stage Docker build.

Build and test with `mvn clean verify`. The executable is `target/bateacapitalassistant-1.0.0.jar`.

For Docker Compose, set `BATEA_SECRETS_HOST` and `BATEA_STATE_HOST` to the existing host directories and run `docker compose up --build -d`. The container uses host networking to reach the NAS tools MCP endpoint at `127.0.0.1:4097`. Sonarr and Radarr use the existing NAS address and ports in `MediaServices.java`.

Mount these existing files under `/secrets`:

| File | Contents |
| --- | --- |
| `telegram-bot-token` | Bot token |
| `telegram-access.json` | JSON object with an `allowed_user_ids` array of numeric Telegram IDs |
| `nas-tools-token` | Bearer token for the NAS tools service |
| `sonarr-api-key`, `radarr-api-key` | The respective API keys |
| `qbittorrent.json` | JSON object with `url`, `username`, and `password` |

Reuse the writable `/state` directory to carry over the Telegram update offset and Decluttarr cursor. Stop the Python bot before starting the Java bot with the same token. For Decluttarr notifications, bind mount the log directory at `/decluttarr-logs` (containing `logs.txt`) and optionally mount the config file at `/decluttarr-config.yaml`. Both mounts can be read-only.

## Publish for Synology URL import

The manual **Publish image** workflow publishes to GHCR. To publish the same image to Docker Hub as well:

1. Create the `bateacapitalassistant` repository on Docker Hub. Use public visibility if you want Synology to import without credentials.
2. In the GitHub repository's **Settings → Secrets and variables → Actions**, add the repository variable `DOCKERHUB_USERNAME` with your Docker Hub username and the repository secret `DOCKERHUB_TOKEN` with a Docker Hub access token that can write to the repository.
3. In **Actions → Publish image → Run workflow**, enable **Also publish to Docker Hub for Synology URL import**. The workflow publishes `latest` and the selected version tag to both registries.
4. In Synology **Container Manager → Image → Action → Import → Add From URL**, enter `https://hub.docker.com/r/<docker-hub-username>/bateacapitalassistant`.
