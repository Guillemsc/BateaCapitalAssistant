package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class Seerr {
    private static final String BASE = "http://192.168.1.152:5055/api/v1/";
    private static final Map<Integer, String> REQUEST_STATUS = Map.of(1, "pending approval", 2, "approved", 3, "declined", 4, "failed", 5, "completed");
    private static final Map<Integer, String> MEDIA_STATUS = Map.of(1, "unknown", 2, "pending", 3, "processing", 4, "partially available", 5, "available", 6, "blocklisted", 7, "deleted");

    private Seerr() {
    }

    private static JsonNode call(String path, Object body) throws IOException, InterruptedException {
        return IntegrationSupport.request(BASE + path, body, Map.of("X-Api-Key", IntegrationSupport.secret("seerr-api-key")), 30);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    static String year(JsonNode node, String field) {
        return limit(value(node, field, ""), 4);
    }

    public static ObjectNode search(String query) throws IOException, InterruptedException {
        if (query.isBlank() || IntegrationSupport.length(query) > 200) throw new IOException("Invalid search query");
        JsonNode response = call("search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&page=1", null);
        ArrayNode results = IntegrationSupport.JSON.createArrayNode();
        for (JsonNode item : response.path("results")) {
            String type = item.path("mediaType").asText();
            if (!Set.of("movie", "tv").contains(type)) continue;
            ObjectNode row = results.addObject();
            row.set("tmdb_id", item.path("id"));
            row.put("type", type).put("title", first(value(item, "title", ""), value(item, "name", "")));
            row.put("date", first(value(item, "releaseDate", ""), value(item, "firstAirDate", "")));
            row.set("media_status", item.path("mediaInfo").path("status"));
            if (results.size() == 8) break;
        }
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        result.set("results", results);
        return result;
    }

    public static ObjectNode status() throws IOException, InterruptedException {
        JsonNode counts = call("request/count", null), response = call("request?take=10&skip=0&sort=added", null);
        ArrayNode recent = IntegrationSupport.JSON.createArrayNode();
        for (JsonNode item : rows(response.path("results")).stream().limit(10).toList()) {
            JsonNode media = item.path("media");
            String kind = first(value(item, "type", ""), value(media, "mediaType", ""));
            String title = "Title temporarily unavailable", year = "";
            if (Set.of("movie", "tv").contains(kind) && media.path("tmdbId").isIntegralNumber()) {
                try {
                    JsonNode details = call(kind + "/" + media.path("tmdbId").asText(), null);
                    title = first(value(details, "title", ""), value(details, "name", ""), title);
                    year = limit(first(value(details, "releaseDate", ""), value(details, "firstAirDate", "")), 4);
                } catch (IOException ignored) {
                }
            }
            ObjectNode row = recent.addObject().put("title", title).put("year", year);
            row.set("request_status", item.path("status"));
            row.set("media_status", media.path("status"));
            row.put("request_status_label", REQUEST_STATUS.getOrDefault(item.path("status").asInt(), "unknown"));
            row.put("media_status_label", MEDIA_STATUS.getOrDefault(media.path("status").asInt(), "unknown"));
        }
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        result.set("counts", counts);
        result.set("recent", recent);
        return result;
    }

    static List<JsonNode> matchingFilms(ArrayNode choices, String title, JsonNode yearNode) {
        return rows(choices).stream().filter(r -> r.path("type").asText().equals("movie"))
                .filter(r -> normalize(r.path("title").asText()).equals(normalize(title)))
                .filter(r -> yearNode == null || yearNode.isNull() || year(r, "date").equals(yearNode.asText())).toList();
    }

    public static synchronized ObjectNode request(String title, JsonNode yearNode) throws IOException, InterruptedException {
        ArrayNode choices = (ArrayNode) search(title).path("results");
        List<JsonNode> matches = matchingFilms(choices, title, yearNode);
        if (matches.size() != 1 || !year(matches.getFirst(), "date").matches("[0-9]{4}")) {
            ObjectNode result = IntegrationSupport.JSON.createObjectNode().put("submitted", false).put("needs_clarification", true);
            result.set("choices", choices);
            return result;
        }
        JsonNode selected = matches.getFirst();
        JsonNode details = call("movie/" + selected.required("tmdb_id").asText(), null);
        String actual = first(value(details, "title", ""), value(details, "name", ""));
        String date = first(value(details, "releaseDate", ""), value(details, "firstAirDate", ""));
        if (!normalize(actual).equals(normalize(selected.path("title").asText())) || !limit(date, 4).equals(year(selected, "date")))
            throw new IOException("Title or year changed; search again");
        JsonNode media = details.path("mediaInfo");
        if (media.path("status").asInt() == 6) throw new IOException("Media is blocklisted");
        boolean existing = rows(media.path("requests")).stream().anyMatch(r -> Set.of(1, 2, 5).contains(r.path("status").asInt()) && !r.path("is4k").asBoolean());
        if (media.path("status").asInt() == 5 || existing)
            return IntegrationSupport.JSON.createObjectNode().put("submitted", false).put("reason", "Already available or has an existing pending/approved/completed request. No duplicate submitted.");
        JsonNode response = call("request", Map.of("mediaType", "movie", "mediaId", selected.required("tmdb_id").intValue(), "is4k", false));
        ObjectNode result = IntegrationSupport.JSON.createObjectNode().put("submitted", true).put("title", actual);
        result.set("request_id", response.path("id"));
        result.set("request_status", response.path("status"));
        return result;
    }
}
