package com.bateacapitalassistant;

import com.bateacapitalassistant.integrations.NasMetrics;
import com.bateacapitalassistant.integrations.ProcessDiskIo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class DiskWatchdog implements Runnable {
    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final double ALERT_THRESHOLD = 80;
    private static final double RECOVERY_THRESHOLD = 60;
    private static final double REQUIRED_SECONDS = 60;
    private static final double PROCESS_SAMPLE_AFTER_SECONDS = 40;
    private static final double MAX_SAMPLE_GAP = 25;
    private static final Path PROC = Path.of("/host-proc");

    interface Sender {
        void send(long user, String message) throws IOException, InterruptedException;
    }

    private final Path statePath;
    private final Set<Long> users;
    private final Sender sender;
    private final Map<String, Incident> incidents = new TreeMap<>();
    private final ArrayNode pending = Bridge.JSON.createArrayNode();

    static final class Incident {
        boolean active;
        double highSeconds;
        double lowSeconds;

        boolean needsProcessSample() {
            return !active && highSeconds >= PROCESS_SAMPLE_AFTER_SECONDS;
        }

        String observe(double busy, double seconds) {
            if (seconds <= 0 || seconds > MAX_SAMPLE_GAP) {
                highSeconds = 0;
                lowSeconds = 0;
                return null;
            }
            if (!active) {
                highSeconds = busy > ALERT_THRESHOLD ? highSeconds + seconds : 0;
                if (highSeconds >= REQUIRED_SECONDS) {
                    active = true;
                    highSeconds = 0;
                    return "alert";
                }
            } else {
                lowSeconds = busy < RECOVERY_THRESHOLD ? lowSeconds + seconds : 0;
                if (lowSeconds >= REQUIRED_SECONDS) {
                    active = false;
                    lowSeconds = 0;
                    return "recovery";
                }
            }
            return null;
        }
    }

    DiskWatchdog(Path statePath, Set<Long> users, Sender sender) {
        this.statePath = statePath;
        this.users = Set.copyOf(users);
        this.sender = sender;
    }

    void load() throws IOException {
        JsonNode state;
        try {
            try (var input = Files.newInputStream(statePath)) {
                state = Bridge.JSON.readTree(input);
            }
        } catch (NoSuchFileException e) {
            return;
        }
        if (state == null || !state.isObject() || !state.path("active").isArray() || !state.path("pending").isArray())
            throw new IOException("Invalid disk watchdog state");
        for (JsonNode device : state.path("active")) {
            if (!device.isTextual() || !device.asText().matches("[a-zA-Z0-9]+"))
                throw new IOException("Invalid disk watchdog device");
            incidents.computeIfAbsent(device.asText(), ignored -> new Incident()).active = true;
        }
        for (JsonNode entry : state.path("pending")) {
            if (!entry.path("user").isIntegralNumber() || !entry.path("text").isTextual())
                throw new IOException("Invalid disk watchdog notification");
            if (users.contains(entry.path("user").longValue())) pending.add(entry.deepCopy());
        }
    }

    void save() throws IOException {
        ObjectNode state = Bridge.JSON.createObjectNode();
        ArrayNode active = state.putArray("active");
        incidents.forEach((device, incident) -> {
            if (incident.active) active.add(device);
        });
        state.set("pending", pending);
        Bridge.save(statePath, state);
    }

    void deliver() throws IOException, InterruptedException {
        while (!pending.isEmpty()) {
            JsonNode notification = pending.get(0);
            sender.send(notification.path("user").longValue(), notification.path("text").asText());
            pending.remove(0);
            try {
                save();
            } catch (IOException e) {
                pending.insert(0, notification);
                throw e;
            }
        }
    }

    void enqueue(String text) {
        for (long user : users) pending.addObject().put("user", user).put("text", text);
    }

    @Override
    public void run() {
        try {
            load();
        } catch (IOException | RuntimeException e) {
            System.err.println("Disk watchdog state unavailable; alerts disabled");
            return;
        }

        Map<String, NasMetrics.Disk> previous = null;
        long previousTime = 0;
        ProcessDiskIo.Snapshot processStart = null;
        long processStartTime = 0;
        boolean processUnavailable = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!pending.isEmpty()) {
                    try {
                        deliver();
                    } catch (IOException e) {
                        System.err.println("Disk watchdog notification unavailable; retrying");
                    }
                }
                long now = System.nanoTime();
                Map<String, NasMetrics.Disk> current = NasMetrics.disks();
                if (previous != null) {
                    double seconds = (now - previousTime) / 1e9;
                    Map<String, Double> usage = NasMetrics.busyPercent(previous, current, seconds);
                    for (var entry : incidents.entrySet()) {
                        if (!usage.containsKey(entry.getKey())) {
                            entry.getValue().highSeconds = 0;
                            entry.getValue().lowSeconds = 0;
                        }
                    }
                    List<String> alerted = new ArrayList<>();
                    boolean changed = false;
                    for (var entry : usage.entrySet()) {
                        Incident incident = incidents.computeIfAbsent(entry.getKey(), ignored -> new Incident());
                        String event = incident.observe(entry.getValue(), seconds);
                        if ("alert".equals(event)) {
                            alerted.add(entry.getKey());
                            changed = true;
                        } else if ("recovery".equals(event)) {
                            enqueue("✅ Disk activity recovered\n" + entry.getKey() + " has stayed below 60% busy for one minute.");
                            changed = true;
                        }
                    }
                    if (processStart == null && !processUnavailable
                            && incidents.values().stream().anyMatch(Incident::needsProcessSample)) {
                        try {
                            processStart = ProcessDiskIo.read(PROC);
                            processStartTime = System.nanoTime();
                        } catch (IOException e) {
                            processUnavailable = true;
                        }
                    }
                    if (!alerted.isEmpty()) {
                        List<String> processes;
                        if (processStart == null) {
                            processes = List.of("\nProcess I/O unavailable; host process counters could not be read.");
                        } else {
                            try {
                                ProcessDiskIo.Snapshot end = ProcessDiskIo.read(PROC);
                                double elapsed = (System.nanoTime() - processStartTime) / 1e9;
                                processes = elapsed > 0 ? ProcessDiskIo.format(processStart, end, elapsed)
                                        : List.of("\nProcess I/O unavailable for this sample.");
                            } catch (IOException e) {
                                processes = List.of("\nProcess I/O unavailable; host process counters could not be read.");
                            }
                        }
                        String details = String.join("\n", processes)
                                + "\nProcess rates cover all disks and do not prove which disk a process used.";
                        for (String device : alerted)
                            enqueue(String.format(Locale.ROOT, "⚠️ Disk activity high\n%s has been over 80%% busy for one minute.\nCurrent activity: %.1f%% busy\n%s",
                                    device, usage.get(device), details));
                    }
                    if (incidents.values().stream().noneMatch(Incident::needsProcessSample)) {
                        processStart = null;
                        processUnavailable = false;
                    }
                    if (changed) save();
                }
                previous = current;
                previousTime = now;
            } catch (IOException | RuntimeException e) {
                previous = null;
                processStart = null;
                processUnavailable = false;
                incidents.clear();
                pending.removeAll();
                try {
                    load();
                } catch (IOException | RuntimeException stateError) {
                    System.err.println("Disk watchdog state unavailable; alerts disabled");
                    return;
                }
                System.err.println("Disk watchdog unavailable; retrying");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
