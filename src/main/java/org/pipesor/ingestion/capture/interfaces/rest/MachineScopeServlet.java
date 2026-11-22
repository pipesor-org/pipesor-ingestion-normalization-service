package org.pipesor.ingestion.capture.interfaces.rest;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.pipesor.ingestion.capture.infrastructure.configuration.CaptureAuthority;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Bounded read-only check. Timed-out work keeps its permit until it actually stops. */
public final class MachineScopeServlet extends HttpServlet {
    /** The single trusted capture route this check answers for, and its request bounds. */
    public record Settings(UUID captureTenant, String appId, String captureDomain, int maxRequestBytes, int maxInFlight,
                           Duration requestTimeout) {
        public Settings {
            Objects.requireNonNull(captureTenant); Objects.requireNonNull(appId); Objects.requireNonNull(captureDomain);
            if (maxRequestBytes < 1 || maxInFlight < 1 || requestTimeout == null || requestTimeout.toMillis() < 1) {
                throw new IllegalArgumentException("Explicit machine scope limits required");
            }
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("requestId", "correlationId", "deadlineAt", "tenantId",
            "githubAccountId", "githubInstallationId", "githubRepositoryId", "githubWorkflowId", "scopeRevision");
    private final Settings properties;
    private final CaptureAuthority authority;
    private final Clock clock;
    private final Semaphore permits;
    private ScheduledThreadPoolExecutor deadlines;

    public MachineScopeServlet(Settings properties, CaptureAuthority authority, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
        this.permits = new Semaphore(properties.maxInFlight());
    }

    @Override public void init() {
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "ingestion-machine-scope-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
    }
    @Override public void destroy() { if (deadlines != null) deadlines.shutdownNow(); }

    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) { problem(response, 403); return; }
        final String correlation;
        final Instant expires;
        try {
            correlation = header(request, "X-Correlation-Id", true);
            if (!correlation.matches("[A-Za-z0-9._:-]{1,128}") || request.getQueryString() != null
                    || request.getHeader("Cookie") != null) throw new Rejected(400);
            var media = MediaType.parseMediaType(header(request, "Content-Type", true));
            if (!media.getType().equalsIgnoreCase("application") || !media.getSubtype().equalsIgnoreCase("json")
                    || media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset())) throw new Rejected(415);
            String encoding = header(request, "Content-Encoding", false);
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new Rejected(415);
            if (request.getContentLengthLong() > properties.maxRequestBytes()) throw new Rejected(413);
            if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authenticated)) throw new Rejected(401);
            expires = authenticated.getToken().getExpiresAt();
            if (expires == null || !expires.isAfter(clock.instant())) throw new Rejected(503);
        } catch (Rejected rejected) { problem(response, rejected.status); return; }
        catch (RuntimeException malformed) { problem(response, 400); return; }
        if (!permits.tryAcquire()) { problem(response, 429); return; }

        long started = System.nanoTime();
        final AsyncContext async;
        try { async = request.startAsync(); }
        catch (RuntimeException unavailable) { permits.release(); problem(response, 503); return; }
        var finished = new AtomicBoolean();
        // Reading can release immediately; work retains its slot through any response timeout.
        var processing = new AtomicBoolean();
        var released = new AtomicBoolean();
        var timer = new AtomicReference<ScheduledFuture<?>>();
        class Completion {
            void release() { if (released.compareAndSet(false, true)) permits.release(); }
            void finish(int status, String result) {
                if (System.nanoTime() - started >= properties.requestTimeout().toNanos()) { status = 503; result = null; }
                if (!finished.compareAndSet(false, true)) return;
                var scheduled = timer.get(); if (scheduled != null) scheduled.cancel(false);
                try {
                    if (status == 200) {
                        response.setStatus(200);
                        response.setContentType("application/json");
                        response.setHeader("Cache-Control", "no-store");
                        response.getOutputStream().write(result.getBytes(StandardCharsets.UTF_8));
                    } else problem(response, status);
                    response.flushBuffer();
                } catch (IOException | IllegalStateException closed) {
                    // The authenticated check may finish after the connection closes.
                } finally {
                    if (!processing.get()) release();
                    try { async.complete(); } catch (IllegalStateException closed) {}
                }
            }
        }
        var completion = new Completion();
        try {
            long tokenRemaining = Duration.between(clock.instant(), expires).toNanos();
            long budget = Math.min(properties.requestTimeout().toNanos(), tokenRemaining);
            if (budget <= 0) { completion.finish(503, null); return; }
            async.setTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget)));
            timer.set(deadlines.schedule(() -> completion.finish(503, null), budget, TimeUnit.NANOSECONDS));
            async.addListener(new AsyncListener() {
                public void onTimeout(AsyncEvent event) { completion.finish(503, null); }
                public void onError(AsyncEvent event) { completion.finish(503, null); }
                public void onComplete(AsyncEvent event) {}
                public void onStartAsync(AsyncEvent event) {}
            });
            var input = request.getInputStream();
            var bytes = new ByteArrayOutputStream();
            input.setReadListener(new ReadListener() {
                public void onDataAvailable() throws IOException {
                    var buffer = new byte[4096];
                    while (!finished.get() && input.isReady() && !input.isFinished()) {
                        int count = input.read(buffer);
                        if (count <= 0) return;
                        if (count > properties.maxRequestBytes() - bytes.size()) { completion.finish(413, null); return; }
                        bytes.write(buffer, 0, count);
                    }
                }
                public void onAllDataRead() {
                    if (finished.get()) return;
                    processing.set(true);
                    try {
                        if (finished.get()) return;
                        var root = parse(bytes.toByteArray());
                        var id = uuid(text(root, "requestId"));
                        if (!correlation.equals(text(root, "correlationId"))) throw new Rejected(400);
                        String rawDeadline = text(root, "deadlineAt");
                        if (!rawDeadline.endsWith("Z")) throw new Rejected(400);
                        Instant requested = Instant.parse(rawDeadline);
                        Instant deadline = Collections.min(List.of(requested, expires,
                                clock.instant().plusNanos(Math.max(0, budget - (System.nanoTime() - started)))));
                        if (!deadline.isAfter(clock.instant()) || System.nanoTime() - started >= budget) throw new Rejected(503);
                        var previousTimer = timer.getAndSet(deadlines.schedule(
                                () -> completion.finish(503, null),
                                Math.max(0, Duration.between(clock.instant(), deadline).toNanos()), TimeUnit.NANOSECONDS));
                        if (previousTimer != null) previousTimer.cancel(false);
                        if (finished.get()) return;
                        UUID tenant = uuid(text(root, "tenantId"));
                        var capture = new WorkflowObservation.CaptureContext(tenant, text(root, "githubAccountId"),
                                text(root, "githubInstallationId"), text(root, "scopeRevision"));
                        var pipeline = new WorkflowObservation.Pipeline(text(root, "githubRepositoryId"), text(root, "githubWorkflowId"));
                        String decision = "DENIED";
                        if (properties.captureTenant().equals(tenant)) {
                            var route = new HookContext(properties.captureDomain(), properties.captureTenant(), properties.appId());
                            decision = switch (authority.verifyProtectedEvidenceScope(route, capture, pipeline)) {
                                case AUTHORIZED -> "AUTHORIZED";
                                case CHANGED_OR_LOST -> "DENIED";
                                case UNAVAILABLE -> throw new Rejected(503);
                            };
                        }
                        if (!deadline.isAfter(clock.instant()) || System.nanoTime() - started >= budget) throw new Rejected(503);
                        completion.finish(200, JSON.writeValueAsString(Map.of(
                                "requestId", id.toString(), "correlationId", correlation, "decision", decision)));
                    } catch (Rejected rejected) { completion.finish(rejected.status, null); }
                    catch (RuntimeException | CharacterCodingException malformed) { completion.finish(400, null); }
                    finally { processing.set(false); completion.release(); }
                }
                public void onError(Throwable error) { completion.finish(503, null); }
            });
        } catch (RuntimeException | IOException unavailable) { completion.finish(503, null); }
    }

    private static JsonNode parse(byte[] bytes) throws CharacterCodingException {
        String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        var root = JSON.readTree(source);
        if (!root.isObject() || !root.propertyNames().equals(FIELDS)) throw new Rejected(400);
        return root;
    }
    private static String text(JsonNode root, String field) {
        var node = root.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) throw new Rejected(400);
        return node.asString();
    }
    private static UUID uuid(String raw) {
        var id = UUID.fromString(raw);
        if (!id.toString().equals(raw) || id.equals(new UUID(0, 0))) throw new Rejected(400);
        return id;
    }
    private static String header(HttpServletRequest request, String name, boolean required) {
        var headers = Collections.list(request.getHeaders(name));
        if (headers.size() > 1 || required && headers.isEmpty()) throw new Rejected(400);
        if (headers.isEmpty()) return null;
        String result = headers.getFirst();
        if (result.isBlank() || result.length() > 512) throw new Rejected(400);
        return result;
    }
    public static void problem(HttpServletResponse response, int status) throws IOException {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHENTICATED"; case 403 -> "CALLER_DENIED";
            case 413 -> "REQUEST_TOO_LARGE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE"; case 429 -> "OVERLOADED";
            default -> "UNAVAILABLE";
        };
        response.resetBuffer(); response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/problem+json");
        response.getOutputStream().write(JSON.writeValueAsBytes(Map.of("type", "about:blank",
                "title", HttpStatus.valueOf(status).getReasonPhrase(), "status", status, "code", code)));
    }
    private static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status) { super(null, null, false, false); this.status = status; }
    }
}
