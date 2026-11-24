package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.StreamSupport;

public final class IntegrationSupport {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final Path SECRETS = Path.of("/secrets");
    public static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private IntegrationSupport() {
    }

    public static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    public static String secret(String name) throws IOException {
        return Files.readString(SECRETS.resolve(name)).strip();
    }

    public static String http(HttpClient client, String url, String body, Map<String, String> headers, int seconds)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds));
        headers.forEach(request::header);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("HTTP request failed: " + response.statusCode());
        return response.body();
    }

    public static JsonNode request(String url, Object body, Map<String, String> headers, int seconds) throws IOException, InterruptedException {
        var all = new HashMap<>(headers);
        all.put("Content-Type", "application/json");
        return JSON.readTree(http(HTTP, url, body == null ? null : JSON.writeValueAsString(body), all, seconds));
    }

    public static String limit(String value, int count) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(count, length(value))));
    }

    public static String value(JsonNode node, String field, String fallback) {
        return node.path(field).asText(fallback);
    }

    public static String first(String... choices) {
        for (String choice : choices) if (choice != null && !choice.isEmpty()) return choice;
        return "";
    }

    public static List<JsonNode> rows(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).toList();
    }

    public static String join(JsonNode node, String delimiter) {
        return String.join(delimiter, rows(node).stream().map(JsonNode::asText).toList());
    }

    public static String counts(JsonNode node, String delimiter, boolean sorted) {
        Map<String, String> counts = sorted ? new TreeMap<>() : new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asText()));
        return String.join(delimiter, counts.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
    }

    public static String format(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    public static String size(JsonNode number) {
        double value = number.asDouble();
        for (String unit : List.of("B", "KiB", "MiB", "GiB", "TiB")) {
            if (value < 1024 || unit.equals("TiB")) return format("%.1f %s", value, unit);
            value /= 1024;
        }
        throw new AssertionError();
    }

}
