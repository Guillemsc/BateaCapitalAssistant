package com.bateacapitalassistant.integrations;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

final class ContainerNames {
    private static final Path SOCKET = Path.of("/var/run/docker.sock");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT_SECONDS = 3;

    private ContainerNames() {
    }

    static Map<String, String> read() {
        return read(SOCKET);
    }

    static Map<String, String> read(Path socket) {
        if (!Files.exists(socket)) return Map.of();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            FutureTask<Map<String, String>> task = new FutureTask<>(() -> query(channel, socket));
            Thread.startVirtualThread(task);
            try {
                return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                channel.close();
                task.cancel(true);
                return Map.of();
            } catch (ExecutionException e) {
                return Map.of();
            } catch (InterruptedException e) {
                channel.close();
                task.cancel(true);
                Thread.currentThread().interrupt();
                return Map.of();
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private static Map<String, String> query(SocketChannel channel, Path socket) throws IOException {
        channel.connect(UnixDomainSocketAddress.of(socket));
        ByteBuffer request = StandardCharsets.US_ASCII.encode(
                "GET /v1.41/containers/json?all=1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        while (request.hasRemaining()) channel.write(request);
        return parseResponse(Channels.newInputStream(channel));
    }

    static Map<String, String> parseResponse(InputStream input) throws IOException {
        String status = readLine(input, 8192);
        if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) return Map.of();
        boolean chunked = false;
        int length = -1;
        int headerBytes = 0;
        while (true) {
            String line = readLine(input, 8192);
            headerBytes += line.length();
            if (headerBytes > 32768) throw new IOException("Docker response headers too large");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 0) throw new IOException("Invalid Docker response header");
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (key.equalsIgnoreCase("Transfer-Encoding") && value.equalsIgnoreCase("chunked")) chunked = true;
            if (key.equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid Docker response length", e);
                }
            }
        }
        byte[] body;
        if (chunked) body = readChunks(input);
        else if (length >= 0 && length <= MAX_RESPONSE_BYTES) {
            body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Incomplete Docker response");
        } else if (length == -1) {
            body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) throw new IOException("Docker response too large");
        } else throw new IOException("Invalid Docker response length");
        return parseNames(IntegrationSupport.JSON.readTree(body));
    }

    private static String readLine(InputStream input, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() <= max) {
            int value = input.read();
            if (value < 0) throw new EOFException("Incomplete Docker response");
            if (value == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length == 0 || bytes[length - 1] != '\r') throw new IOException("Invalid Docker response line");
                return new String(bytes, 0, length - 1, StandardCharsets.US_ASCII);
            }
            buffer.write(value);
        }
        throw new IOException("Docker response line too long");
    }

    private static byte[] readChunks(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input, 128).split(";", 2)[0].strip();
            int size;
            try {
                size = Integer.parseInt(line, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Docker chunk size", e);
            }
            if (size < 0 || size > MAX_RESPONSE_BYTES - body.size()) throw new IOException("Docker response too large");
            if (size == 0) return body.toByteArray();
            byte[] part = input.readNBytes(size);
            if (part.length != size) throw new EOFException("Incomplete Docker chunk");
            body.write(part);
            if (!readLine(input, 2).isEmpty()) throw new IOException("Invalid Docker chunk");
        }
    }

    static Map<String, String> parseNames(JsonNode containers) {
        if (containers == null || !containers.isArray()) return Map.of();
        Map<String, String> names = new HashMap<>();
        for (JsonNode container : containers) {
            String id = container.path("Id").asText();
            if (!id.matches("[0-9a-f]{64}")) continue;
            for (JsonNode entry : container.path("Names")) {
                String name = entry.asText().replaceFirst("^/", "");
                if (name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
                    names.put(id, name);
                    break;
                }
            }
        }
        return Map.copyOf(names);
    }
}
