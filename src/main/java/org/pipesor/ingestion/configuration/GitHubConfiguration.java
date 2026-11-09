package org.pipesor.ingestion.configuration;

import java.time.Clock;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.InstallationAdmission;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GitHubConfiguration {
    @Bean
    Clock githubClock() { return Clock.systemUTC(); }

    @Bean
    GitHubQueries githubQueries(CurrentGitHubLink links, InstallationAdmission admission,
                                ReadOnlyGitHub github, Clock clock) {
        return new GitHubQueryHandler(links, admission, github, clock);
    }
}
