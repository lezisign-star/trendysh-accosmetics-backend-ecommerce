package com.nbh.backend.tumaboda;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A stand-in for TumaBoda's partner API on a real local socket, so the real HTTP client, real JSON and
 * real timeouts are exercised. It proves this code does what it intends against the request and response
 * shapes copied from Moments' working integration — it cannot prove TumaBoda itself agrees.
 *
 * <p>Routes are "METHOD /v1/partner/..." (no {@code /api} prefix, no query string). A queued reply is used
 * once, in order; otherwise the route's function answers; otherwise it is a 404 in TumaBoda's error shape.
 */
final class FakeTumaBodaServer implements AutoCloseable {

    record Recorded(String method, String path, String rawQuery, String apiKey, String body) {}

    record Reply(int status, String body) {
        static Reply ok(String body) { return new Reply(200, body); }
        static Reply error(int status, String code, String message) {
            return new Reply(status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> queued = new ConcurrentHashMap<>();
    private final Map<String, Function<Recorded, Reply>> routes = new ConcurrentHashMap<>();

    FakeTumaBodaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    static FakeTumaBodaServer startUnchecked() {
        try {
            return new FakeTumaBodaServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Includes the {@code /api} path the real base URL has. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Answers "METHOD path" once with this reply, ahead of any route. */
    void enqueue(String method, String path, Reply reply) {
        queued.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>()).add(reply);
    }

    /** Answers every "METHOD path" not served by a queued reply. */
    void route(String method, String path, Function<Recorded, Reply> handler) {
        routes.put(method + " " + path, handler);
    }

    void route(String method, String path, Reply reply) {
        route(method, path, request -> reply);
    }

    /** Forgets recorded requests, queued replies and routes. */
    void reset() {
        requests.clear();
        queued.clear();
        routes.clear();
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    List<Recorded> requestsTo(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api")) {
            path = path.substring("/api".length());
        }
        Recorded recorded = new Recorded(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("X-API-Key"), body);
        requests.add(recorded);

        String key = recorded.method() + " " + recorded.path();
        Deque<Reply> queue = queued.get(key);
        Reply reply = queue == null ? null : queue.poll();
        if (reply == null) {
            Function<Recorded, Reply> route = routes.get(key);
            reply = route == null
                    ? Reply.error(404, "NOT_FOUND", "no fake route for " + key)
                    : route.apply(recorded);
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
