package com.bateacapitalassistant;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenCodeTest {
    @Test
    void allowsColdStartAndReusesOnlySuccessfulSessions() throws Exception {
        var paths = new java.util.ArrayList<String>();
        var client = new OpenCode((path, body, timeout) -> {
            paths.add(path);
            if (path.equals("/api/session")) {
                assertTrue(timeout.compareTo(java.time.Duration.ofSeconds(10)) > 0);
                return Bridge.JSON.readTree("{\"data\":{\"id\":\"ses_first\"}}");
            }
            if (path.endsWith("/prompt")) assertTrue(timeout.compareTo(java.time.Duration.ofSeconds(10)) > 0);
            if (path.contains("/message?")) return Bridge.JSON.readTree("""
                    {"data":[{"type":"assistant","content":[{"type":"text","text":"Hello"}]},{"type":"user"}]}
                    """);
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        });
        assertEquals("Hello", client.reply(1, "Hi"));
        assertEquals("Hello", client.reply(1, "Again"));
        assertEquals(1, paths.stream().filter("/api/session"::equals).count());
        assertEquals(2, paths.stream().filter(p -> p.endsWith("/prompt")).count());
    }

    @Test
    void failedFirstPromptIsNotCachedOrAutomaticallyRetried() throws Exception {
        var paths = new java.util.ArrayList<String>();
        var creations = new java.util.concurrent.atomic.AtomicInteger();
        var client = new OpenCode((path, body, timeout) -> {
            paths.add(path);
            if (path.equals("/api/session")) return Bridge.JSON.readTree("{\"data\":{\"id\":\"ses_"
                    + creations.incrementAndGet() + "\"}}");
            if (path.equals("/api/session/ses_1/prompt")) throw new HttpTimeoutException("cold start");
            if (path.contains("/message?")) return Bridge.JSON.readTree("""
                    {"data":[{"type":"assistant","content":[{"type":"text","text":"Ready"}]}]}
                    """);
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        });
        assertThrows(HttpTimeoutException.class, () -> client.reply(1, "Hi"));
        assertEquals(1, creations.get());
        assertTrue(paths.contains("/api/session/ses_1/interrupt"));
        assertEquals("Ready", client.reply(1, "Retry"));
        assertEquals(2, creations.get());
        assertEquals(1, paths.stream().filter("/api/session/ses_1/prompt"::equals).count());
    }

    @Test
    void distinguishesTransportFailuresFromServerAuthentication() {
        assertTrue(OpenCode.failureMessage(new IOException("request", new HttpTimeoutException("timeout"))).contains("may still be running"));
        assertTrue(OpenCode.failureMessage(new IOException("request", new ConnectException())).contains("connect"));
        assertTrue(OpenCode.failureMessage(new IOException("OpenCode /api/session: HTTP 401")).contains("server password"));
        assertFalse(OpenCode.failureMessage(new IOException("secret-url")).contains("secret-url"));
        assertFalse(OpenCode.failureMessage(new IOException("unavailable")).contains("ChatGPT"));
    }
}
