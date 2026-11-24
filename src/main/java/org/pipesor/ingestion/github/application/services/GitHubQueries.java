package org.pipesor.ingestion.github.application.services;

import org.pipesor.ingestion.github.application.queries.DiscoverRepositoriesQuery;
import org.pipesor.ingestion.github.application.queries.DiscoverRepositoryPageQuery;
import org.pipesor.ingestion.github.application.queries.DiscoverWorkflowPageQuery;
import org.pipesor.ingestion.github.application.queries.DiscoverWorkflowsQuery;
import org.pipesor.ingestion.github.application.queries.GitHubViews;
import org.pipesor.ingestion.github.application.queries.ListCollaboratorsQuery;
import org.pipesor.ingestion.github.application.queries.SelectWorkflowQuery;
import org.pipesor.ingestion.github.application.queries.VerifyAccessQuery;

public interface GitHubQueries {
    GitHubViews.Access verifyAccess(VerifyAccessQuery query);
    GitHubViews.Repositories discoverRepositories(DiscoverRepositoriesQuery query);
    /** ADR-024: the user's pipesor installations and per-repository admission, reconciled with GitHub. */
    GitHubViews.Installations listInstallations(DiscoverRepositoriesQuery query);
    GitHubViews.Workflows discoverWorkflows(DiscoverWorkflowsQuery query);
    /** Paged form of {@link #discoverRepositories}: reconciles admissions (ADR-024) on every page, then filters and slices. */
    GitHubViews.RepositoryPage discoverRepositoryPage(DiscoverRepositoryPageQuery query);
    /** Paged form of {@link #discoverWorkflows}. */
    GitHubViews.WorkflowPage discoverWorkflowPage(DiscoverWorkflowPageQuery query);
    GitHubViews.Selection selectWorkflow(SelectWorkflowQuery query);
    GitHubViews.Runs listRuns(org.pipesor.ingestion.github.application.queries.ListRunsQuery query);
    /** One GitHub page of collaborators, only for a current administrator of the repository, with their Pipesor link state. */
    GitHubViews.Collaborators listCollaborators(ListCollaboratorsQuery query);
}
