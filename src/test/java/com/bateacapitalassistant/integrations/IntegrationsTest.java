package com.bateacapitalassistant.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationsTest {
    @Test
    void resolvesContainerNamesFromDockerResponse() throws Exception {
        String id = "a50914636f6d21de440daf36c0dd3ae6c90b393447530c57c9f607f7c8e18621";
        String json = "[{\"Id\":\"" + id + "\",\"Names\":[\"/homeassistant-homeassistant-1\"]}]";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        var input = new java.io.ByteArrayInputStream((headers + json).getBytes(StandardCharsets.UTF_8));
        var names = ContainerNames.parseResponse(input);
        assertEquals("homeassistant-homeassistant-1", names.get(id));
        Path proc = Files.createTempDirectory("process-fixture");
        try {
            Path process = Files.createDirectory(proc.resolve("42"));
            Files.writeString(process.resolve("stat"), "42 (python3) S " + "0 ".repeat(18) + "9876 0");
            Files.writeString(process.resolve("cgroup"), "8:cpu:/docker/" + id + "\n");
            assertEquals(" [container homeassistant-homeassistant-1]",
                    ProcessDiskIo.applicationHint(proc, "42", "9876", "python3", names));
        } finally {
            try (var paths = Files.walk(proc)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void readsChunkedDockerResponsesAndRejectsInvalidNames() throws Exception {
        String id = "a".repeat(64);
        String json = "[{\"Id\":\"" + id + "\",\"Names\":[\"/valid-name\"]}]";
        String response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(json.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + json + "\r\n0\r\n\r\n";
        assertEquals("valid-name", ContainerNames.parseResponse(new java.io.ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))).get(id));
        assertTrue(ContainerNames.parseNames(IntegrationSupport.JSON.readTree("[{\"Id\":\"" + id
                + "\",\"Names\":[\"/unsafe name\"]}]")).isEmpty());
    }

    @Test
    void identifiesScriptAndContainerWithoutExposingArguments(@TempDir Path proc) throws Exception {
        Path process = Files.createDirectory(proc.resolve("123"));
        String stat = "123 (deno) S " + "0 ".repeat(18) + "9876 0";
        Files.writeString(process.resolve("stat"), stat);
        Files.write(process.resolve("cmdline"), "deno\0run\0/app/server.ts\0--token=secret\0".getBytes(StandardCharsets.UTF_8));
        Files.writeString(process.resolve("cgroup"), "8:cpu:/docker/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        String hint = ProcessDiskIo.applicationHint(proc, "123", "9876", "deno");
        assertEquals(" · server.ts [container 0123456789ab]", hint);
        assertFalse(hint.contains("secret"));
        assertEquals("", ProcessDiskIo.applicationHint(proc, "123", "other-start", "deno"));
    }

    @Test
    void ignoresUntrustedCommandArguments() {
        assertEquals("server.ts", ProcessDiskIo.scriptName("deno", "deno\0run\0server.ts\0".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", ProcessDiskIo.scriptName("deno", "deno\0--token=secret.js\0".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", ProcessDiskIo.scriptName("deno", "deno\0run\0https://host/app.ts?token=secret\0".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsProcessRatesAndIgnoresReusedPids() {
        var before = new ProcessDiskIo.Snapshot(java.util.Map.of(
                "10", new ProcessDiskIo.Counters("database", "1", 100, 200),
                "11", new ProcessDiskIo.Counters("old", "2", 0, 0)), 1);
        var after = new ProcessDiskIo.Snapshot(java.util.Map.of(
                "10", new ProcessDiskIo.Counters("database", "1", 2148, 1224),
                "11", new ProcessDiskIo.Counters("new", "3", 5000, 5000)), 0);
        String output = String.join("\n", ProcessDiskIo.format(before, after, 2));
        assertTrue(output.contains("database (PID 10): read 1.0 KiB/s; write 512.0 B/s"));
        assertFalse(output.contains("PID 11"));
        assertTrue(output.contains("inaccessible"));
    }

    @Test
    void filmMatchingRequiresExactTitleTypeAndOptionalYear() throws Exception {
        var choices = (com.fasterxml.jackson.databind.node.ArrayNode) IntegrationSupport.JSON.readTree("""
                [{"title":"Arrival","type":"movie","date":"2016-11-11"},
                 {"title":"Arrival","type":"movie","date":"1996-05-31"},
                 {"title":"Arrival","type":"tv","date":"2016-01-01"},
                 {"title":"Arrival Again","type":"movie","date":"2016-01-01"}]
                """);
        assertEquals(2, Seerr.matchingFilms(choices, "arrival", null).size());
        assertEquals(1, Seerr.matchingFilms(choices, "Ａｒｒｉｖａｌ", IntegrationSupport.JSON.valueToTree(2016)).size());
        assertTrue(Seerr.matchingFilms(choices, "Arrival", IntegrationSupport.JSON.valueToTree(2000)).isEmpty());
    }

    @Test
    void songMatchingRequiresExactTitleAndArtist() throws Exception {
        var songs = (com.fasterxml.jackson.databind.node.ArrayNode) IntegrationSupport.JSON.readTree("""
                [{"name":"Song","artists":["Artist"]},
                 {"name":"Song","artists":["Another Artist"]},
                 {"name":"Song Remix","artists":["Artist"]}]
                """);
        assertEquals(1, Music.matchingSongs(songs, "song", "artist").size());
        assertTrue(Music.matchingSongs(songs, "song", "missing").isEmpty());
    }
}
