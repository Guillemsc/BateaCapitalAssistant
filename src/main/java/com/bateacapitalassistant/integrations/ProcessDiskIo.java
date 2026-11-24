package com.bateacapitalassistant.integrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProcessDiskIo {
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(2);
    private static final int MAX_PROCESSES = 10;

    private ProcessDiskIo() {
    }

    record Counters(String name, String startTime, long readBytes, long writeBytes) {
    }

    record Snapshot(Map<String, Counters> processes, int inaccessible) {
    }

    record Rate(String pid, String name, double readBytes, double writeBytes) {
    }

    public static List<String> sample(Path proc) throws InterruptedException {
        try {
            long start = System.nanoTime();
            Snapshot before = read(proc);
            Thread.sleep(SAMPLE_INTERVAL);
            Snapshot after = read(proc);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            return format(before, after, seconds);
        } catch (IOException e) {
            return List.of("\nProcess disk I/O unavailable. Mount the host /proc at /host-proc.");
        }
    }

    static Snapshot read(Path proc) throws IOException {
        Map<String, Counters> processes = new HashMap<>();
        int inaccessible = 0;
        try (var entries = Files.list(proc)) {
            for (Path entry : entries.filter(p -> p.getFileName().toString().matches("[0-9]+")).toList()) {
                try {
                    String stat = Files.readString(entry.resolve("stat"));
                    int end = stat.lastIndexOf(')');
                    if (end < 0) continue;
                    String[] fields = stat.substring(end + 1).trim().split("\\s+");
                    if (fields.length < 20) continue;
                    String name = stat.substring(stat.indexOf('(') + 1, end);
                    long read = -1;
                    long write = -1;
                    for (String line : Files.readAllLines(entry.resolve("io"))) {
                        if (line.startsWith("read_bytes:")) read = Long.parseLong(line.substring(11).trim());
                        if (line.startsWith("write_bytes:")) write = Long.parseLong(line.substring(12).trim());
                    }
                    if (read >= 0 && write >= 0)
                        processes.put(entry.getFileName().toString(), new Counters(name, fields[19], read, write));
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // Processes can exit during a sample.
                } catch (IOException | RuntimeException e) {
                    inaccessible++;
                }
            }
        }
        return new Snapshot(Map.copyOf(processes), inaccessible);
    }

    static List<String> format(Snapshot before, Snapshot after, double seconds) {
        var rates = new ArrayList<Rate>();
        after.processes().forEach((pid, current) -> {
            Counters previous = before.processes().get(pid);
            if (previous == null || !previous.startTime().equals(current.startTime())) return;
            long read = current.readBytes() - previous.readBytes();
            long write = current.writeBytes() - previous.writeBytes();
            if (read < 0 || write < 0 || (read == 0 && write == 0)) return;
            rates.add(new Rate(pid, current.name(), read / seconds, write / seconds));
        });
        rates.sort(Comparator.comparingDouble((Rate rate) -> rate.readBytes() + rate.writeBytes()).reversed());
        var lines = new ArrayList<String>();
        lines.add("\nProcesses by disk I/O — two-second sample");
        for (Rate rate : rates.stream().limit(MAX_PROCESSES).toList()) {
            String name = IntegrationSupport.limit(rate.name().replaceAll("[\\p{Cntrl}]", " "), 100);
            lines.add("• " + name + " (PID " + rate.pid() + "): read "
                    + IntegrationSupport.size(IntegrationSupport.JSON.getNodeFactory().numberNode(rate.readBytes())) + "/s; write "
                    + IntegrationSupport.size(IntegrationSupport.JSON.getNodeFactory().numberNode(rate.writeBytes())) + "/s");
        }
        if (rates.isEmpty()) lines.add("No readable processes reported storage I/O during the sample.");
        if (before.inaccessible() > 0 || after.inaccessible() > 0)
            lines.add("Some process counters were inaccessible; results are incomplete.");
        return lines;
    }
}
