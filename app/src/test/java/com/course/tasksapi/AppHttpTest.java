package com.course.tasksapi;

import com.course.tasksapi.config.Config;
import com.course.tasksapi.events.NoOpEventPublisher;
import com.course.tasksapi.repo.InMemoryTaskRepository;
import com.course.tasksapi.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the real HTTP server. We start {@link App}'s server on an
 * ephemeral port (0) with in-memory storage and drive it with the JDK's
 * {@link HttpClient} — no mocks, no external services. This exercises the actual
 * routing, status codes and JSON shapes, which is exactly what the PRD acceptance
 * criteria (§8.4) describe.
 *
 * <p>A fresh server + repository per test keeps the cases independent.
 */
class AppHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        Config cfg = Config.of(0, "test-version");
        server = App.createServer(cfg, new InMemoryTaskRepository(), new NoOpEventPublisher());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // --- happy paths ---------------------------------------------------------

    @Test
    void healthReturnsUpAndVersion() throws Exception {
        HttpResponse<String> res = send("GET", "/health", null, null);

        assertEquals(200, res.statusCode());
        JsonNode body = Json.parse(res.body());
        assertEquals("UP", body.get("status").asText());
        assertEquals("test-version", body.get("version").asText());
        // Every response carries a request id (FR-5).
        assertNotNull(res.headers().firstValue("X-Request-Id").orElse(null));
    }

    @Test
    void createThenGetThenListFlow() throws Exception {
        HttpResponse<String> created = send("POST", "/tasks",
                "{\"title\":\"Learn Docker\"}", "application/json");

        assertEquals(201, created.statusCode());
        JsonNode task = Json.parse(created.body());
        String id = task.get("id").asText();
        assertFalse(id.isBlank());
        assertFalse(task.get("done").asBoolean());          // defaults to false
        assertTrue(task.get("createdAt").asText().endsWith("Z"));
        assertEquals("/tasks/" + id, created.headers().firstValue("Location").orElseThrow());

        // GET the new task
        HttpResponse<String> fetched = send("GET", "/tasks/" + id, null, null);
        assertEquals(200, fetched.statusCode());
        assertEquals("Learn Docker", Json.parse(fetched.body()).get("title").asText());

        // It appears in the list with a correct count
        HttpResponse<String> list = send("GET", "/tasks", null, null);
        assertEquals(200, list.statusCode());
        JsonNode listBody = Json.parse(list.body());
        assertEquals(1, listBody.get("count").asInt());
        assertEquals(1, listBody.get("tasks").size());
    }

    @Test
    void putUpdatesDoneFlag() throws Exception {
        String id = createTask("Write tests");

        HttpResponse<String> updated = send("PUT", "/tasks/" + id,
                "{\"done\":true}", "application/json");

        assertEquals(200, updated.statusCode());
        assertTrue(Json.parse(updated.body()).get("done").asBoolean());
    }

    @Test
    void deleteRemovesTask() throws Exception {
        String id = createTask("Delete me");

        HttpResponse<String> deleted = send("DELETE", "/tasks/" + id, null, null);
        assertEquals(204, deleted.statusCode());

        // Now it is gone.
        assertEquals(404, send("GET", "/tasks/" + id, null, null).statusCode());
    }

    // --- error paths ---------------------------------------------------------

    @Test
    void postWithBlankTitleReturns400Validation() throws Exception {
        HttpResponse<String> res = send("POST", "/tasks",
                "{\"title\":\"\"}", "application/json");

        assertEquals(400, res.statusCode());
        assertEquals("VALIDATION", Json.parse(res.body()).get("error").asText());
    }

    @Test
    void postWithoutJsonContentTypeReturns415() throws Exception {
        HttpResponse<String> res = send("POST", "/tasks", "title=x", "text/plain");

        assertEquals(415, res.statusCode());
    }

    @Test
    void getMissingTaskReturns404() throws Exception {
        HttpResponse<String> res = send("GET", "/tasks/nope", null, null);

        assertEquals(404, res.statusCode());
        assertEquals("NOT_FOUND", Json.parse(res.body()).get("error").asText());
    }

    @Test
    void unsupportedMethodOnCollectionReturns405() throws Exception {
        HttpResponse<String> res = send("DELETE", "/tasks", null, null);

        assertEquals(405, res.statusCode());
        assertTrue(res.headers().firstValue("Allow").orElse("").contains("POST"));
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        assertEquals(404, send("GET", "/nope", null, null).statusCode());
    }

    /**
     * Pins the HttpServer prefix-matching gotcha: "/tasksfoo" is delivered to the
     * "/tasks" context by prefix match, and our handler must reject it as 404
     * rather than treating "foo" as an id.
     */
    @Test
    void prefixLookalikePathReturns404() throws Exception {
        assertEquals(404, send("GET", "/tasksfoo", null, null).statusCode());
    }

    // --- helpers -------------------------------------------------------------

    private String createTask(String title) throws Exception {
        HttpResponse<String> res = send("POST", "/tasks",
                "{\"title\":\"" + title + "\"}", "application/json");
        return Json.parse(res.body()).get("id").asText();
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        HttpRequest.BodyPublisher publisher = (body == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.method(method, publisher);
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
