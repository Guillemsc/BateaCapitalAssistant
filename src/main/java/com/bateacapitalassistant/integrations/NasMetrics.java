package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class NasMetrics {
    private static final Path METRICS = Path.of("/host-metrics");

    private NasMetrics() {
    }

    public record Disk(long read, long write, long busy, long inFlight) {
    }

    public static Map<String, Disk> disks() throws IOException {
        Map<String, Disk> result = new TreeMap<>();
        for (String line : Files.readAllLines(METRICS.resolve("diskstats"))) {
            String[] f = line.strip().split("\\s+");
            if (f.length < 14 || !f[2].matches("(sata[0-9]+|sd[a-zA-Z]+|nvme[0-9]+n[0-9]+)")) continue;
            try {
                result.put(f[2], new Disk(Long.parseLong(f[5]), Long.parseLong(f[9]),
                        Long.parseLong(f[12]), Long.parseLong(f[11])));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid disk counters", e);
            }
        }
        return result;
    }

    public static Map<String, Double> busyPercent(Map<String, Disk> before, Map<String, Disk> after, double seconds) {
        Map<String, Double> result = new TreeMap<>();
        if (seconds <= 0) return result;
        after.forEach((name, current) -> {
            Disk previous = before.get(name);
            if (previous == null || current.busy() < previous.busy()) return;
            result.put(name, Math.min(100, (current.busy() - previous.busy()) / (seconds * 10)));
        });
        return result;
    }

    public static ObjectNode diskActivity() throws IOException, InterruptedException {
        Map<String, Disk> before = disks();
        long start = System.nanoTime();
        Thread.sleep(2000);
        Map<String, Disk> after = disks();
        double seconds = (System.nanoTime() - start) / 1e9;
        ArrayNode rows = IntegrationSupport.JSON.createArrayNode();
        after.forEach((name, current) -> {
            Disk old = before.get(name);
            if (old == null) return;
            long reads = current.read - old.read, writes = current.write - old.write, busy = current.busy - old.busy;
            if (reads < 0 || writes < 0 || busy < 0) return;
            rows.addObject().put("device", name).put("read_bytes_per_second", Math.round(reads * 512 / seconds))
                    .put("write_bytes_per_second", Math.round(writes * 512 / seconds))
                    .put("busy_percent", Math.round(Math.min(100, busy / (seconds * 10)) * 10) / 10.0)
                    .put("in_flight", current.inFlight);
        });
        ObjectNode result = IntegrationSupport.JSON.createObjectNode().put("sample_seconds", Math.round(seconds * 100) / 100.0);
        result.set("disks", rows);
        return result;
    }

    private static long[] cpu() throws IOException {
        String[] f = Files.readAllLines(METRICS.resolve("stat")).getFirst().trim().split("\\s+");
        long total = 0;
        for (int i = 1; i <= 8 && i < f.length; i++) total += Long.parseLong(f[i]);
        return new long[]{total, Long.parseLong(f[4]) + Long.parseLong(f[5])};
    }

    public static ObjectNode status() throws IOException, InterruptedException {
        long[] first = cpu();
        Thread.sleep(500);
        long[] second = cpu();
        long delta = second[0] - first[0];
        Map<String, Long> memory = new HashMap<>();
        for (String line : Files.readAllLines(METRICS.resolve("meminfo"))) {
            String[] pair = line.split(":", 2);
            if (pair.length != 2) continue;
            String[] fields = pair[1].strip().split("\\s+");
            try {
                memory.put(pair[0], Long.parseLong(fields[0]) * 1024);
            } catch (NumberFormatException ignored) {
            }
        }
        var store = Files.getFileStore(Path.of("/host-volume1"));
        long total = store.getTotalSpace(), used = total - store.getUnallocatedSpace();
        ObjectNode result = IntegrationSupport.JSON.createObjectNode();
        if (delta > 0)
            result.put("cpu_usage_percent", Math.round(1000.0 * (delta - (second[1] - first[1])) / delta) / 10.0);
        ArrayNode load = result.putArray("load_average");
        String[] values = Files.readString(METRICS.resolve("loadavg")).strip().split("\\s+");
        for (int i = 0; i < 3; i++) load.add(Double.parseDouble(values[i]));
        result.put("uptime_seconds", (long) Double.parseDouble(Files.readString(METRICS.resolve("uptime")).strip().split("\\s+")[0]));
        ObjectNode mem = result.putObject("memory");
        mem.put("total_bytes", memory.getOrDefault("MemTotal", 0L));
        mem.put("available_bytes", memory.getOrDefault("MemAvailable", 0L));
        mem.put("swap_total_bytes", memory.getOrDefault("SwapTotal", 0L));
        mem.put("swap_used_bytes", memory.getOrDefault("SwapTotal", 0L) - memory.getOrDefault("SwapFree", 0L));
        ObjectNode volume = result.putObject("volume1");
        volume.put("available_bytes", store.getUsableSpace());
        if (total > 0) volume.put("used_percent", Math.round(1000.0 * used / total) / 10.0);
        return result;
    }
}
