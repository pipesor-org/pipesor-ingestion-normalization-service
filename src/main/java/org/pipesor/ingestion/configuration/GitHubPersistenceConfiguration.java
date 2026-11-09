package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.pipesor.ingestion.github.application.commandhandlers.GitHubConnectionHandler;
import org.pipesor.ingestion.github.application.ports.ConnectionRepository;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubConnectionQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubConnectionQueries;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.JpaConnectionRepository;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.JpaConnectionStatusReader;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.TransactionalGitHubConnections;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/** Metadata commands only. Database rows do not establish operational authorization after restore. */
@Configuration(proxyBeanMethods = false)
public class GitHubPersistenceConfiguration {
    @Bean
    ConnectionRepository connectionRepository(EntityManagerFactory factory) {
        return new JpaConnectionRepository(SharedEntityManagerCreator.createSharedEntityManager(factory));
    }
    @Bean
    GitHubConnections githubConnections(ConnectionRepository repository, Clock clock, PlatformTransactionManager manager) {
        return new TransactionalGitHubConnections(new GitHubConnectionHandler(repository, clock), manager);
    }
    @Bean
    ConnectionStatusReader connectionStatusReader(EntityManagerFactory factory, PlatformTransactionManager manager) {
        return new JpaConnectionStatusReader(SharedEntityManagerCreator.createSharedEntityManager(factory), manager);
    }
    @Bean
    GitHubConnectionQueries githubConnectionQueries(ConnectionStatusReader reader) {
        return new GitHubConnectionQueryHandler(reader);
    }
}
