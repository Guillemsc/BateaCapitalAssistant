package com.bateacapitalassistant;

import com.bateacapitalassistant.integrations.IntegrationSupport;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

final class Decluttarr implements Runnable {
    private static final int MAX_BATCH_LINES = 1_000;
    private static final int MAX_BATCH_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 65_535;
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(30);
    private static final Path LOG = Path.of("/decluttarr-logs/logs.txt");
    private static final Path CURSOR = Path.of("/state/decluttarr-notifications.json");
    private static final Pattern ACTION = Pattern.compile("\\| INFO\\s+\\| Job '([a-z_]+)' triggered (.+)$");
    private static final Pattern MODE = Pattern.compile("(?m)^\\s+test_run:\\s*(true|false)\\s*$");
    private static final Map<String, String> REASONS = Map.of("remove_metadata_missing", "Metadata unavailable",
            "remove_stalled", "Stalled download", "remove_failed_downloads", "Failed download");

    interface Sender {
        void send(long user, String text) throws IOException, InterruptedException;
    }

    private final Set<Long> users;
    private final Sender sender;

    Decluttarr(Set<Long> users, Sender sender) {
        this.users = users;
        this.sender = sender;
    }

    static String event(String line, Boolean dryRun) {
        var match = ACTION.matcher(line);
        if (!match.find()) return null;
        String action = match.group(2);
        if (List.of("removal:", "queue removal (torrent kept):", "obsolete-tagging:", "a search for ")
                .stream().noneMatch(action::startsWith)) return null;
        String mode = dryRun == null ? "action reported — mode unknown"
                : dryRun ? "DRY RUN — no changes" : "action reported";
        return "Decluttarr — " + mode + "\n" + REASONS.getOrDefault(match.group(1), match.group(1))
                + "\n" + IntegrationSupport.limit(action, 1500)
                + "\n\nLog report, not confirmation of success or a replacement download.";
    }

    private static Boolean mode() {
        try {
            var match = MODE.matcher(Files.readString(Path.of("/decluttarr-config.yaml")));
            return match.find() ? Boolean.valueOf(match.group(1)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private JsonNode batch(JsonNode cursor) throws IOException, InterruptedException {
        var stat = Files.readAttributes(LOG, "unix:dev,ino");
        var identity = Bridge.JSON.createArrayNode().add(((Number) stat.get("dev")).longValue())
                .add(((Number) stat.get("ino")).longValue());
        try (var file = new RandomAccessFile(LOG.toFile(), "r")) {
            long offset = cursor == null ? file.length() : cursor.path("offset").asLong();
            if (cursor != null && (!identity.toString().equals(cursor.path("identity").toString())
                    || offset < 0 || offset > file.length())) offset = 0;
            if (!stat.equals(Files.readAttributes(LOG, "unix:dev,ino")))
                throw new IOException("Log rotated while opening");
            file.seek(offset);
            var input = new BufferedInputStream(java.nio.channels.Channels.newInputStream(file.getChannel()));
            long position = offset;
            var messages = new ArrayList<String>();
            Boolean dryRun = mode();
            var line = new ByteArrayOutputStream();
            boolean oversized = false;
            int lines = 0;
            int bytes = 0;
            while (lines < MAX_BATCH_LINES && bytes < MAX_BATCH_BYTES) {
                int b = input.read();
                if (b == -1) break;
                bytes++;
                position++;
                if (b == '\n') {
                    if (!oversized) {
                        String message = event(line.toString(java.nio.charset.StandardCharsets.UTF_8), dryRun);
                        if (message != null) messages.add(message);
                    }
                    offset = position;
                    lines++;
                    line.reset();
                    oversized = false;
                } else if (line.size() < MAX_LINE_BYTES) {
                    line.write(b);
                } else {
                    oversized = true;
                }
            }
            var next = Bridge.JSON.createObjectNode().put("offset", offset);
            next.set("identity", identity);
            if (!messages.isEmpty()) {
                String text = String.join("\n\n", messages);
                for (long user : users) sender.send(user, text);
            }
            Bridge.save(CURSOR, next);
            return next;
        }
    }

    @Override
    public void run() {
        JsonNode cursor = null;
        try {
            cursor = Bridge.JSON.readTree(CURSOR.toFile());
        } catch (IOException ignored) {
            // A fresh cursor starts at the end of the log.
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                cursor = batch(cursor);
            } catch (FileNotFoundException | NoSuchFileException ignored) {
                // The log mount is optional.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                System.err.println("Decluttarr notifications unavailable; retrying");
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
