package com.bateacapitalassistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

final class Commands {
    static final Set<String> SUPPORTED = Set.of("/nas", "/disk", "/transcoding", "/seerr", "/downloads",
            "/music", "/home", "/shows", "/films", "/qbittorrent", "/refresh");
    static final String HELP = """
            Commands
            
            /shows status — episode downloads and import issues
            /shows search "Show Title" season 6 — search and grab missing episodes
            /films [status] — film downloads and import issues
            /qbittorrent [status] — torrent states and speeds
            /refresh — update Telegram command menu
            /nas [status]
            /disk activity
            /transcoding [status]
            /seerr help
            /music downloads — Downtify music queue and progress
            /music search "Title or artist"
            /music request "Song title" "Artist"
            /music releases "Artist"
            /home status ["Area or device name"]
            
            Requests submit only on a unique validated match. Ambiguous matches require clarification. Album downloads and direct home controls are not supported.""";
    private static final String SEERR_HELP = """
            Seerr commands
            /seerr status
            /seerr search "Film Name"
            /seerr request "Film Name" 2016
            
            Year is optional. Requests submit immediately only if the title resolves uniquely; ambiguous titles return choices. Film requests only; manage show requests and seasons in Seerr.""";

    interface Refresh {
        void run() throws IOException, InterruptedException;
    }

    private final Refresh refresh;

    Commands(Refresh refresh) {
        this.refresh = refresh;
    }

    static List<String> split(String text) {
        var parts = new ArrayList<String>();
        var word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && quote != '\'') {
                if (i + 1 == text.length()) throw new IllegalArgumentException();
                char next = text.charAt(++i);
                if (quote == '"' && next != '"' && next != '\\') word.append(ch);
                word.append(next);
                started = true;
            } else if (quote != 0) {
                if (ch == quote) quote = 0;
                else word.append(ch);
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
                started = true;
            } else if (" \t\r\n".indexOf(ch) >= 0) {
                if (started) parts.add(word.toString());
                word.setLength(0);
                started = false;
            } else {
                word.append(ch);
                started = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException();
        if (started) parts.add(word.toString());
        return parts;
    }

