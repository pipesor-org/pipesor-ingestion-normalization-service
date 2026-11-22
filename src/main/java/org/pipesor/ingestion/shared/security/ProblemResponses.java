package org.pipesor.ingestion.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

/** RFC 9457 bodies for the platform identity contract (§9): no token details, {@code Cache-Control: no-store}. */
public final class ProblemResponses {
    /** Request attribute holding the validated correlation id of the current request. */
    public static final String CORRELATION_ATTRIBUTE = "org.pipesor.ingestion.correlationId";

    private ProblemResponses() { }

    public static void write(HttpServletResponse response, HttpServletRequest request, int status, String code)
            throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        String title = HttpStatus.resolve(status) == null ? "Error" : HttpStatus.valueOf(status).getReasonPhrase();
        response.getOutputStream().write(("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"correlationId\":\"" + correlation(request) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The correlation id is validated before it is stored ({@code [A-Za-z0-9._:-]}), so it is JSON-safe. */
    public static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(CORRELATION_ATTRIBUTE);
        return value instanceof String text ? text : "";
    }
}
