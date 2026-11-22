package org.pipesor.ingestion.configuration;

import java.time.Clock;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.interfaces.rest.MachineScopeServlet;
import org.pipesor.ingestion.shared.security.PlatformSecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pipeline's machine-scope check (route catalog: pipeline, Pipeline.MachineScope.Check) over the authority file. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProperties.class)
public class MachineScopeConfiguration {
    @Bean
    ServletRegistrationBean<MachineScopeServlet> machineScopeServlet(GitHubProperties github, PlatformSecurityProperties platform,
            GitHubFileAuthority authority, Clock clock) {
        var limits = github.limits();
        var settings = new MachineScopeServlet.Settings(platform.tenantId(), github.appId(), github.captureDomain(),
                limits.machineScopeMaxRequestBytes(), limits.machineScopeMaxInFlight(), limits.machineScopeTimeout());
        var registration = new ServletRegistrationBean<>(new MachineScopeServlet(settings, authority, clock), WebConfiguration.MACHINE_SCOPE_PATH);
        registration.setAsyncSupported(true);
        return registration;
    }
}
