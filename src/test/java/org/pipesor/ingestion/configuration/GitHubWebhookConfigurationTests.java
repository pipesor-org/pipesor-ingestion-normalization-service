package org.pipesor.ingestion.configuration;

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
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner().withUserConfiguration(Security.class,GitHubWebhookConfiguration.class);
    private static final List<String> SETTINGS=List.of("ingestion.github-webhook.enabled=true","ingestion.github-webhook.request-timeout=2s","ingestion.github-webhook.max-header-chars=512","ingestion.github-webhook.max-in-flight=1","ingestion.github-machine.max-body-bytes=2048");
    @Test void disabledInstallsNoWebhookServletAndRequiresNoIngress(){runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(ServletRegistrationBean.class).isEmpty());});}
    @Test void enabledRequiresTheActualPrivateIngress(){runner.withPropertyValues(SETTINGS.toArray(String[]::new)).run(c->{var cause=root(c.getStartupFailure());assertEquals(GitHubWorkflowWebhook.class,assertInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,cause).getBeanType());});}
    @Test void completeSettingsRegisterOnlyTheApprovedExactPath(){runner.withBean(GitHubWorkflowWebhook.class,()->mock(GitHubWorkflowWebhook.class)).withPropertyValues(SETTINGS.toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(Set.of(GitHubWebhookConfiguration.PATH),c.getBean(ServletRegistrationBean.class).getUrlMappings());verifyNoInteractions(c.getBean(GitHubWorkflowWebhook.class));});}
    @ParameterizedTest @ValueSource(strings={"ingestion.github-webhook.request-timeout","ingestion.github-webhook.max-header-chars","ingestion.github-webhook.max-in-flight","ingestion.github-machine.max-body-bytes"})
    void eachHttpBudgetIsRequired(String missing){var values=new ArrayList<>(SETTINGS);values.removeIf(s->s.startsWith(missing+"="));runner.withBean(GitHubWorkflowWebhook.class,()->mock(GitHubWorkflowWebhook.class)).withPropertyValues(values.toArray(String[]::new)).run(c->assertEquals("Invalid or missing webhook setting: "+missing,root(c.getStartupFailure()).getMessage()));}
    @ParameterizedTest @ValueSource(strings={"ingestion.github-webhook.request-timeout=0s","ingestion.github-webhook.request-timeout=private-invalid","ingestion.github-webhook.max-header-chars=0","ingestion.github-webhook.max-in-flight=0","ingestion.github-machine.max-body-bytes=private-invalid"})
    void invalidBudgetsFailWithFixedCauseWithoutRejectedValues(String input){var key=input.substring(0,input.indexOf('='));var values=new ArrayList<>(SETTINGS);values.removeIf(s->s.startsWith(key+"="));values.add(input);runner.withBean(GitHubWorkflowWebhook.class,()->mock(GitHubWorkflowWebhook.class)).withPropertyValues(values.toArray(String[]::new)).run(c->{var failure=root(c.getStartupFailure());assertEquals("Invalid or missing webhook setting: "+key,failure.getMessage());assertNull(failure.getCause());assertFalse(c.getStartupFailure().toString().contains("private-invalid"));});}
    static Throwable root(Throwable failure){assertNotNull(failure);while(failure.getCause()!=null)failure=failure.getCause();return failure;}
}
