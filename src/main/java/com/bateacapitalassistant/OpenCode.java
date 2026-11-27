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
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(3);
    private static final long SESSION_IDLE_NANOS = Duration.ofMinutes(30).toNanos();

    private record Session(String id, long lastInteraction) {
    }

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    interface Transport {
        JsonNode request(String path, Object body, Duration timeout) throws IOException, InterruptedException;
    }

    static final class TransportFailure extends IOException {
        TransportFailure(String endpoint, IOException cause) {
            super("OpenCode " + endpoint + ": " + cause.getClass().getSimpleName()
                    + (cause.getCause() == null ? "" : " / " + cause.getCause().getClass().getSimpleName()), cause);
        }
    }

    private final Transport transport;
    private final Duration waitRetryDelay;

    OpenCode() {
        transport = this::request;
        waitRetryDelay = Duration.ofSeconds(1);
    }

    OpenCode(Transport transport) {
        this(transport, Duration.ofSeconds(1));
    }

    OpenCode(Transport transport, Duration waitRetryDelay) {
        this.transport = transport;
        this.waitRetryDelay = waitRetryDelay;
    }

    static boolean enabled() {
        return URL != null && !URL.isBlank();
    }

    String reply(long user, String prompt) throws IOException, InterruptedException {
        long now = System.nanoTime();
        Session previous = sessions.get(user);
        String session;
        if (previous == null || now - previous.lastInteraction() >= SESSION_IDLE_NANOS) {
            session = transport.request("/api/session", Map.of("title", "Telegram"), SESSION_TIMEOUT)
                    .path("data").path("id").asText();
            if (!session.matches("ses_[a-zA-Z0-9]+")) throw new IOException("Invalid OpenCode session ID");
        } else {
            session = previous.id();
        }
        String path = "/api/session/" + session;
        try {
            transport.request(path + "/prompt", Map.of("text", prompt, "agent", "telegram"), PROMPT_TIMEOUT);
            waitForCompletion(session);
            JsonNode messages = transport.request(path + "/message?order=desc&limit=50", null, REQUEST_TIMEOUT).path("data");
            if (!messages.isArray()) throw new IOException("Invalid OpenCode transcript");
            String reply = replyText(messages);
            sessions.put(user, new Session(session, System.nanoTime()));
            return reply;
        } catch (IOException e) {
            if (previous != null) sessions.remove(user, previous);
            try {
                transport.request(path + "/interrupt", Map.of(), REQUEST_TIMEOUT);
            } catch (IOException interruptFailure) {
                e.addSuppressed(interruptFailure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                e.addSuppressed(interrupted);
            }
            throw e;
        } catch (InterruptedException e) {
            if (previous != null) sessions.remove(user, previous);
            throw e;
        }
    }

    private void waitForCompletion(String session) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + PROMPT_TIMEOUT.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.net.http.HttpTimeoutException("OpenCode completion deadline exceeded");
            try {
                transport.request("/api/experimental/session/" + session + "/wait", Map.of(), Duration.ofNanos(remaining));
                return;
            } catch (TransportFailure e) {
                if (System.nanoTime() >= deadline)
                    throw new java.net.http.HttpTimeoutException("OpenCode completion deadline exceeded");
                System.err.println(e.getMessage() + "; reconnecting wait without resubmitting prompt");
                long pause = Math.min(waitRetryDelay.toNanos(), Math.max(0, deadline - System.nanoTime()));
                Thread.sleep(Duration.ofNanos(pause));
            }
        }
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

    static String failureMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException)
                return "OpenCode took too long to respond. The request may still be running; check OpenCode before repeating an action.";
            if (cause instanceof java.net.ConnectException)
                return "Could not connect to the OpenCode server. Check that it is running and reachable.";
        }
        String detail = error instanceof IOException ? error.getMessage() : "";
        if (detail != null && (detail.endsWith(": HTTP 401") || detail.endsWith(": HTTP 403")))
            return "The OpenCode server rejected the bot's credentials. Check the bot's OpenCode server password.";
        return "OpenCode could not complete this request. Check the bot logs for the failing endpoint and error.";
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
            throw new TransportFailure(endpoint, e);
        }
        if (response.statusCode() / 100 != 2)
            throw new IOException("OpenCode " + endpoint + ": HTTP " + response.statusCode());
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
