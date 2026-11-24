package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class Music {
    private static final String BASE = "http://192.168.1.152:8800/api/";

    private Music() {
    }

    private static JsonNode call(String path, Object body) throws IOException, InterruptedException {
        return IntegrationSupport.request(BASE + path, body, Map.of("Authorization", "Bearer " + IntegrationSupport.secret("downtify-device-token")), 40);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static ArrayNode searchRows(String query) throws IOException, InterruptedException {
        if (query.isBlank() || IntegrationSupport.length(query) > 200) throw new IOException("Invalid search query");
        JsonNode response = call("songs/search?query=" + encode(query), null);
        if (!(response instanceof ArrayNode array)) throw new IOException("Invalid music search response");
        return array;
    }

    private static ObjectNode compact(JsonNode node, String... fields) {
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        for (String field : fields) if (node.has(field)) result.set(field, node.get(field));
        return result;
    }

    public static ObjectNode search(String query) throws IOException, InterruptedException {
        ArrayNode results = IntegrationSupport.JSON.createArrayNode();
        rows(searchRows(query)).stream().limit(8).forEach(r -> results.add(compact(r, "song_id", "name", "artists", "album_name", "duration", "source")));
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        result.set("results", results);
        return result;
    }

    public static ObjectNode status() throws IOException, InterruptedException {
        JsonNode state = call("queue/state", null), queue = call("queue", null);
        if (!queue.isArray()) throw new IOException("Invalid music queue");
        ObjectNode counts = IntegrationSupport.JSON.createObjectNode();
        for (JsonNode row : queue) {
            String status = value(row, "status", "unknown");
            counts.put(status, counts.path(status).asInt() + 1);
        }
        List<JsonNode> sorted = new ArrayList<>(rows(queue));
        sorted.sort(Comparator.comparing(r -> !Set.of("queued", "downloading").contains(r.path("status").asText())));
        ArrayNode jobs = IntegrationSupport.JSON.createArrayNode();
        for (JsonNode row : sorted.stream().limit(15).toList()) {
            ObjectNode job = compact(row, "status", "progress", "position", "provider");
            job.set("title", row.path("song").path("name"));
            job.set("artists", row.path("song").path("artists"));
            jobs.add(job);
        }
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        result.set("queue_state", state.path("state"));
        result.set("active", state.path("active"));
        result.set("counts", counts);
        result.set("jobs", jobs);
        return result;
    }

    public static ObjectNode releases(String artist, int limit) throws IOException, InterruptedException {
        if (artist.isBlank() || IntegrationSupport.length(artist) > 200 || limit < 1 || limit > 25)
            throw new IOException("Invalid artist");
        JsonNode result = call("finder/search?query=" + encode(artist) + "&limit=25", null);
        Map<String, JsonNode> unique = new LinkedHashMap<>();
        for (JsonNode row : result.path("artists"))
            if (Seerr.normalize(row.path("name").asText()).equals(Seerr.normalize(artist)))
                unique.put(row.path("artist_id").asText(), row);
        if (unique.size() != 1) {
            List<JsonNode> choices = unique.isEmpty() ? rows(result.path("artists")) : new ArrayList<>(unique.values());
            ArrayNode options = IntegrationSupport.JSON.createArrayNode();
            for (JsonNode row : choices.stream().limit(5).toList()) {
                ObjectNode option = compact(row, "name", "artist_id");
                ArrayNode albums = option.putArray("example_albums");
                rows(result.path("albums")).stream().filter(a -> a.path("artist_id").asText().equals(row.path("artist_id").asText()))
                        .limit(4).forEach(a -> albums.add(a.path("name").asText()));
                options.add(option);
            }
            ObjectNode output = IntegrationSupport.JSON.createObjectNode().put("needs_clarification", true);
            output.set("choices", options);
            return output;
        }
        JsonNode match = unique.values().iterator().next();
        String id = match.path("artist_id").asText();
        if (!id.matches("[0-9]{1,30}")) throw new IOException("Invalid artist ID");
        JsonNode response = call("finder/artist/albums?artist_id=" + encode(id), null);
        if (!response.isArray()) throw new IOException("Invalid discography");
        List<JsonNode> releases = new ArrayList<>(rows(response));
        releases.sort(Comparator.comparing((JsonNode r) -> first(value(r, "release_date", ""), value(r, "year", ""))).reversed());
        ArrayNode items = IntegrationSupport.JSON.createArrayNode();
        releases.stream().limit(limit).forEach(r -> items.add(compact(r, "name", "release_date", "year", "release_type")));
        ObjectNode output = IntegrationSupport.JSON.createObjectNode().put("needs_clarification", false).put("artist", match.path("name").asText());
        output.set("items", items);
        return output;
    }

    static List<JsonNode> matchingSongs(ArrayNode results, String title, String artist) {
        return rows(results).stream().filter(r -> Seerr.normalize(r.path("name").asText()).equals(Seerr.normalize(title)))
                .filter(r -> rows(r.path("artists")).stream().anyMatch(a -> Seerr.normalize(a.asText()).equals(Seerr.normalize(artist)))).toList();
    }

    public static synchronized ObjectNode request(String title, String artist) throws IOException, InterruptedException {
        if (title.isBlank() || artist.isBlank() || IntegrationSupport.length(title) > 90 || IntegrationSupport.length(artist) > 90)
            throw new IOException("Invalid song title or artist");
        String query = artist + " " + title;
        ArrayNode results = searchRows(query);
        List<JsonNode> matches = matchingSongs(results, title, artist);
        if (matches.size() != 1) {
            ArrayNode choices = IntegrationSupport.JSON.createArrayNode();
            (matches.isEmpty() ? rows(results) : matches).stream().limit(8).forEach(r -> choices.add(compact(r, "song_id", "name", "artists", "album_name", "duration")));
            ObjectNode result = IntegrationSupport.JSON.createObjectNode().put("submitted", false).put("needs_clarification", true);
            result.set("choices", choices);
            return result;
        }
        JsonNode chosen = matches.getFirst();
        String id = chosen.path("song_id").asText();
        if (id.isBlank()) throw new IOException("Missing song ID");
        List<JsonNode> verified = rows(searchRows(query)).stream().filter(r -> r.path("song_id").asText().equals(id)).toList();
        if (verified.size() != 1 || !Seerr.normalize(verified.getFirst().path("name").asText()).equals(Seerr.normalize(title))
                || rows(verified.getFirst().path("artists")).stream().noneMatch(a -> Seerr.normalize(a.asText()).equals(Seerr.normalize(artist))))
            throw new IOException("Selected song could not be verified");
        JsonNode queue = call("queue", null);
        if (!queue.isArray()) throw new IOException("Invalid music queue");
        for (JsonNode row : queue)
            if (row.path("song").path("song_id").asText().equals(id) && Set.of("queued", "downloading", "done").contains(row.path("status").asText()))
                return IntegrationSupport.JSON.createObjectNode().put("submitted", false).put("reason", "Song already queued/downloading/completed in visible queue");
        call("download/batch", Map.of("songs", List.of(verified.getFirst()), "generate_m3u", false));
        return IntegrationSupport.JSON.createObjectNode().put("submitted", true).put("title", verified.getFirst().path("name").asText());
    }
}
