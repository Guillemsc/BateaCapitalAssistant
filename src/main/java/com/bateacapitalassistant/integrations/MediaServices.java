package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.util.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class MediaServices {
    private static final Set<String> ATTENTION = Set.of("metaDL", "forcedMetaDL", "stalledDL", "error", "missingFiles");
    private static final Set<String> DOWNLOADING = Set.of("downloading", "forcedDL");
    private static final Map<String, String> TORRENT_LABELS = Map.of("metaDL", "FETCHING METADATA",
            "forcedMetaDL", "FETCHING METADATA (forced)", "stalledDL", "STALLED", "queuedDL", "QUEUED");

    private MediaServices() {
    }

    private static JsonNode arr(boolean sonarr, String path, Object body) throws IOException, InterruptedException {
        String port = sonarr ? "8989" : "7878";
        String key = IntegrationSupport.secret(sonarr ? "sonarr-api-key" : "radarr-api-key");
        return IntegrationSupport.request("http://192.168.1.152:" + port + "/api/v3/" + path, body,
                Map.of("X-Api-Key", key), 25);
    }

    public static String queue(boolean sonarr) throws IOException, InterruptedException {
        var queue = arr(sonarr, "queue?page=1&pageSize=15" + (sonarr
                ? "&includeUnknownSeriesItems=true&includeSeries=true&includeEpisode=true"
                : "&includeUnknownMovieItems=true&includeMovie=true"), null);
        var health = rows(arr(sonarr, "health", null));
        var lines = new ArrayList<String>();
        int count = queue.path("totalRecords").asInt();
        lines.add((sonarr ? "Sonarr: " : "Films (Radarr): ") + count + " queue entries");
        for (var row : queue.path("records")) {
            String title = first(value(row.path(sonarr ? "series" : "movie"), "title", ""),
                    value(row, "title", ""), sonarr ? "Unknown series" : "Unknown film");
            var episode = row.path("episode");
            if (sonarr && episode.hasNonNull("episodeNumber"))
                title += format(" — S%02dE%02d", episode.path("seasonNumber").asInt(), episode.path("episodeNumber").asInt());
            double total = row.path("size").asDouble();
            var remaining = row.path("sizeleft");
            String progress = total > 0 && remaining.isNumber()
                    ? format(" — %.1f%%", Math.clamp(100 * (total - remaining.asDouble()) / total, 0, 100)) : "";
            lines.add("• " + title + " — " + first(value(row, "status", ""), "unknown") + progress);
            for (var status : row.path("statusMessages")) {
                String label = value(status, "title", "");
                if (!label.isEmpty()) lines.add("  " + limit(label, 250));
                rows(status.path("messages")).stream().limit(3)
                        .forEach(message -> lines.add("  " + limit(message.asText(), 250)));
            }
        }
        if (queue.path("records").isEmpty()) lines.add("No queued downloads or import issues reported.");
        if (count > 15) lines.add("Showing the first 15 queue entries.");
        if (!sonarr) lines.add("\nHealth: " + (health.isEmpty() ? "no alerts reported" : "alerts"));
        else if (!health.isEmpty()) lines.add("\nHealth alerts");
        for (var item : health.stream().limit(5).toList())
            lines.add("• "
                    + (sonarr ? value(item, "type", "alert") + ": " : "") + limit(value(item, "message", "Unknown issue"), 400));
        if (sonarr) lines.add("\nSonarr download/import status—not Tdarr transcoding progress.");
        return String.join("\n", lines);
    }

    public static String searchSeason(List<String> parts) throws IOException, InterruptedException {
        String usage = "Use /shows status or /shows search \"Exact Show Title\" season 6. Search can automatically grab releases.";
        int count = parts.size();
        if (count < 5 || !parts.get(1).equalsIgnoreCase("search") || !parts.get(count - 2).equalsIgnoreCase("season")
                || !parts.getLast().matches("\\p{Nd}+")) return usage;
        int season;
        try {
            season = Integer.parseInt(parts.getLast());
        } catch (NumberFormatException e) {
            return usage;
        }
        String title = String.join(" ", parts.subList(2, count - 2)).strip();
        var shows = rows(arr(true, "series", null)).stream()
                .filter(row -> fold(row.path("title").asText()).equals(fold(title))).toList();
        if (shows.size() != 1)
            return "No unique exact show title in Sonarr. Check the title in Sonarr; nothing searched.";
        var show = shows.getFirst();
        var selected = rows(show.path("seasons")).stream()
                .filter(row -> row.path("seasonNumber").asInt() == season).findFirst();
        if (season < 1 || !show.path("monitored").asBoolean() || !selected.map(row -> row.path("monitored").asBoolean()).orElse(false))
            return "That show/season is absent or not monitored. Nothing searched; monitoring settings were not changed.";

        long id = show.required("id").longValue();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        var missing = new LinkedHashSet<Long>();
        for (var episode : arr(true, "episode?seriesId=" + id, null)) {
            String airDate = episode.path("airDate").asText("");
            if (episode.path("seasonNumber").asInt() == season && episode.path("monitored").asBoolean()
                    && !episode.path("hasFile").asBoolean() && !airDate.isEmpty() && airDate.compareTo(today) <= 0)
                missing.add(episode.required("id").longValue());
        }
        if (missing.isEmpty()) return "No aired, monitored missing episodes in that season. No search started.";
        var queued = arr(true, "queue?page=1&pageSize=1000&includeUnknownSeriesItems=true", null);
        for (var row : queued.path("records"))
            if ((row.hasNonNull("episodeId") && missing.contains(row.path("episodeId").longValue()))
                    || (row.path("seriesId").longValue() == id && row.path("seasonNumber").asInt() == season))
                return "This season already has queue entries. Check /shows status and resolve/blocklist suspicious or blocked downloads before searching again.";
        try {
            arr(true, "command", Map.of("name", "EpisodeSearch", "episodeIds", missing));
        } catch (IOException | RuntimeException e) {
            return "Search submission outcome uncertain. Check Sonarr tasks/queue before retrying.";
        }
        return format("Search submitted for %s season %d: %d missing episodes. Sonarr may automatically grab eligible releases. Use /shows status for progress.",
                show.path("title").asText(), season, missing.size());
    }

    private static String fold(String value) {
        return value.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode torrentGet(HttpClient client, String base, String path) throws IOException, InterruptedException {
        return IntegrationSupport.JSON.readTree(IntegrationSupport.http(client, base + "/api/v2/" + path, null, Map.of("Referer", base), 20));
    }

    public static String torrents() throws IOException, InterruptedException {
        var config = IntegrationSupport.JSON.readTree(IntegrationSupport.SECRETS.resolve("qbittorrent.json").toFile());
        String base = config.required("url").asText().replaceAll("/+$", "");
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String credentials = "username=" + encode(config.required("username").asText())
                + "&password=" + encode(config.required("password").asText());
        String login = IntegrationSupport.http(client, base + "/api/v2/auth/login", credentials,
                Map.of("Referer", base, "Content-Type", "application/x-www-form-urlencoded"), 20).strip();
        if (!login.isEmpty() && !login.equals("Ok."))
            throw new IllegalStateException("qBittorrent authentication failed");
        var info = torrentGet(client, base, "transfer/info");
        var torrents = rows(torrentGet(client, base, "torrents/info"));
        var counts = IntegrationSupport.JSON.createObjectNode();
        for (var torrent : torrents) {
            String state = value(torrent, "state", "unknown");
            counts.put(state, counts.path(state).asInt() + 1);
        }
        var lines = new ArrayList<String>();
        lines.add("qBittorrent status — " + LocalTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm")) + " UTC");
        lines.add("Connection: " + value(info, "connection_status", "unknown"));
        lines.add("Download: " + size(info.path("dl_info_speed")) + "/s; upload: " + size(info.path("up_info_speed")) + "/s");
        lines.add(torrents.size() + " torrents; " + counts(counts, ", ", true));
        var order = Comparator.comparingInt(MediaServices::torrentRank)
                .thenComparing(row -> row.path("progress").asDouble() >= 1)
                .thenComparing(row -> row.path("name").asText());
        for (var row : torrents.stream().sorted(order).limit(15).toList()) {
            String state = value(row, "state", "unknown");
            lines.add(format("• %s — %s; %.1f%%; %s/s; %s connected seeds", limit(value(row, "name", "Unknown"), 180),
                    TORRENT_LABELS.getOrDefault(state, state), row.path("progress").asDouble() * 100,
                    size(row.path("dlspeed")), value(row, "num_seeds", "0")));
            long added = row.path("added_on").asLong();
            if (ATTENTION.contains(state) && added != 0) lines.add("  Added "
                    + Math.max(0, (Instant.now().getEpochSecond() - added) / 60) + " min ago (not necessarily time stuck).");
        }
        if (torrents.size() > 15)
            lines.add("Showing 15 torrents: downloading first, then metadata/stalled/errors, then others.");
        if (torrents.isEmpty()) lines.add("No torrents.");
        return String.join("\n", lines);
    }

    private static int torrentRank(JsonNode row) {
        String state = row.path("state").asText();
        if (DOWNLOADING.contains(state)) return 0;
        return ATTENTION.contains(state) ? 1 : 2;
    }
}
