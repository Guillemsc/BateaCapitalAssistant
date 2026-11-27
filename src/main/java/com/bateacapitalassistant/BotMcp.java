package com.bateacapitalassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/** MCP stdio adapter for the bot's existing command dispatcher. */
public final class BotMcp {
    private static final Commands COMMANDS = new Commands(() -> {
        throw new UnsupportedOperationException("Telegram menu refresh is unavailable through MCP");
    });

    private static final Map<String, Object> HOME_CONTROL_TOOL = Map.of(
            "name", "home_control",
            "description", "Control one live Home Assistant light, radiator timer, or explicitly enabled entity after an explicit user request. Discover the entity ID with /home first.",
            "inputSchema", Map.of("type", "object", "required", List.of("entity_id", "action"),
                    "properties", Map.of(
                            "entity_id", Map.of("type", "string"),
                            "action", Map.of("type", "string", "enum", List.of("turn_on", "turn_off", "start", "cancel")),
                            "duration", Map.of("type", "string", "description", "Optional for timer start. HH:MM:SS, up to one hour. Omit for the Home Assistant default."))));

    public static void main(String[] args) throws Exception {
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = input.readLine()) != null;) {
                JsonNode request = null;
                try {
                    request = Bridge.JSON.readTree(line);
                    var response = dispatch(request);
                    if (response == null) continue;
                    System.out.println(Bridge.JSON.writeValueAsString(response));
                } catch (Exception e) {
                    if (request != null && request.has("id")) {
                        var response = Bridge.JSON.createObjectNode().put("jsonrpc", "2.0");
                        response.set("id", request.get("id"));
                        response.set("error", Bridge.JSON.valueToTree(Map.of("code", -32603, "message", "Command failed; check service status before retrying.")));
                        System.out.println(Bridge.JSON.writeValueAsString(response));
                    }
                }
            }
        }
    }

    private static ObjectNode dispatch(JsonNode request) throws Exception {
        if (!request.has("id")) return null;
        ObjectNode response = Bridge.JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        String method = request.path("method").asText();
        switch (method) {
            case "initialize" -> response.set("result", Bridge.JSON.valueToTree(Map.of(
                    "protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "bateacapitalassistant", "version", "1.0.0"))));
            case "ping" -> response.set("result", Bridge.JSON.createObjectNode());
            case "tools/list" -> response.set("result", Bridge.JSON.valueToTree(Map.of("tools", List.of(Map.of(
                    "name", "bot_command", "description", "Run an existing BateaCapitalAssistant command. Use /help to discover commands. Requests may trigger downloads.",
                    "inputSchema", Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")), "required", List.of("command"))), HOME_CONTROL_TOOL))));
            case "tools/call" -> {
                var params = request.path("params");
                String text = params.path("arguments").path("command").asText("").strip();
                String command = text.isEmpty() ? "" : Commands.canonical(text.split("\\s+", 2)[0]);
                String result;
                if (params.path("name").asText().equals("home_control")) {
                    var args = params.path("arguments");
                    try {
                        result = com.bateacapitalassistant.integrations.HomeAssistant.control(
                                args.path("entity_id").asText(""), args.path("action").asText(""),
                                args.path("duration").asText(""));
                    } catch (java.io.IOException e) {
                        result = "Could not confirm the Home Assistant action. Check the entity state before repeating it.";
                    }
                } else if (!params.path("name").asText().equals("bot_command")) result = "Unknown tool.";
                else if (command.equals("/help")) result = Commands.HELP;
                else if (command.equals("/refresh") || !Commands.SUPPORTED.contains(command)) result = "Unsupported command. Use /help.";
                else result = COMMANDS.run(command, text);
                response.set("result", Bridge.JSON.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", result)))));
            }
            default -> response.set("error", Bridge.JSON.valueToTree(Map.of("code", -32601, "message", "Method not found")));
        }
        return response;
    }

    static void startHttp() throws java.io.IOException {
        if (!OpenCode.enabled()) return;
        String token = System.getenv("BATEA_MCP_TOKEN");
        if (token == null || token.isBlank()) throw new java.io.IOException("Missing BATEA_MCP_TOKEN");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 4197), 4);
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                byte[] input = exchange.getRequestBody().readNBytes(16385);
                if (input.length > 16384) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                byte[] output;
                try {
                    var response = dispatch(Bridge.JSON.readTree(input));
                    if (response == null) {
                        exchange.sendResponseHeaders(202, -1);
                        return;
                    }
                    output = Bridge.JSON.writeValueAsBytes(response);
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, output.length);
                exchange.getResponseBody().write(output);
            }
        });
        server.start();
    }
}
