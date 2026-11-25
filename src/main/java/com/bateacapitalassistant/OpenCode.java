package com.bateacapitalassistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

final class OpenCode {
    private static final String URL = System.getenv("BATEA_OPENCODE_URL");
    private final Map<Long, String> sessions = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static boolean enabled() {
        return URL != null && !URL.isBlank();
    }

    String reply(long user, String prompt) throws IOException, InterruptedException {
        String session = sessions.get(user);
        if (session == null) {
            session = post("/session", Map.of("title", "Telegram")).path("id").asText();
            if (!session.matches("ses_[a-zA-Z0-9]+")) throw new IOException("Invalid OpenCode session");
            sessions.put(user, session);
        }
        var answer = post("/session/" + session + "/message", Map.of(
                "parts", List.of(Map.of("type", "text", "text", prompt))));
        var lines = new ArrayList<String>();
        for (var part : answer.path("parts")) {
            if (part.path("type").asText().equals("text") && !part.path("synthetic").asBoolean()) {
                lines.add(part.path("text").asText());
            }
        }
        String result = String.join("\n", lines).strip();
        return result.isEmpty() ? "OpenCode returned no reply. Check its ChatGPT connection and service logs." : result;
    }

    private JsonNode post(String path, Object body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(URL + path))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Bridge.JSON.writeValueAsString(body)));
        String password = System.getenv("OPENCODE_SERVER_PASSWORD");
        if (password != null && !password.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString(
                    ("opencode:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + credentials);
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("OpenCode HTTP " + response.statusCode());
        return Bridge.JSON.readTree(response.body());
    }
}
