package org.pipesor.ingestion.github.infrastructure.persistence.jdbc;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.ports.PipesorConnections;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One statement per page over the ADR-020 connection metadata, with the {@code ConnectedGitHubLink} definition of a
 * currently usable link: the principal's connection in the tenant points at its ACTIVE generation, that generation
 * belongs to the same GitHub user and its refresh credential has not expired. Reads metadata only, never credentials.
 */
public final class JdbcPipesorConnections implements PipesorConnections {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcPipesorConnections(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public Observation<Request, Set<Principal>> active(Request request) {
        Objects.requireNonNull(request);
        if (request.principals().isEmpty()) { return new Observation<>(request, Set.of(), clock.instant()); }
        var arguments = new ArrayList<Object>();
        arguments.add(request.tenantId());
        for (var principal : request.principals()) {
            arguments.add(principal.objectId());
            arguments.add(principal.githubUserId());
        }
        arguments.add(Timestamp.from(clock.instant()));
        var found = jdbc.query("""
                select c.entra_object_id, g.github_user_id
                  from ingestion.github_connections c
                  join ingestion.github_connection_generations g on g.connection_id = c.id and g.id = c.active_generation_id
                 where c.tenant_id = ? and (c.entra_object_id, g.github_user_id) in (%s)
                   and g.state = 'ACTIVE' and g.refresh_expires_at > ?"""
                        .formatted(String.join(", ", Collections.nCopies(request.principals().size(), "(?, ?)"))),
                (row, index) -> new Principal(row.getObject("entra_object_id", UUID.class), row.getString("github_user_id")),
                arguments.toArray());
        var active = new HashSet<Principal>();
        for (var principal : found) {
            if (!request.principals().contains(principal)) { throw new IllegalStateException("Unrequested principal"); }
            active.add(principal);
        }
        return new Observation<>(request, Set.copyOf(active), clock.instant());
    }

    @Override public String toString() { return "JdbcPipesorConnections[configured]"; }
}
