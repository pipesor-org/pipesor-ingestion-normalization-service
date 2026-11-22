package org.pipesor.ingestion.shared.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.pipesor.ingestion.auth.application.services.LoginFailure;
import org.pipesor.ingestion.capture.application.commandhandlers.GitHubAnalysisHandler;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.shared.security.ProblemResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the service's fixed failure types to {@code application/problem+json}. Bodies never carry
 * exception messages; server-side failures are logged with exception class and correlation id only.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record Problem(String type, String title, int status, String code, String correlationId) {}

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Problem> failure(ApiFailure failure, HttpServletRequest request) {
        return problem(failure.status(), failure.code(), failure, request);
    }
    @ExceptionHandler(GitHubAccessFailure.class)
    ResponseEntity<Problem> github(GitHubAccessFailure failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case CONNECTION_REQUIRED -> problem(409, "CONNECTION_REQUIRED", failure, request);
            case DENIED -> problem(403, "DENIED", failure, request);
            case UNAVAILABLE -> problem(503, "VERIFICATION_UNAVAILABLE", failure, request);
        };
    }
    @ExceptionHandler(ConnectionFailure.class)
    ResponseEntity<Problem> connection(ConnectionFailure failure, HttpServletRequest request) {
        return failure.reason() == ConnectionFailure.Reason.UNAVAILABLE ? problem(503, "VERIFICATION_UNAVAILABLE", failure, request)
                : problem(409, "CONNECTION_CONFLICT", failure, request);
    }
    @ExceptionHandler(CaptureFailure.class)
    ResponseEntity<Problem> capture(CaptureFailure failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case INVALID_INPUT -> problem(422, "UNSUPPORTED_EVIDENCE", failure, request);
            case SCOPE_CHANGED -> problem(403, "DENIED", failure, request);
            case CONFLICT, STALE_CLAIM -> problem(409, "CAPTURE_CONFLICT", failure, request);
            default -> problem(503, "CAPTURE_UNAVAILABLE", failure, request);
        };
    }
    @ExceptionHandler(GitHubAnalysisHandler.AnalysisPending.class)
    ResponseEntity<Problem> analysisPending(GitHubAnalysisHandler.AnalysisPending failure, HttpServletRequest request) {
        return problem(409, "CAPTURE_SCOPE_PENDING", failure, request);
    }
    @ExceptionHandler(GitHubAnalysisHandler.AnalysisDenied.class)
    ResponseEntity<Problem> analysisDenied(GitHubAnalysisHandler.AnalysisDenied failure, HttpServletRequest request) {
        return problem(403, "DENIED", failure, request);
    }
    @ExceptionHandler(LoginFailure.class)
    ResponseEntity<Problem> login(LoginFailure failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case UNAVAILABLE -> problem(503, "LOGIN_UNAVAILABLE", failure, request);
            case INVALID_HANDOFF -> problem(400, "INVALID_HANDOFF", failure, request);
            default -> problem(400, "INVALID_REQUEST", failure, request);
        };
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestValueException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class, org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpRequestMethodNotSupportedException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    ResponseEntity<Problem> badRequest(Exception failure, HttpServletRequest request) { return problem(400, "INVALID_REQUEST", failure, request); }

    /** Anything else is a defect: logged with its class and correlation id, answered without details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("Unexpected request failure exception={} correlationId={}", failure.getClass().getName(), correlation(request));
        return problem(500, "INTERNAL_ERROR", null, request);
    }

    private static ResponseEntity<Problem> problem(int status, String code, Exception failure, HttpServletRequest request) {
        if (failure != null && status >= 500) {
            LOG.warn("Request failed status={} code={} exception={} correlationId={}", status, code,
                    failure.getClass().getName(), correlation(request));
        } else if (failure != null && LOG.isDebugEnabled()) {
            LOG.debug("Request rejected status={} code={} exception={} correlationId={}", status, code,
                    failure.getClass().getName(), correlation(request));
        }
        return ResponseEntity.status(status).header("Cache-Control", "no-store").contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem("about:blank", HttpStatus.valueOf(status).getReasonPhrase(), status, code, correlation(request)));
    }
    public static void write(HttpServletResponse response, HttpServletRequest request, int status, String code) throws IOException {
        ProblemResponses.write(response, request, status, code);
    }
    public static String correlation(HttpServletRequest request) {
        return ProblemResponses.correlation(request);
    }
}
