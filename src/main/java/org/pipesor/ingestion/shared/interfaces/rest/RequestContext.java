package org.pipesor.ingestion.shared.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.configuration.ApiProperties;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.springframework.security.oauth2.jwt.Jwt;

/** Claims are used only after the resource server has verified the delegated token. */
public record RequestContext(String requestId, AccessContext access, UUID actorObjectId, UUID subjectObjectId,
                             Instant startedAt, Instant validUntil) {
    public static RequestContext internal(Jwt jwt, HttpServletRequest request, String requestId, String correlationId,
                                          Instant deadline, AccessOperation operation, UUID recipient,
                                          ApiProperties.Limits limits, Clock clock) {
        if (!ApiBoundaryFilter.validContext(requestId, limits.maxContextLength()) || deadline == null || operation == null
                || !correlationIdEquals(request, correlationId) || operation == AccessOperation.DISCOVER) {
            throw new ApiFailure(400, "INVALID_REQUEST");
        }
        if (recipient != null && !operation.permitsDifferentSubject()) { throw new ApiFailure(403, "DENIED"); }
        return create(jwt, requestId, correlationId, deadline, operation, recipient, limits, clock);
    }
    public static RequestContext self(Jwt jwt, HttpServletRequest request, ApiProperties.Limits limits, Clock clock) {
        return create(jwt, UUID.randomUUID().toString(), ApiExceptionHandler.correlation(request),
                clock.instant().plus(limits.requestTimeout()), AccessOperation.DISCOVER, null, limits, clock);
    }
    private static RequestContext create(Jwt jwt, String requestId, String correlationId, Instant deadline,
                                          AccessOperation operation, UUID recipient, ApiProperties.Limits limits, Clock clock) {
        var tenant = UUID.fromString(jwt.getClaimAsString("tid"));
        var actor = UUID.fromString(jwt.getClaimAsString("oid"));
        var subject = recipient == null ? actor : recipient;
        var started = clock.instant();
        Instant bound = started.plus(limits.requestTimeout());
        if (deadline.isBefore(bound)) { bound = deadline; }
        if (jwt.getExpiresAt().isBefore(bound)) { bound = jwt.getExpiresAt(); }
        if (!bound.isAfter(started)) { throw ApiFailure.unavailable(); }
        return new RequestContext(requestId, new AccessContext(tenant.toString(), tenant + ":" + actor,
                tenant + ":" + subject, operation, correlationId), actor, subject, started, bound);
    }
    public void requireLive(Clock clock) {
        var now = clock.instant();
        if (now.isBefore(startedAt) || !now.isBefore(validUntil)) { throw ApiFailure.unavailable(); }
    }
    public ConnectionIdentity identity() { return new ConnectionIdentity(UUID.fromString(access.tenantId()), actorObjectId); }
    private static boolean correlationIdEquals(HttpServletRequest request, String correlation) {
        return correlation != null && correlation.equals(ApiExceptionHandler.correlation(request));
    }
}