    static String limit(String value, int count) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(count, Bridge.length(value))));
    }

    static String value(JsonNode node, String field, String fallback) {
        return node.path(field).asText(fallback);
    }

    static String first(String... choices) {
        for (String choice : choices) if (choice != null && !choice.isEmpty()) return choice;
        return "";
    }

    static List<JsonNode> rows(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).toList();
    }

    static String join(JsonNode node, String delimiter) {
        return String.join(delimiter, rows(node).stream().map(JsonNode::asText).toList());
    }

    static String counts(JsonNode node, String delimiter, boolean sorted) {
        Map<String, String> counts = sorted ? new TreeMap<>() : new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asText()));
        return String.join(delimiter, counts.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
    }

    static String format(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    static String size(JsonNode number) {
        double value = number.asDouble();
        for (String unit : List.of("B", "KiB", "MiB", "GiB", "TiB")) {
            if (value < 1024 || unit.equals("TiB")) return format("%.1f %s", value, unit);
            value /= 1024;
        }
        throw new AssertionError();
    }

    static JsonNode tool(String name, Object args) throws IOException, InterruptedException {
        var reply = Bridge.request("http://127.0.0.1:4097/mcp", Map.of(
                        "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", name, "arguments", args)),
                Map.of("Authorization", "Bearer " + Bridge.secret("nas-tools-token")), 45);
        var result = reply.path("result");
        if (reply.hasNonNull("error") || result.path("isError").asBoolean())
            throw new IllegalStateException("Status service failed");
        for (var part : result.path("content"))
            if (part.path("type").asText().equals("text")) return Bridge.JSON.readTree(part.required("text").asText());
        throw new IllegalStateException("Missing status result");
    }

    String run(String command, String input) throws IOException, InterruptedException {
        List<String> parts;
        try {
            parts = split(input);
        } catch (IllegalArgumentException e) {
            return "Unmatched quotation marks.\n\n" + (command.equals("/seerr") ? SEERR_HELP : HELP);
        }
        String action = parts.size() > 1 ? parts.get(1).toLowerCase(Locale.ROOT) : "status";
        boolean status = action.equals("status") && parts.size() <= 2;
        return switch (command) {
            case "/refresh" -> {
                if (parts.size() != 1) yield HELP;
                refresh.run();
                yield "Telegram command menu refreshed.\n\n" + HELP;
            }
            case "/shows" -> status ? MediaServices.queue(true)
                    : action.equals("help") ? HELP : MediaServices.searchSeason(parts);
            case "/films" -> status ? MediaServices.queue(false) : HELP;
            case "/qbittorrent" -> status ? MediaServices.torrents() : HELP;
            case "/transcoding" -> status ? Status.transcoding() : HELP;
            case "/downloads" -> status ? Status.downloads() : HELP;
            case "/nas" -> status ? Status.nas() : HELP;
            case "/disk" -> action.equals("help") ? HELP : action.equals("activity") && parts.size() == 2
                    ? Status.disk() : "Use /disk activity for a two-second physical-disk sample.";
            case "/home" -> action.equals("status") && parts.size() <= 3
                    ? Status.home(parts.size() == 3 ? parts.get(2) : "") : HELP;
            case "/music" -> music(action, parts);
            case "/seerr" -> status ? Status.seerr() : seerr(action, parts);
            default -> HELP;
        };
    }

    static String recording(JsonNode row) {
        String album = row.path("album_name").asText("");
        return "• " + value(row, "name", "None") + " — " + join(row.path("artists"), ", ")
                + (album.isEmpty() ? "" : " (" + album + ")");
    }

    private static String music(String action, List<String> parts) throws IOException, InterruptedException {
        if (Set.of("downloads", "status").contains(action) && parts.size() <= 2) return Status.downloads();
        if (action.equals("search") && parts.size() == 3) {
            var results = rows(tool("music_search", Map.of("query", parts.get(2))).path("results"));
            return "Song matches\n" + (results.isEmpty() ? "No matches."
                    : String.join("\n", results.stream().map(Commands::recording).toList()));
        }
        if (action.equals("releases") && parts.size() == 3) {
            var data = tool("music_artist_releases", Map.of("artist_name", parts.get(2), "limit", 10));
            var lines = new ArrayList<String>();
            if (data.path("needs_clarification").asBoolean()) {
                lines.add("Artist needs clarification; nothing downloaded.");
                for (var row : data.path("choices"))
                    lines.add("• " + value(row, "name", "None") + " — "
                            + first(join(row.path("example_albums"), ", "), "No example albums returned"));
                lines.add("Select the exact artist in Downtify.");
            } else {
                lines.add(value(data, "artist", parts.get(2)) + " — latest catalog releases");
                for (var row : data.path("items"))
                    lines.add("• " + value(row, "name", "None") + " — "
                            + first(value(row, "release_date", ""), value(row, "year", ""), "Date unknown")
                            + " (" + first(value(row, "release_type", ""), "release") + ")");
                lines.add("Catalog dates, not a comparison against your local library.");
            }
            return String.join("\n", lines);
        }
        if (action.equals("request") && parts.size() == 4) {
            var data = tool("music_request_song", Map.of("title", parts.get(2), "artist", parts.get(3), "submit", true));
            if (data.path("needs_clarification").asBoolean()) {
                var lines = new ArrayList<String>();
                lines.add("No download submitted. Choose a recording/version:");
                data.path("choices").forEach(r -> lines.add(recording(r)));
                lines.add("Select the exact recording in Downtify.");
                return String.join("\n", lines);
            }
            if (data.path("submitted").asBoolean())
                return "Submitted to Downtify: " + value(data, "title", parts.get(2))
                        + "\nUse /music downloads for progress. Not necessarily completed yet.";
            return reason(data, "No new download submitted.");
        }
        return HELP;
    }

    static String reason(JsonNode data, String fallback) {
        return first(value(data, "reason", ""), value(data, "note", ""), fallback);
    }

    private static String filmLabel(JsonNode row) {
        String date = limit(value(row, "date", ""), 4);
        return "• " + first(value(row, "title", ""), "Title unavailable") + (date.isEmpty() ? "" : " (" + date + ")");
    }

    private static String seerr(String action, List<String> parts) throws IOException, InterruptedException {
        if (!Set.of("search", "request").contains(action) || parts.size() < 3) return SEERR_HELP;
        var words = new ArrayList<>(parts.subList(2, parts.size()));
        boolean request = action.equals("request");
        Integer year = null;
        if (request && words.size() >= 2 && Set.of("-", "—").contains(words.get(words.size() - 2))) {
            String type = words.getLast().toLowerCase(Locale.ROOT);
            if (type.equals("tv")) return """
                    That is a TV show. /seerr request currently submits films only.
                    For an existing show, search missing episodes with:
                    /shows search "Slow Horses" season 6
                    This can grab downloads. Remove/blocklist suspicious releases first.""";
            if (type.equals("movie")) words.subList(words.size() - 2, words.size()).clear();
        }
        if (request && words.size() > 1 && words.getLast().matches("\\d{4}|\\(\\d{4}\\)"))
            year = Integer.valueOf(words.removeLast().replace("(", "").replace(")", ""));
        String title = String.join(" ", words).strip();
        if (request && year == null) {
            var match = Pattern.compile("(.+?)\\s+\\((\\d{4})\\)").matcher(title);
            if (match.matches()) {
                title = match.group(1).strip();
                year = Integer.valueOf(match.group(2));
            }
        }
        if (title.isEmpty() || Bridge.length(title) > 200)
            return "Use a title between 1 and 200 characters.\n\n" + SEERR_HELP;
        var lines = new ArrayList<String>();
        if (!request) {
            var results = tool("seerr_search", Map.of("query", title)).path("results");
            if (results.isEmpty()) return "No matching films or shows found.";
            lines.add("Seerr matches");
            for (var row : results) lines.add(filmLabel(row) + " — " + value(row, "type", "unknown"));
            lines.add("\nFilms: /seerr request \"Exact Title\" year\n"
                    + "TV requests: use Seerr. Existing TV season search: /shows search \"Show Title\" season 6 (can grab downloads).");
            return String.join("\n", lines);
        }
        var args = Bridge.JSON.createObjectNode().put("title", title).put("media_type", "movie").put("submit", true);
        args.put("year", year);
        var result = tool("seerr_request_title", args);
        if (result.path("needs_clarification").asBoolean()) {
            var choices = rows(result.path("choices"));
            var films = choices.stream().filter(r -> r.path("type").asText().equals("movie")).toList();
            if (films.isEmpty()) {
                if (choices.stream().anyMatch(r -> r.path("type").asText().equals("tv")))
                    return "This matches a TV show, not a film. No request submitted.\n"
                            + "To search again for an existing monitored season, use:\n/shows search \"" + title
                            + "\" season 6\nReplace 6 with the season you want. This can grab downloads; blocklist suspicious releases first.";
                return "No request submitted: no matching films were returned.\nTry /seerr search \"" + title
                        + "\"\nThen /seerr request \"Exact Title\" year";
            }
            lines.add("No request submitted. Please choose the exact film title and year:");
            films.forEach(row -> lines.add(filmLabel(row)));
            lines.add("\n/seerr request \"Exact Title\" year\nIf title and year still match multiple films, use Seerr directly to select the exact one.");
            return String.join("\n", lines);
        }
        if (result.path("submitted").asBoolean()) {
            String status = Map.of(1, "pending approval", 2, "approved", 3, "declined", 4, "failed", 5, "completed")
                    .getOrDefault(result.path("request_status").asInt(), "accepted");
            return "Seerr request: " + value(result, "title", title) + "\nStatus: " + status
                    + "\nRequest ID: " + value(result, "request_id", "None") + "\nSubmission does not mean the download is complete.";
        }
        return reason(result, "No new request submitted.");
    }
}
