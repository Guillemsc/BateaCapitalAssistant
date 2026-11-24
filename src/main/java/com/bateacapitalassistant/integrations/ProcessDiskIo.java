package com.bateacapitalassistant.integrations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProcessDiskIo {
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(2);
    private static final int MAX_PROCESSES = 10;
    private static final int MAX_COMMAND_BYTES = 8192;
    private static final Pattern CONTAINER_ID = Pattern.compile("(?:^|/)(?:docker[-/]|libpod[-/])([0-9a-f]{12,64})(?:[/.]|$)");
    private static final Pattern SCRIPT_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}\\.(?:js|mjs|cjs|ts|tsx|jsx|py|pyz|jar|sh)", Pattern.CASE_INSENSITIVE);

    private ProcessDiskIo() {
    }

    public record Counters(String name, String startTime, long readBytes, long writeBytes) {
    }

    public record Snapshot(Map<String, Counters> processes, int inaccessible) {
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

    public static Snapshot read(Path proc) throws IOException {
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

    public static List<String> format(Snapshot before, Snapshot after, double seconds) {
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
        lines.add("\nProcesses by disk I/O — " + Math.round(seconds) + "-second sample");
        for (Rate rate : rates.stream().limit(MAX_PROCESSES).toList()) {
            String name = IntegrationSupport.limit(rate.name().replaceAll("[\\p{Cntrl}]", " "), 100);
            String hint = applicationHint(Path.of("/host-proc"), rate.pid(), after.processes().get(rate.pid()).startTime(), name);
            lines.add("• " + name + hint + " (PID " + rate.pid() + "): read "
                    + IntegrationSupport.size(IntegrationSupport.JSON.getNodeFactory().numberNode(rate.readBytes())) + "/s; write "
                    + IntegrationSupport.size(IntegrationSupport.JSON.getNodeFactory().numberNode(rate.writeBytes())) + "/s");
        }
        if (rates.isEmpty()) lines.add("No readable processes reported storage I/O during the sample.");
        if (before.inaccessible() > 0 || after.inaccessible() > 0)
            lines.add("Some process counters were inaccessible; results are incomplete.");
        return lines;
    }

    static String applicationHint(Path proc, String pid, String expectedStart, String processName) {
        Path entry = proc.resolve(pid);
        try {
            if (!expectedStart.equals(startTime(Files.readString(entry.resolve("stat"))))) return "";
            String script = "";
            try (var input = Files.newInputStream(entry.resolve("cmdline"))) {
                script = scriptName(processName, input.readNBytes(MAX_COMMAND_BYTES));
            } catch (IOException | SecurityException ignored) {
                // Command lines may be restricted or disappear as a process exits.
            }
            String container = "";
            try {
                var match = CONTAINER_ID.matcher(Files.readString(entry.resolve("cgroup")));
                if (match.find()) container = match.group(1).substring(0, 12);
            } catch (IOException | SecurityException ignored) {
                // The process may not have a readable cgroup.
            }
            if (!expectedStart.equals(startTime(Files.readString(entry.resolve("stat"))))) return "";
            StringBuilder label = new StringBuilder();
            if (!script.isEmpty()) label.append(" · ").append(script);
            if (!container.isEmpty()) label.append(" [container ").append(container).append("]");
            return label.toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String startTime(String stat) {
        int end = stat.lastIndexOf(')');
        if (end < 0) return "";
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        return fields.length > 19 ? fields[19] : "";
    }

    static String scriptName(String processName, byte[] commandLine) {
        String name = processName.toLowerCase(java.util.Locale.ROOT);
        if (!(name.startsWith("deno") || name.startsWith("node") || name.startsWith("python")
                || name.equals("java") || name.equals("sh") || name.equals("bash"))) return "";
        String[] args = new String(commandLine, StandardCharsets.UTF_8).split("\\x00");
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-") || arg.contains("?") || arg.contains("#") || arg.contains("@")) continue;
            boolean scriptPosition = i == 1 || args[i - 1].equals("run") || args[i - 1].equals("-jar");
            if (!arg.contains("/") && !scriptPosition) continue;
            String file = arg.substring(arg.lastIndexOf('/') + 1);
            if (SCRIPT_NAME.matcher(file).matches()) return file;
        }
        return "";
    }

}
