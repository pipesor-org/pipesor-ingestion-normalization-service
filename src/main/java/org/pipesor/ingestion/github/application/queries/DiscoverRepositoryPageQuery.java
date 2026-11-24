package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;

/** Paged repository discovery: the same reconciled, admitted and readable set as the complete read, one page at a time. */
public record DiscoverRepositoryPageQuery(DiscoverRepositoriesQuery discovery, DiscoveryPage page) {
    public DiscoverRepositoryPageQuery {
        Objects.requireNonNull(discovery, "Discovery required");
        Objects.requireNonNull(page, "Page required");
    }
}
