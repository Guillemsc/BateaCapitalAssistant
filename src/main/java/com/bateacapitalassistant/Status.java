package com.bateacapitalassistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

import com.bateacapitalassistant.integrations.*;

import static com.bateacapitalassistant.integrations.IntegrationSupport.*;

final class Status {
    private Status() {
    }

    static String space() throws IOException {
        var volume = java.nio.file.Files.getFileStore(java.nio.file.Path.of("/host-volume1"));
        long total = volume.getTotalSpace(), available = volume.getUsableSpace(), used = total - volume.getUnallocatedSpace();
        return "Volume1 disk space\n"
                + "• Used: " + size(IntegrationSupport.JSON.valueToTree(used)) + " / " + size(IntegrationSupport.JSON.valueToTree(total))
                + (total > 0 ? format(" (%.1f%%)", 100.0 * used / total) : "")
                + "\n• Available: " + size(IntegrationSupport.JSON.valueToTree(available));
    }

    static String queue() throws InterruptedException {
        Map<String, java.util.concurrent.Callable<String>> sources = new LinkedHashMap<>();
        sources.put("Shows", () -> MediaServices.queue(true));
        sources.put("Films", () -> MediaServices.queue(false));
        sources.put("Music", Status::downloads);
        return queue(sources);
    }

