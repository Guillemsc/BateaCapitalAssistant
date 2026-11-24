package com.bateacapitalassistant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DiskWatchdogTest {
    @Test
    void processSamplingStartsOnlyAfterSustainedHighActivity() {
        var incident = new DiskWatchdog.Incident();
        for (int i = 0; i < 3; i++) {
            incident.observe(90, 10);
            assertFalse(incident.needsProcessSample());
        }
        incident.observe(90, 10);
        assertTrue(incident.needsProcessSample());
        incident.observe(70, 10);
        assertFalse(incident.needsProcessSample());
    }

    @Test
    void requiresSustainedHighAndLowActivityWithoutRepeating() {
        var incident = new DiskWatchdog.Incident();
        for (int i = 0; i < 5; i++) assertNull(incident.observe(81, 10));
        assertEquals("alert", incident.observe(81, 10));
        assertNull(incident.observe(99, 10));
        for (int i = 0; i < 5; i++) assertNull(incident.observe(59, 10));
        assertNull(incident.observe(65, 10));
        for (int i = 0; i < 5; i++) assertNull(incident.observe(59, 10));
        assertEquals("recovery", incident.observe(59, 10));
        assertNull(incident.observe(60, 10));
    }

    @Test
    void interruptedAndMissingSamplesCannotTriggerFalseAlerts() {
        var incident = new DiskWatchdog.Incident();
        for (int i = 0; i < 5; i++) assertNull(incident.observe(95, 10));
        assertNull(incident.observe(95, 30));
        assertNull(incident.observe(95, 10));
        assertNull(incident.observe(80, 10));
        for (int i = 0; i < 5; i++) assertNull(incident.observe(95, 10));
        assertEquals("alert", incident.observe(95, 10));
    }

    @Test
    void retriesPersistedNotificationsAfterFailure(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("watchdog.json");
        var watchdog = new DiskWatchdog(state, Set.of(1L), (user, text) -> {
            throw new IOException("offline");
        });
        watchdog.load();
        watchdog.enqueue("disk alert");
        watchdog.save();
        assertThrows(IOException.class, watchdog::deliver);
        var delivered = new ArrayList<String>();
        var restarted = new DiskWatchdog(state, Set.of(1L), (user, text) -> delivered.add(user + ":" + text));
        restarted.load();
        restarted.deliver();
        assertEquals(java.util.List.of("1:disk alert"), delivered);
        var again = new DiskWatchdog(state, Set.of(1L), (user, text) -> fail("duplicate"));
        again.load();
        again.deliver();
    }
}
