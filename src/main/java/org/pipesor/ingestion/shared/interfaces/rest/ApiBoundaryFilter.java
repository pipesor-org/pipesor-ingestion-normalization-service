package org.pipesor.ingestion.shared.interfaces.rest;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.pipesor.ingestion.configuration.ApiProperties;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Bounds bodies before JSON parsing and rejects ambient credential/header identity channels. */
public final class ApiBoundaryFilter extends OncePerRequestFilter {
    public static final String CORRELATION = ApiBoundaryFilter.class.getName() + ".correlation";
    private final ApiProperties.Limits limits;
    public ApiBoundaryFilter(ApiProperties.Limits limits) { this.limits = limits; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String correlation = request.getHeader("X-Correlation-Id");
        if (correlation == null) { correlation = UUID.randomUUID().toString(); }
        if (!validContext(correlation, limits.maxContextLength())) {
            ApiExceptionHandler.write(response, request, 400, "INVALID_REQUEST"); return;
        }
        request.setAttribute(CORRELATION, correlation);
        response.setHeader("X-Correlation-Id", correlation);
        response.setHeader("Cache-Control", "no-store");
        if (request.getHeader("X-Actor-Id") != null || request.getHeader("X-Subject-Id") != null || request.getHeader("X-Tenant-Id") != null) {
            ApiExceptionHandler.write(response, request, 400, "INVALID_REQUEST"); return;
        }
        byte[] body = request.getInputStream().readNBytes(limits.maxRequestBytes() + 1);
        if (body.length > limits.maxRequestBytes()) { ApiExceptionHandler.write(response, request, 400, "INVALID_REQUEST"); return; }
        var wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        };
        var wrappedResponse = new ContentCachingResponseWrapper(response);
        chain.doFilter(wrappedRequest, wrappedResponse);
        if (wrappedResponse.getContentSize() > limits.maxResponseBytes()) {
            ApiExceptionHandler.write(response, request, 503, "VERIFICATION_UNAVAILABLE");
        } else { wrappedResponse.copyBodyToResponse(); }
    }
    public static boolean validContext(String value, int maxLength) {
        return value != null && value.length() <= maxLength && value.matches("[A-Za-z0-9._:-]+");
    }
}
