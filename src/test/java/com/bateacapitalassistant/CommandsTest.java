package com.bateacapitalassistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {
    private final Commands commands = new Commands(() -> fail("Unexpected menu change"));

    @Test
    void rejectsRedundantStatusVariantsWithoutCallingServices() throws Exception {
        for (String command : List.of("/disk", "/nas", "/seerr", "/music", "/films", "/qbittorrent", "/transcoding")) {
            assertEquals(Commands.help(command), commands.run(command, command + " status"));
        }
        assertEquals(Commands.help("/disk"), commands.run("/disk", "/disk activity"));
        assertEquals(Commands.help("/disk"), commands.run("/disk", "/disk blabla"));
        assertEquals(Commands.help("/music"), commands.run("/music", "/music downloads"));
    }

    @Test
    void helpIsFocusedAndNeverContactsServices() throws Exception {
        for (String name : Commands.SUPPORTED) {
            String help = commands.run(name, name + " help");
            assertFalse(help.isBlank());
            assertTrue(help.contains(name), name);
        }
        assertEquals(Commands.help("/shows"), commands.run("/sonarr", "/sonarr help"));
        assertEquals(Commands.help("/music"), commands.run("/music", "/music invalid"));
        assertEquals(Commands.help("/home"), commands.run("/home", "/home status"));
    }

    @Test
    void parsesPhoneQuotesAndApostrophesInNames() {
        assertEquals(List.of("/music", "request", "Don't Stop", "Fleetwood Mac"),
                Commands.split("/music request “Don't Stop” “Fleetwood Mac”"));
        assertEquals(List.of("/seerr", "search", "Schindler's", "List"),
                Commands.split("/seerr search Schindler's List"));
        assertThrows(IllegalArgumentException.class, () -> Commands.split("/music search \"unfinished"));
    }

    @Test
    void rejectsInvalidMusicRequestsBeforeContactingServices() throws Exception {
        assertTrue(commands.run("/music", "/music request \"\" Artist").contains("1 and 90"));
        assertTrue(commands.run("/music", "/music search \"\"").contains("1 and 200"));
        assertTrue(commands.run("/music", "/music request Song Artist extra").contains("/music request"));
    }

    @Test
    void canonicalizesAliasesAndKeepsRefreshActionsReadOnly() {
        assertEquals("/seerr", Commands.canonical("/Seer@MyBot"));
        assertEquals("/shows", Commands.canonical("/Sonarr"));
        assertTrue(Commands.isRefreshableStatus("/disk", "/disk"));
        assertFalse(Commands.isRefreshableStatus("/disk", "/disk activity"));
        assertTrue(Commands.isRefreshableStatus("/music", "/music"));
        assertFalse(Commands.isRefreshableStatus("/music", "/music downloads"));
        assertFalse(Commands.isRefreshableStatus("/music", "/music request Song Artist"));
        assertFalse(Commands.isRefreshableStatus("/seerr", "/seerr search Arrival"));
        assertFalse(Commands.isRefreshableStatus("/home", "/home status Living Room"));
    }
}
