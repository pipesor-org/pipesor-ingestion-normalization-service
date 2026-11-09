package org.pipesor.ingestion.shared.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record Problem(String type, int status, String code, String correlationId) {}

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Problem> failure(ApiFailure failure, HttpServletRequest request) {
        return problem(failure.status(), failure.code(), request);
    }
    @ExceptionHandler(GitHubAccessFailure.class)
    ResponseEntity<Problem> github(GitHubAccessFailure failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case CONNECTION_REQUIRED -> problem(409, "CONNECTION_REQUIRED", request);
            case DENIED -> problem(403, "DENIED", request);
            case UNAVAILABLE -> problem(503, "VERIFICATION_UNAVAILABLE", request);
        };
    }
    @ExceptionHandler(ConnectionFailure.class)
    ResponseEntity<Problem> connection(ConnectionFailure failure, HttpServletRequest request) {
        return failure.reason() == ConnectionFailure.Reason.UNAVAILABLE ? problem(503, "VERIFICATION_UNAVAILABLE", request)
                : problem(409, "CONNECTION_CONFLICT", request);
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestValueException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class, org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpRequestMethodNotSupportedException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    ResponseEntity<Problem> badRequest(Exception ignored, HttpServletRequest request) { return problem(400, "INVALID_REQUEST", request); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unavailable(Exception ignored, HttpServletRequest request) { return problem(503, "VERIFICATION_UNAVAILABLE", request); }

    private static ResponseEntity<Problem> problem(int status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem("about:blank", status, code, correlation(request)));
    }
    public static void write(HttpServletResponse response, HttpServletRequest request, int status, String code) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(("{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"correlationId\":\"" + correlation(request) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(ApiBoundaryFilter.CORRELATION);
        return value instanceof String text ? text : "";
    }
}
