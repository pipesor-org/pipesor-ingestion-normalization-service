package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;

public record DiscoverRepositoriesQuery(AccessContext context) {
    public DiscoverRepositoriesQuery {
        Objects.requireNonNull(context, "Context required");
        if (context.operation() != AccessOperation.DISCOVER) {
            throw new IllegalArgumentException("Discovery operation required");
        }
    }
}
