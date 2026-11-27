package com.bateacapitalassistant;

import com.bateacapitalassistant.integrations.HomeAssistant;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

final class HomeWatchdog implements Runnable {
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    private static final int REQUIRED_SAMPLES = 3;

    private final Set<Long> users;
    private final DiskWatchdog.Sender sender;
    private final Map<String, Integer> streaks = new HashMap<>();
    private final Map<Long, Set<String>> notified = new HashMap<>();

    HomeWatchdog(Set<Long> users, DiskWatchdog.Sender sender) {
        this.users = Set.copyOf(users);
        this.sender = sender;
    }

    void observe(Map<String, HomeAssistant.DeviceAvailability> devices) throws InterruptedException {
        streaks.keySet().retainAll(devices.keySet());
        for (var entry : devices.entrySet()) {
            String id = entry.getKey();
            var device = entry.getValue();
            if (ignored(device.name())) continue;
            if (device.unavailable()) {
                int count = streaks.merge(id, 1, (previous, increment) -> Math.min(REQUIRED_SAMPLES, previous + increment));
                if (count < REQUIRED_SAMPLES) continue;
            } else {
                streaks.remove(id);
            }
            String name = device.name().replaceAll("[\\p{Cntrl}]", " ");
            for (long user : users) {
                Set<String> alerts = notified.computeIfAbsent(user, ignored -> new HashSet<>());
                if (alerts.contains(id) == device.unavailable()) continue;
                try {
                    if (device.unavailable()) {
                        sender.send(user, "⚠️ Home Assistant device unavailable\n" + name
                                + "\nAll known entity states were unavailable in three consecutive checks.");
                        alerts.add(id);
                    } else {
                        sender.send(user, "✅ Home Assistant device available again\n" + name);
                        alerts.remove(id);
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("Home Assistant availability notification failed; will retry if still applicable");
                }
            }
        }
    }

    static boolean ignored(String deviceName) {
        String name = deviceName.toLowerCase(Locale.ROOT);
        return (name.contains("samsung") && (name.matches(".*\\btv\\b.*") || name.contains("television")))
                || (name.contains("xiaomi") && (name.contains("box") || name.contains("decodificador")));
    }

    @Override public void run() {
        var monitor = new HomeAssistant.AvailabilityMonitor();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                try {
                    observe(monitor.sample());
                } catch (IOException | RuntimeException e) {
                    streaks.clear();
                    System.err.println("Home Assistant availability check failed; device states unknown");
                }
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
