package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;

/** Paged workflow discovery of one readable repository; the filter matches the workflow name or file. */
public record DiscoverWorkflowPageQuery(DiscoverWorkflowsQuery discovery, DiscoveryPage page) {
    public DiscoverWorkflowPageQuery {
        Objects.requireNonNull(discovery, "Discovery required");
        Objects.requireNonNull(page, "Page required");
    }
}
