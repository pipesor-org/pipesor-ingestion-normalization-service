package org.pipesor.ingestion.configuration;

import java.time.Clock;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.InstallationAdmission;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
public class GitHubConfiguration {
    @Bean
    Clock githubClock() { return Clock.systemUTC(); }

    @Bean
    GitHubQueries githubQueries(ObjectProvider<CurrentGitHubLink> links, ObjectProvider<InstallationAdmission> admission,
                                ObjectProvider<ReadOnlyGitHub> github, Clock clock) {
        var currentLinks = links.getIfAvailable();
        var currentAdmission = admission.getIfAvailable();
        var provider = github.getIfAvailable();
        if (currentLinks == null || currentAdmission == null || provider == null) { return null; }
        return new GitHubQueryHandler(currentLinks, currentAdmission, provider, clock);
    }
}
