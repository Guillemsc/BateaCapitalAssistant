package com.bateacapitalassistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.bateacapitalassistant.integrations.IntegrationSupport;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public final class Bridge {
    static final ObjectMapper JSON = IntegrationSupport.JSON;
    static final Path SECRETS = Path.of("/secrets");
    private static final int MESSAGE_CHUNK_LENGTH = 4_000;
    private static final int MAX_INPUT_LENGTH = 8_000;
    private static final int POLL_TIMEOUT_SECONDS = 25;
    private static final Duration POLL_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Path STATE = Path.of("/state/bridge.json");
    static final Set<String> REFRESHABLE = Set.of("/shows", "/films", "/qbittorrent", "/transcoding");
    static final Map<String, String> ALIASES = Map.of("/sonarr", "/shows", "/sonar", "/shows",
            "/radarr", "/films", "/tdarr", "/transcoding", "/seer", "/seerr");
    static final List<Map<String, String>> MENU = List.of(
            menu("shows", "Show downloads and import issues (Sonarr)"),
            menu("films", "Film downloads and import issues (Radarr)"),
            menu("qbittorrent", "Torrent states, speeds and blocked downloads"),
            menu("transcoding", "Transcoding workers and queues (Tdarr)"),
            menu("nas", "NAS status"), menu("disk", "Disk activity: /disk activity"),
            menu("seerr", "Media requests and search"), menu("music", "Music search and downloads"),
            menu("home", "Home Assistant status"), menu("refresh", "Refresh Telegram command menu"),
            menu("help", "Show commands and examples"));

    private final String token;
    private final Set<Long> allowed;
    private final Commands commands = new Commands(this::refresh);

    Bridge() throws IOException {
        token = secret("telegram-bot-token");
        if (token.isBlank()) throw new IOException("Telegram bot token is empty");
        JsonNode users = readJsonObject(SECRETS.resolve("telegram-access.json")).path("allowed_user_ids");
        if (!users.isArray()) throw new IOException("Invalid Telegram allowlist");
        var userIds = new LinkedHashSet<Long>();
        for (JsonNode user : users) {
            if (!user.isIntegralNumber() || !user.canConvertToLong()) throw new IOException("Invalid Telegram user ID");
            userIds.add(user.longValue());
        }
        allowed = Collections.unmodifiableSet(userIds);
    }

    static Map<String, String> menu(String name, String description) {
        return Map.of("command", name, "description", description);
    }

    private static ObjectNode readJsonObject(Path path) throws IOException {
        JsonNode value = JSON.readTree(Files.readString(path));
        if (value instanceof ObjectNode object) return object;
        throw new IOException("Expected a JSON object in " + path.getFileName());
    }

    static void save(Path path, JsonNode value) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), ".bridge-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            JSON.writeValue(tmp.toFile(), value);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    JsonNode telegram(String method, Object body) throws IOException, InterruptedException {
        var result = request("https://api.telegram.org/bot" + token + "/" + method, body, Map.of(), 40);
        if (!result.path("ok").asBoolean()) throw new IOException("Telegram request failed");
        return result.path("result");
    }

    void refresh() throws IOException, InterruptedException {
        for (long user : allowed)
            telegram("setMyCommands",
                    Map.of("scope", Map.of("type", "chat", "chat_id", user), "commands", MENU));
    }

    static Map<String, Object> keyboard(String command) {
        return Map.of("inline_keyboard", List.of(List.of(Map.of("text", "↻ Refresh", "callback_data", command))));
    }

    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    void send(long chat, String text) throws IOException, InterruptedException {
        for (int start = 0; start < text.length(); ) {
            int end = text.offsetByCodePoints(start, Math.min(MESSAGE_CHUNK_LENGTH, text.codePointCount(start, text.length())));
            telegram("sendMessage", Map.of("chat_id", chat, "text", text.substring(start, end)));
            start = end;
        }
    }

    void sendStatus(long chat, String text, String command) throws IOException, InterruptedException {
        if (length(text) > MESSAGE_CHUNK_LENGTH) {
            send(chat, text);
            text = "Refresh this status:";
        }
        telegram("sendMessage", Map.of("chat_id", chat, "text", text, "reply_markup", keyboard(command)));
    }

    boolean admitted(JsonNode sender, JsonNode chat) {
        var user = sender.path("id");
        var id = chat.path("id");
        return user.isIntegralNumber() && user.canConvertToLong() && allowed.contains(user.longValue())
                && id.isIntegralNumber() && id.canConvertToLong() && id.longValue() == user.longValue()
                && chat.path("type").asText().equals("private");
    }

    void handle(JsonNode update) throws IOException, InterruptedException {
        var callback = update.path("callback_query");
        if (callback.isObject()) {
            handleCallback(callback);
            return;
        }
        var message = update.path("message");
        var chat = message.path("chat");
        if (!admitted(message.path("from"), chat)) return;
        String input = message.path("text").asText("").strip();
        if (input.isEmpty()) return;
        long id = chat.path("id").longValue();
        String[] words = WHITESPACE.split(input);
        String command = words[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        command = ALIASES.getOrDefault(command, command);
        if (Commands.SUPPORTED.contains(command)) {
            String result;
            try {
                result = commands.run(command, input);
            } catch (IOException | RuntimeException e) {
                System.err.println("Direct status request failed");
                boolean submission = Set.of("/seerr", "/music").contains(command)
                        && words.length > 1 && words[1].equalsIgnoreCase("request");
                send(id, submission
                        ? "Could not confirm the request outcome. Check /seerr status or /music downloads before retrying to avoid duplicates."
                        : "Could not read that service’s status. Please try again or check the service.");
                return;
            }
            if (REFRESHABLE.contains(command) && (words.length == 1
                    || (words.length == 2 && words[1].equalsIgnoreCase("status")))) sendStatus(id, result, command);
            else send(id, result);
        } else if (command.equals("/start") || command.equals("/help")) send(id, Commands.HELP);
        else if (input.equals("/new")) send(id, "No reset is needed. Use /help for commands.");
        else if (length(input) > MAX_INPUT_LENGTH) send(id, "Please keep messages below 8,000 characters.");
        else send(id, "Send /help for available commands.");
    }

    private void handleCallback(JsonNode callback) throws IOException, InterruptedException {
        var message = callback.path("message");
        var chat = message.path("chat");
        String command = callback.path("data").asText();
        if (!admitted(callback.path("from"), chat) || !REFRESHABLE.contains(command)) return;
        long id = chat.path("id").longValue();
        telegram("answerCallbackQuery", Map.of("callback_query_id", callback.path("id").asText()));
        try {
            String status = commands.run(command, command);
            if (length(status) > MESSAGE_CHUNK_LENGTH) sendStatus(id, status, command);
            else
                telegram("editMessageText", Map.of("chat_id", id, "message_id", message.path("message_id").asLong(),
                        "text", status, "reply_markup", keyboard(command)));
        } catch (IOException | RuntimeException e) {
            System.err.println("Status refresh unchanged or unavailable");
        }
    }

    void run() throws IOException, InterruptedException {
        ObjectNode state;
        try {
            state = readJsonObject(STATE);
        } catch (NoSuchFileException e) {
            state = JSON.createObjectNode().put("offset", 0);
        }
        state.remove(List.of("session", "last_input_at"));
        save(STATE, state);
        System.out.println("Private commands-only Telegram bot started");
        Thread notifications = new Thread(new Decluttarr(allowed, this::send), "decluttarr-notifications");
        notifications.setDaemon(true);
        notifications.start();
        try {
            refresh();
        } catch (IOException | RuntimeException e) {
            System.err.println("Command menu refresh failed; use /refresh to retry");
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var updates = telegram("getUpdates", Map.of("offset", state.path("offset").asLong(), "timeout", POLL_TIMEOUT_SECONDS,
                        "allowed_updates", List.of("message", "callback_query")));
                for (var update : updates) {
                    // Commit admission before calling any service that could submit a request.
                    var next = state.deepCopy().put("offset", update.required("update_id").longValue() + 1);
                    save(STATE, next);
                    state = next;
                    try {
                        handle(update);
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Conversation request failed");
                        var message = update.path("message");
                        var chat = message.path("chat");
                        if (admitted(message.path("from"), chat))
                            send(chat.path("id").longValue(), "The command failed. Check service status before repeating a request.");
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Polling temporarily unavailable; retrying");
                Thread.sleep(POLL_RETRY_DELAY);
            }
        }
    }

    public static void main(String[] args) {
        try {
            new Bridge().run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            System.err.println("Bridge startup failed. Check the secrets and state mounts.");
            System.exit(1);
        }
    }
}
