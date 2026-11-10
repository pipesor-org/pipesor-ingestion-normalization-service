package org.pipesor.ingestion.configuration;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.infrastructure.credentials.KeyVaultAppMaterial;
import org.pipesor.ingestion.capture.infrastructure.github.*;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook;
import org.pipesor.ingestion.github.application.ports.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubMachineConfigurationTests {
    @TempDir Path directory;
    Path file;
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(GitHubMachineConfiguration.class);
    static final Map<String,String> VALUES=Map.ofEntries(
            Map.entry("app-id","120"),Map.entry("api-version","2026-03-10"),Map.entry("max-file-bytes","10000"),Map.entry("max-entries","30"),
            Map.entry("vault-url","https://unit.vault.azure.net"),Map.entry("key-secret-name","unit-rsa-key"),Map.entry("key-version","rsa-version-1"),Map.entry("webhook-secret-name","unit-hook"),Map.entry("webhook-version","hook-version-2"),Map.entry("max-secret-bytes","20000"),Map.entry("vault-timeout","2s"),
            Map.entry("jwt-ttl","5m"),Map.entry("jwt-issued-at-skew","30s"),Map.entry("operation-budget","3s"),Map.entry("page-size","20"),Map.entry("max-pages","4"),Map.entry("max-jobs","40"),Map.entry("max-response-bytes","50000"),Map.entry("max-log-bytes","60000"),Map.entry("download-hosts","logs.example"),Map.entry("webhook-tenant","11111111-1111-4111-8111-111111111111"),Map.entry("capture-domain","pilot"),Map.entry("max-body-bytes","10000"));
    @BeforeEach void createFile() throws Exception {file=directory.resolve("authority.json");Files.writeString(file,"{\"formatVersion\":\"1\",\"revision\":\"deny-all\",\"humanAdmissions\":[],\"machineScopes\":[]}");}
    ApplicationContextRunner deps(){return runner.withBean(Clock.class,Clock::systemUTC).withBean(CaptureHandler.class,()->mock(CaptureHandler.class));}
    Map<String,String> settings(){var values=new HashMap<>(VALUES);values.put("authority-file",file.toString());return values;}
    String[] props(Map<String,String> values){var properties=new ArrayList<String>();properties.add("ingestion.github-machine.enabled=true");values.forEach((k,v)->properties.add("ingestion.github-machine."+k+"="+v));return properties.toArray(String[]::new);}
    @Test void disabledCompositionNeedsNoSettingsClockHandlerOrRemoteMaterial(){runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(GitHubFileAuthority.class).isEmpty());assertTrue(c.getBeansOfType(KeyVaultAppMaterial.class).isEmpty());assertTrue(c.getBeansOfType(GitHubMachineTransport.class).isEmpty());assertTrue(c.getBeansOfType(GitHubWorkflowWebhook.class).isEmpty());});}
    @Test void completeConfigurationComposesActualAdaptersButDoesNotReadSecretsOrAuthorizeEmptyScopes(){
        deps().withPropertyValues(props(settings())).run(c->{
            assertNull(c.getStartupFailure());assertInstanceOf(GitHubFileAuthority.class,c.getBean(CurrentMachineScope.class));assertSame(c.getBean(CurrentMachineScope.class),c.getBean(InstallationAdmission.class));assertSame(c.getBean(CurrentMachineScope.class),c.getBean(GitHubRepositorySelection.class));
            assertNotNull(c.getBean(KeyVaultAppMaterial.class));assertNotNull(c.getBean(GitHubAppJwtSigner.class));assertNotNull(c.getBean(GitHubMachineTransport.class));assertNotNull(c.getBean(GitHubWorkflowWebhook.class));
            c.getBean(GitHubFileAuthority.class).validate();verifyNoInteractions(c.getBean(CaptureHandler.class));assertFalse(c.getBean(GitHubMachineConfiguration.Settings.class).toString().contains("unit-rsa-key"));
        });
    }
    @Test void everyRequiredPropertyMissingProducesItsOwnFixedDiagnostic(){
        for(var missing:settings().keySet()){
            var values=settings();values.remove(missing);
            deps().withPropertyValues(props(values)).run(c->{var root=root(c.getStartupFailure());assertEquals(IllegalArgumentException.class,root.getClass());assertEquals("Invalid or missing GitHub machine setting: "+missing,root.getMessage());assertNull(root.getCause());});
        }
    }
    @ParameterizedTest @ValueSource(strings={"max-file-bytes=private-invalid","max-entries=0","key-version=latest","webhook-version=latest","key-secret-name=private/invalid","webhook-secret-name=private/invalid","vault-url=https://private-invalid/path","vault-timeout=private-invalid","operation-budget=0s","download-hosts=*.private-invalid","webhook-tenant=private-invalid","app-id=private-invalid","api-version=private-invalid"})
    void invalidValuesNeverAppearInConfigurationErrorsOrPrivateCauses(String setting){
        var key=setting.substring(0,setting.indexOf('='));var values=settings();values.put(key,setting.substring(setting.indexOf('=')+1));
        deps().withPropertyValues(props(values)).run(c->{var root=root(c.getStartupFailure());assertEquals("Invalid or missing GitHub machine setting: "+key,root.getMessage());assertNull(root.getCause());assertFalse(c.getStartupFailure().toString().contains("private-invalid"));});
    }
    @Test void invalidOrMissingAuthorityFileFailsStartupWithoutLastGoodDefault() throws Exception {
        Files.writeString(file,"private-source-broken");deps().withPropertyValues(props(settings())).run(c->{var cause=assertInstanceOf(CaptureFailure.class,root(c.getStartupFailure()));assertEquals(CaptureFailure.Reason.UNAVAILABLE,cause.reason());assertNull(cause.getCause());});
        Files.delete(file);deps().withPropertyValues(props(settings())).run(c->assertEquals(CaptureFailure.Reason.UNAVAILABLE,assertInstanceOf(CaptureFailure.class,root(c.getStartupFailure())).reason()));
    }
    @Test void enablingWithoutCaptureHandlerCannotInstallAPermissiveFallback(){
        runner.withBean(Clock.class,Clock::systemUTC).withPropertyValues(props(settings())).run(c->{var missing=assertInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,root(c.getStartupFailure()));assertEquals(CaptureHandler.class,missing.getBeanType());});
    }
    @Test void usingTheSameSecretObjectForKeyAndWebhookFailsStartup(){
        var values=settings();values.put("webhook-secret-name",values.get("key-secret-name"));deps().withPropertyValues(props(values)).run(c->{var error=root(c.getStartupFailure());assertEquals(IllegalArgumentException.class,error.getClass());assertEquals("Invalid App material configuration",error.getMessage());});
    }
    static Throwable root(Throwable failure){assertNotNull(failure);while(failure.getCause()!=null)failure=failure.getCause();return failure;}
}
