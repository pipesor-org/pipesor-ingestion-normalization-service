package org.pipesor.ingestion.configuration;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubWebhookConfigurationTests {
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity static class Security {}
    private static final List<String> SETTINGS=GitHubTestSettings.properties(Path.of("/synthetic"));
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner().withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer()).withUserConfiguration(Security.class,GitHubWebhookConfiguration.class)
            .withPropertyValues(SETTINGS.toArray(String[]::new));
    @Test void disabledInstallsNoWebhookServletAndRequiresNoIngress(){runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(ServletRegistrationBean.class).isEmpty());});}
    @Test void enabledRequiresTheActualPrivateIngress(){runner.withPropertyValues("pipesor.github.webhook-enabled=true").run(c->{var cause=root(c.getStartupFailure());assertEquals(GitHubWorkflowWebhook.class,assertInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,cause).getBeanType());});}
    @Test void completeSettingsRegisterOnlyTheApprovedExactPath(){runner.withBean(GitHubWorkflowWebhook.class,()->mock(GitHubWorkflowWebhook.class)).withPropertyValues("pipesor.github.webhook-enabled=true").run(c->{assertNull(c.getStartupFailure());assertEquals(Set.of(GitHubWebhookConfiguration.PATH),c.getBean(ServletRegistrationBean.class).getUrlMappings());verifyNoInteractions(c.getBean(GitHubWorkflowWebhook.class));});}
    @ParameterizedTest @ValueSource(strings={"pipesor.github.limits.webhook-timeout=0s","pipesor.github.limits.webhook-max-header-chars=0","pipesor.github.limits.webhook-max-in-flight=0","pipesor.github.limits.webhook-max-body-bytes=-1"})
    void invalidBudgetsFailStartup(String input){runner.withBean(GitHubWorkflowWebhook.class,()->mock(GitHubWorkflowWebhook.class)).withPropertyValues("pipesor.github.webhook-enabled=true",input).run(c->assertNotNull(c.getStartupFailure()));}
    @Test void enablingTheWebhookWithoutASecretFailsStartup(){
        var values=new ArrayList<>(SETTINGS);values.removeIf(s->s.startsWith("pipesor.github.webhook-secret-file="));values.add("pipesor.github.webhook-enabled=true");
        new WebApplicationContextRunner().withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer()).withUserConfiguration(Security.class,GitHubWebhookConfiguration.class).withPropertyValues(values.toArray(String[]::new))
                .withBean(GitHubWorkflowWebhook.class,()->mock(GitHubWorkflowWebhook.class))
                .run(c->assertTrue(root(c.getStartupFailure()).getMessage().contains("webhook secret")));
    }
    static Throwable root(Throwable failure){assertNotNull(failure);while(failure.getCause()!=null)failure=failure.getCause();return failure;}
}
