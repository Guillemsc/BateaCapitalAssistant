package com.bateacapitalassistant;

import com.bateacapitalassistant.integrations.Backups;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class BackupWatchdog implements Runnable {
    private final Path path;
    private final Set<Long> users;
    private final DiskWatchdog.Sender sender;
    private Map<String, String> previous = Map.of();
    private final ArrayNode pending = Bridge.JSON.createArrayNode();

    BackupWatchdog(Path path, Set<Long> users, DiskWatchdog.Sender sender) {
        this.path = path;
        this.users = Set.copyOf(users);
        this.sender = sender;
    }

    void load() throws IOException {
        if (!Files.exists(path)) return;
        JsonNode data = Bridge.JSON.readTree(Files.readString(path));
        if (data == null || !data.path("problems").isObject() || !data.path("pending").isArray())
            throw new IOException("Invalid backup alert state");
        Map<String, String> problems = new HashMap<>();
        data.path("problems").fields().forEachRemaining(e -> problems.put(e.getKey(), e.getValue().asText()));
        previous = Map.copyOf(problems);
        for (JsonNode entry : data.path("pending")) {
            if (!entry.path("user").isIntegralNumber() || !entry.path("text").isTextual())
                throw new IOException("Invalid backup notification");
            if (users.contains(entry.path("user").longValue())) pending.add(entry.deepCopy());
        }
    }

    private void save() throws IOException {
        var state = Bridge.JSON.createObjectNode();
        state.set("problems", Bridge.JSON.valueToTree(previous));
        state.set("pending", pending);
        Bridge.save(path, state);
    }

    private void enqueue(String text) {
        for (long user : users) pending.addObject().put("user", user).put("text", text);
    }

    void observe(Backups.Report report) throws IOException {
        Map<String, String> next = report.problems();
        if (next.equals(previous)) return;
        next.forEach((key, message) -> {
            if (!message.equals(previous.get(key))) enqueue("⚠️ Backup alert\n" + message);
        });
        previous.forEach((key, message) -> {
            if (!next.containsKey(key))
                enqueue("✅ Backup status updated\nPreviously reported issue cleared: " + message + "\nUse /backups for current results.");
        });
        previous = next;
        save();
    }

    void deliver() throws IOException, InterruptedException {
        while (!pending.isEmpty()) {
            JsonNode entry = pending.get(0);
            sender.send(entry.path("user").longValue(), entry.path("text").asText());
            pending.remove(0);
            try {
                save();
            } catch (IOException e) {
                pending.insert(0, entry);
                throw e;
            }
        }
    }

    @Override
    public void run() {
        boolean started = Files.exists(path);
        try {
            load();
        } catch (IOException | RuntimeException e) {
            System.err.println("Backup alert state unavailable; monitoring disabled");
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                deliver();
                if (started || Files.exists(Backups.SNAPSHOT)) {
                    started = true;
                    Backups.Report report;
                    try {
                        report = Backups.read();
                    } catch (IOException | RuntimeException e) {
                        report = new Backups.Report("Backup status unavailable.", Map.of("monitoring", "Backup status cannot be read; current results are unknown."));
                    }
                    observe(report);
                    deliver();
                }
            } catch (IOException | RuntimeException e) {
                previous = Map.of();
                pending.removeAll();
                try {
                    load();
                } catch (IOException | RuntimeException stateError) {
                    System.err.println("Backup alert state unavailable; monitoring disabled");
                    return;
                }
                System.err.println("Backup alerts unavailable; retrying");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
