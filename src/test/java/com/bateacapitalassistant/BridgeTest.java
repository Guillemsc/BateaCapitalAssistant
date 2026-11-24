package com.bateacapitalassistant;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {
    @Test
    void parsesQuotedTitlesAndRejectsUnfinishedCommands() {
        assertEquals(List.of("/music", "request", "Don't Stop", "Fleetwood Mac"),
                Commands.split("/music request \"Don't Stop\" 'Fleetwood Mac'"));
        assertEquals(List.of("/seerr", "request", "Film Name (2014)"),
                Commands.split("/seerr request \"Film Name (2014)\""));
        assertThrows(IllegalArgumentException.class, () -> Commands.split("/music request \"Incomplete"));
    }

    @Test
    void rejectsTvSubmissionBeforeCallingSeerr() throws Exception {
        var commands = new Commands(() -> fail("Unexpected menu refresh"));
        assertTrue(commands.run("/seerr", "/seerr request \"Slow Horses\" — tv").contains("films only"));
    }

    @Test
    void keepsTelegramOffsetsAsLongsAndPreservesOtherState(@TempDir Path dir) throws Exception {
        var state = Bridge.JSON.readTree("{\"offset\":4400000000,\"other\":true}");
        Path path = dir.resolve("bridge.json");
        Bridge.save(path, state);
        var loaded = Bridge.JSON.readTree(path.toFile());
        assertEquals(4400000000L, loaded.path("offset").longValue());
        assertTrue(loaded.path("other").booleanValue());
    }

    @Test
    void onlyForwardsRecognizedDecluttarrActions() {
        assertNull(Decluttarr.event("| INFO | arbitrary log", false));
        assertNull(Decluttarr.event("| INFO | Job 'remove_stalled' triggered debug output", false));
        assertTrue(Decluttarr.event("| INFO | Job 'remove_stalled' triggered removal: episode", true)
                .contains("DRY RUN — no changes\nStalled download\nremoval: episode"));
    }
}
