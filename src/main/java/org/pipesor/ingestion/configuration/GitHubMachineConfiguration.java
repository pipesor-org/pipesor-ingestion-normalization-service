package org.pipesor.ingestion.configuration;

import java.time.Clock;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubAppJwtSigner;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport;
import org.pipesor.ingestion.shared.security.TransportPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Read-only GitHub App installation access for capture, bounded by the operator authority file. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GitHubProperties.class, CaptureProperties.class})
public class GitHubMachineConfiguration {
    @Bean
    GitHubMachineTransport.ArchiveLimits captureArchiveLimits(CaptureProperties capture) {
        var archive = capture.archive();
        return new GitHubMachineTransport.ArchiveLimits(archive.maxCompressedBytes(), archive.maxExpandedBytes(),
                archive.maxEntries(), archive.maxStepBytes());
    }

    @Bean
    GitHubMachineTransport githubMachineTransport(GitHubProperties github, TransportPolicy transport, GitHubFileAuthority authority,
            GitHubAppJwtSigner signer, GitHubMachineTransport.ArchiveLimits archive, Clock clock) {
        var limits = github.limits();
        return new GitHubMachineTransport(GitHubConfiguration.http(limits.machineTimeout()), transport.require(github.apiBaseUrl()),
                authority, signer, github.appId(), github.apiVersion(), limits.machineTimeout(), limits.machinePageSize(),
                limits.machineMaxPages(), limits.machineMaxJobs(), limits.machineMaxResponseBytes(), limits.machineMaxLogBytes(),
                archive, limits.downloadHosts(), clock);
    }
}
