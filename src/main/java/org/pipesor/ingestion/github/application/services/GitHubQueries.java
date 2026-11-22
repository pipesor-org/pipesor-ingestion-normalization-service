package org.pipesor.ingestion.github.application.services;

import org.pipesor.ingestion.github.application.queries.DiscoverRepositoriesQuery;
import org.pipesor.ingestion.github.application.queries.DiscoverWorkflowsQuery;
import org.pipesor.ingestion.github.application.queries.GitHubViews;
import org.pipesor.ingestion.github.application.queries.SelectWorkflowQuery;
import org.pipesor.ingestion.github.application.queries.VerifyAccessQuery;

public interface GitHubQueries {
    GitHubViews.Access verifyAccess(VerifyAccessQuery query);
    GitHubViews.Repositories discoverRepositories(DiscoverRepositoriesQuery query);
    GitHubViews.Workflows discoverWorkflows(DiscoverWorkflowsQuery query);
    GitHubViews.Selection selectWorkflow(SelectWorkflowQuery query);
    GitHubViews.Runs listRuns(org.pipesor.ingestion.github.application.queries.ListRunsQuery query);
}
