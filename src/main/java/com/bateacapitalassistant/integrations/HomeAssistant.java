package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class HomeAssistant {
    private static final String BASE = "http://192.168.1.152:8123";

    private HomeAssistant() {
    }

    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > 16 * 1024 * 1024) {
                messages.offer("");
                socket.abort();
            } else if (last) {
                messages.offer(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            messages.offer("");
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            messages.offer("");
            return null;
        }

        JsonNode receive() throws IOException, InterruptedException {
            String message = messages.poll(15, TimeUnit.SECONDS);
            if (message == null || message.isEmpty()) throw new IOException("Home Assistant WebSocket unavailable");
            return IntegrationSupport.JSON.readTree(message);
        }
    }

    private static List<JsonNode> registries(String token) throws IOException, InterruptedException {
        Listener listener = new Listener();
        WebSocket socket;
        try {
            socket = IntegrationSupport.HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create("ws://192.168.1.152:8123/api/websocket"), listener).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Home Assistant connection failed", e);
        }
        try {
            if (!listener.receive().path("type").asText().equals("auth_required"))
                throw new IOException("Unexpected authentication response");
            send(socket, Map.of("type", "auth", "access_token", token));
            if (!listener.receive().path("type").asText().equals("auth_ok"))
                throw new IOException("Home Assistant authentication failed");
            List<JsonNode> result = new ArrayList<>();
            int id = 1;
            for (String kind : List.of("area", "device", "entity")) {
                send(socket, Map.of("id", id, "type", "config/" + kind + "_registry/list"));
                JsonNode response = listener.receive();
                if (response.path("id").asInt() != id || !response.path("success").asBoolean() || !response.path("result").isArray())
                    throw new IOException("Home Assistant registry query failed");
                result.add(response.path("result"));
                id++;
            }
            return result;
        } finally {
            socket.abort();
        }
    }

    private static void send(WebSocket socket, Object value) throws IOException, InterruptedException {
        try {
            socket.sendText(IntegrationSupport.JSON.writeValueAsString(value), true).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Home Assistant request failed", e);
        }
    }

    public static ObjectNode status(String query) throws IOException, InterruptedException {
        String token = IntegrationSupport.secret("homeassistant-token");
        List<JsonNode> registries = registries(token);
        Map<String, String> areas = new HashMap<>(), deviceAreas = new HashMap<>();
        Map<String, JsonNode> entities = new HashMap<>();
        registries.get(0).forEach(a -> areas.put(a.path("area_id").asText(), a.path("name").asText()));
        registries.get(1).forEach(d -> deviceAreas.put(d.path("id").asText(), d.path("area_id").asText("")));
        registries.get(2).forEach(e -> {
            if (e.path("disabled_by").isNull() || e.path("disabled_by").isMissingNode())
                entities.put(e.path("entity_id").asText(), e);
        });
        JsonNode states = IntegrationSupport.request(BASE + "/api/states", null, Map.of("Authorization", "Bearer " + token), 20);
        if (!states.isArray()) throw new IOException("Invalid Home Assistant states");
        ArrayNode found = IntegrationSupport.JSON.createArrayNode();
        for (JsonNode state : states) {
            String id = state.path("entity_id").asText();
            JsonNode entry = entities.getOrDefault(id, IntegrationSupport.JSON.createObjectNode());
            String areaId = first(value(entry, "area_id", ""), deviceAreas.get(entry.path("device_id").asText()));
            String area = areas.getOrDefault(areaId, "");
            String name = first(value(state.path("attributes"), "friendly_name", ""), id);
            if (!query.isEmpty() && !Seerr.normalize(id + " " + name + " " + area).contains(Seerr.normalize(query)))
                continue;
            ObjectNode row = found.addObject().put("name", name).put("area", area);
            row.set("state", state.path("state"));
        }
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        result.set("entities", found);
        return result;
    }
}
