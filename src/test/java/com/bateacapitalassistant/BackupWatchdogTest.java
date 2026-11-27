package com.bateacapitalassistant;

import com.bateacapitalassistant.integrations.Backups;

import java.nio.file.Path;
import java.util.*;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class BackupWatchdogTest {
    @Test
    void persistsDeduplicationAndRetriesBeforeReportingRecovery(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("backups.json");
        var failure = new Backups.Report("failed", Map.of("task-1", "NAS — last backup failed"));
        var first = new BackupWatchdog(path, Set.of(1L), (user, message) -> {
            throw new IOException("offline");
        });
        first.observe(failure);
        assertThrows(IOException.class, first::deliver);
        var delivered = new ArrayList<String>();
        var restarted = new BackupWatchdog(path, Set.of(1L), (user, message) -> delivered.add(message));
        restarted.load();
        restarted.deliver();
        restarted.observe(failure);
        restarted.deliver();
        assertEquals(1, delivered.size());
        restarted.observe(new Backups.Report("successful", Map.of()));
        restarted.deliver();
        assertEquals(2, delivered.size());
        assertTrue(delivered.get(1).contains("issue cleared"));
    }
}
