package com.bateacapitalassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class OpenCode {
    private static final String URL = System.getenv("BATEA_OPENCODE_URL");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long SESSION_IDLE_NANOS = Duration.ofMinutes(30).toNanos();

    private record Session(String id, long lastInteraction) {}

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static boolean enabled() {
        return URL != null && !URL.isBlank();
    }

    String reply(long user, String prompt) throws IOException, InterruptedException {
        long now = System.nanoTime();
        Session previous = sessions.get(user);
        String session;
        if (previous == null || now - previous.lastInteraction() >= SESSION_IDLE_NANOS) {
            session = request("/api/session", Map.of("title", "Telegram"), REQUEST_TIMEOUT)
                    .path("data").path("id").asText();
            if (!session.matches("ses_[a-zA-Z0-9]+")) throw new IOException("Invalid OpenCode session ID");
        } else {
            session = previous.id();
        }
        Session current = new Session(session, now);
        sessions.put(user, current);

        String path = "/api/session/" + session;
        request(path + "/prompt", Map.of("text", prompt, "agent", "telegram"), REQUEST_TIMEOUT);
        try {
            request("/api/experimental/session/" + session + "/wait", Map.of(), Duration.ofMinutes(3));
        } catch (IOException e) {
            sessions.remove(user, current);
            try {
                request(path + "/interrupt", Map.of(), REQUEST_TIMEOUT);
            } catch (IOException interruptFailure) {
                e.addSuppressed(interruptFailure);
            }
            throw e;
        }
        JsonNode messages = request(path + "/message?order=desc&limit=50", null, REQUEST_TIMEOUT).path("data");
        if (!messages.isArray()) throw new IOException("Invalid OpenCode transcript");
        return replyText(messages);
    }

    private static String replyText(JsonNode messages) {
        var newestFirst = new ArrayList<String>();
        for (JsonNode message : messages) {
            String type = message.path("type").asText();
            if (type.equals("user")) break;
            if (message.hasNonNull("error")) {
                int status = message.path("error").path("status").asInt();
                if (status == 401 || status == 403) return "OpenCode authentication failed. Check the ChatGPT sign-in.";
                if (status == 429) return "OpenCode reached a provider limit. Check your ChatGPT usage.";
                return "OpenCode could not complete the request. Check its service logs.";
            }
            if (!type.equals("assistant")) continue;

            var parts = new ArrayList<String>();
            for (JsonNode part : message.path("content")) {
                if (part.path("type").asText().equals("text")) {
                    String text = part.path("text").asText().strip();
                    if (!text.isEmpty()) parts.add(text);
                }
            }
            if (!parts.isEmpty()) newestFirst.add(String.join("\n", parts));
        }
        return newestFirst.isEmpty()
                ? "OpenCode returned no reply. Check its model, ChatGPT sign-in, and service logs."
                : String.join("\n", newestFirst.reversed());
    }

    private JsonNode request(String path, Object body, Duration timeout) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(URL + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Bridge.JSON.writeValueAsString(body)));
        }
        String password = System.getenv("OPENCODE_SERVER_PASSWORD");
        if (password != null && !password.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString(
                    ("opencode:" + password).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + credentials);
        }
        String endpoint = path.replaceAll("ses_[a-zA-Z0-9]+", "{session}");
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("OpenCode " + endpoint + ": " + e.getClass().getSimpleName(), e);
        }
        if (response.statusCode() / 100 != 2) throw new IOException("OpenCode " + endpoint + ": HTTP " + response.statusCode());
        if (response.statusCode() == 204) return NullNode.getInstance();
        JsonNode json;
        try {
            json = Bridge.JSON.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("OpenCode " + endpoint + ": invalid JSON response", e);
        }
        if (json == null || json.isMissingNode()) throw new IOException("OpenCode " + endpoint + ": empty response");
        return json;
    }
}