    static String queue(Map<String, java.util.concurrent.Callable<String>> sources) throws InterruptedException {
        var lines = new ArrayList<String>();
        lines.add("Media queue — current download and import status");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new LinkedHashMap<String, java.util.concurrent.Future<String>>();
            sources.forEach((name, call) -> futures.put(name, executor.submit(call)));
            for (var entry : futures.entrySet()) {
                try {
                    lines.add("\n" + entry.getValue().get());
                } catch (java.util.concurrent.ExecutionException e) {
                    lines.add("\n" + entry.getKey() + " queue unavailable. Other results are partial.");
                }
            }
        }
        return String.join("\n", lines);
    }

    static String nas() throws IOException, InterruptedException {
        var data = NasMetrics.status();
        var memory = data.required("memory");
        var disk = data.required("volume1");
        long hours = data.path("uptime_seconds").asLong() / 3600;
        return "NAS status\n"
                + "• CPU: " + data.path("cpu_usage_percent").asText() + "% (0.5-second sample)\n"
                + "• Load: " + join(data.path("load_average"), " / ") + "\n"
                + format("• Uptime: %d days, %d hours\n", hours / 24, hours % 24)
                + "• RAM available: " + size(memory.path("available_bytes")) + " / " + size(memory.path("total_bytes")) + "\n"
                + "• Swap used: " + size(memory.path("swap_used_bytes")) + " / " + size(memory.path("swap_total_bytes")) + "\n"
                + "• Volume1: " + disk.path("used_percent").asText() + "% used; " + size(disk.path("available_bytes")) + " available\n\n"
                + "Disk space only—not disk I/O, SMART or RAID health. Swap occupancy alone does not prove current memory pressure.";
    }

    static String disk() throws IOException, InterruptedException {
        var data = NasMetrics.diskActivity();
        var processes = ProcessDiskIo.sample(java.nio.file.Path.of("/host-proc"));
        var lines = new ArrayList<String>();
        lines.add("Disk activity — " + value(data, "sample_seconds", "2") + "-second sample");
        for (var row : data.path("disks"))
            lines.add("• " + row.required("device").asText()
                    + ": read " + size(row.path("read_bytes_per_second")) + "/s; write "
                    + size(row.path("write_bytes_per_second")) + "/s; busy " + row.required("busy_percent").asText() + "%");
        if (data.path("disks").isEmpty()) lines.add("No supported physical-disk counters were returned.");
        lines.addAll(processes);
        lines.add("\nDisk and process rates are separate samples. Process counters cover storage I/O across all devices; cached I/O and kernel activity may not appear.");
        return String.join("\n", lines);
    }

    static String home(String query) throws IOException, InterruptedException {
        var rows = rows(HomeAssistant.status(query).path("entities"));
        if (rows.isEmpty()) return "No matching Home Assistant entities.";
        var lines = new ArrayList<String>();
        lines.add("Home Assistant status");
        for (var row : rows.stream().limit(25).toList()) {
            String area = value(row, "area", "");
            lines.add("• " + row.required("name").asText() + (area.isEmpty() ? "" : " [" + area + "]")
                    + " — " + value(row, "state", "unknown"));
        }
        if (rows.size() > 25)
            lines.add(rows.size() + " matches; showing 25. Specify an area/device to narrow the result.");
        return String.join("\n", lines);
    }

    static String transcoding() throws IOException, InterruptedException {
        var data = Tdarr.status();
        var lines = new ArrayList<String>();
        lines.add("Tdarr status");
        for (var node : data.path("nodes")) {
            var queue = rows(node.path("node_queue_lengths")).stream().filter(JsonNode::isNumber).toList();
            double total = queue.stream().mapToDouble(JsonNode::asDouble).sum();
            String totalText = queue.stream().anyMatch(JsonNode::isFloatingPointNumber)
                    ? Double.toString(total) : Long.toString((long) total);
            lines.add("• " + value(node, "name", "None") + ": " + value(node, "active_worker_count", "0")
                    + " active workers; paused: " + (node.path("paused").asBoolean() ? "yes" : "no")
                    + "; node queue: " + totalText);
            for (var worker : node.path("workers")) {
                String label = first(value(worker, "fileName", ""), value(worker, "file", ""),
                        value(worker, "status", ""), "Active worker");
                String progress = worker.has("percentage") ? value(worker, "percentage", "") : value(worker, "progress", "");
                lines.add("  " + label + (progress.isEmpty() ? "" : " — " + progress + "%"));
            }
        }
        if (data.path("nodes").isEmpty()) lines.add("No nodes reported.");
        lines.add("\nNode queues are not necessarily the complete server backlog.");
        return String.join("\n", lines);
    }

    static String downloads() throws IOException, InterruptedException {
        var data = Music.status();
        var lines = new ArrayList<String>();
        lines.add("Downtify: " + value(data, "queue_state", "unknown") + "; " + value(data, "active", "0") + " active");
        if (!data.path("counts").isEmpty()) lines.add(counts(data.path("counts"), " • ", false));
        for (var job : data.path("jobs")) {
            String artists = join(job.path("artists"), ", ");
            String progress = value(job, "progress", "");
            lines.add("• " + first(value(job, "title", ""), "Title unavailable")
                    + (artists.isEmpty() ? "" : " — " + artists) + " — " + value(job, "status", "unknown")
                    + (progress.isEmpty() ? "" : " (" + progress + "%)"));
        }
        if (data.path("jobs").isEmpty()) lines.add("No download jobs reported.");
        return String.join("\n", lines);
    }

    static String seerr() throws IOException, InterruptedException {
        var data = Seerr.status();
        var counts = data.path("counts");
        var lines = new ArrayList<String>();
        lines.add(format("Seerr: %s requests (%s films, %s shows)", value(counts, "total", "0"),
                value(counts, "movie", "0"), value(counts, "tv", "0")));
        if (counts.path("pending").asInt() != 0) lines.add("Pending approval: " + counts.path("pending").asText());
        var attention = new ArrayList<String>();
        var other = new ArrayList<String>();
        for (var row : data.path("recent")) {
            boolean requestIssue = Set.of(1, 3, 4).contains(row.path("request_status").asInt());
            boolean processing = Set.of(2, 3, 4).contains(row.path("media_status").asInt());
            String status = value(row, "media_status_label", "unknown");
            if (requestIssue) status = value(row, "request_status_label", status) + "; media " + status;
            String year = value(row, "year", "");
            (requestIssue || processing ? attention : other).add("• "
                    + first(value(row, "title", ""), "Title unavailable")
                    + (year.isEmpty() ? "" : " (" + year + ")") + " — " + status);
        }
        if (!attention.isEmpty()) {
            lines.add("\nProcessing / needs attention");
            lines.addAll(attention);
        }
        if (!other.isEmpty()) {
            lines.add("\nOther recent requests");
            lines.addAll(other);
        }
        lines.add("\nLatest ten requests; Seerr processing is not download progress.");
        return String.join("\n", lines);
    }
}
