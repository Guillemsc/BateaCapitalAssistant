package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

public final class Backups {
    public static final Path SNAPSHOT = Path.of("/secrets/backup-status.json");

    private Backups() {
    }

    public record Report(String text, Map<String, String> problems) {
    }

    public static Report read() throws IOException {
        if (!Files.exists(SNAPSHOT))
            return new Report("Backup monitoring is not configured. No backup-status.json snapshot is mounted.", Map.of("monitoring", "Backup status snapshot is missing; current results are unknown."));
        return parse(JSON.readTree(Files.readString(SNAPSHOT)), Instant.now());
    }

    static Report parse(JsonNode data, Instant now) throws IOException {
        if (data == null || !data.path("tasks").isArray()) throw new IOException("Invalid backup snapshot");
        Instant collected;
        try {
            collected = Instant.parse(data.required("collected_at").asText());
        } catch (RuntimeException e) {
            throw new IOException("Invalid backup snapshot timestamp", e);
        }
        var problems = new LinkedHashMap<String, String>();
        var lines = new ArrayList<String>();
        lines.add("Backup status — snapshot collected " + collected);
        Duration age = Duration.between(collected, now);
        if (age.compareTo(Duration.ofHours(2)) > 0 || age.isNegative() && age.abs().compareTo(Duration.ofMinutes(5)) > 0) {
            String message = "Backup monitoring data is stale or has an invalid clock. Current backup results are unknown.";
            return new Report(message, Map.of("monitoring", message));
        }
        int index = 0;
        for (JsonNode task : data.path("tasks")) {
            String name = limit(task.path("name").asText("Backup task").replaceAll("[\\p{Cntrl}]", " "), 100);
            String result = task.path("last_bkp_result").asText("unknown").toLowerCase(Locale.ROOT);
            String label = switch (result) {
                case "true", "success", "successful", "succeeded" -> "last result successful";
                case "false", "failed", "failure", "error" -> "last backup failed";
                default -> "last result unrecognized (" + limit(result.replaceAll("[\\p{Cntrl}]", " "), 60) + ")";
            };
            String key = task.path("task_id").isIntegralNumber() ? "task-" + task.path("task_id").asText() : "task-" + index;
            if (!Set.of("true", "success", "successful", "succeeded").contains(result))
                problems.put(key, name + " — " + label);
            lines.add("• " + name + " — " + label);
            String state = task.path("state").asText("");
            if (!state.isBlank()) lines.add("  State: " + limit(state.replaceAll("[\\p{Cntrl}]", " "), 60));
            index++;
        }
        if (index == 0) lines.add("No backup jobs were returned by the collector.");
        return new Report(String.join("\n", lines), Map.copyOf(problems));
    }
}
