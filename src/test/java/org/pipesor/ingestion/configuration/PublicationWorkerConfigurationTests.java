package org.pipesor.ingestion.configuration;

import java.time.Clock;
import java.util.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.application.commandhandlers.*;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PublicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationWorkerConfigurationTests {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(PublicationWorkerConfiguration.class).withInitializer(c->c.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()));
    private static final List<String> PROPS=List.of("ingestion.publication-worker.enabled=true","ingestion.publication-worker.batch-size=3","ingestion.publication-worker.lease=30s","ingestion.publication-worker.retry-delay=10s","ingestion.publication-worker.budget=2s","ingestion.publication-worker.interval=1s","ingestion.publication-worker.shutdown-timeout=1s");
    @Test void disabledHasNoWorkerOrSchedulerOrRequiredPorts() {
        runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(PublicationWorker.class).isEmpty());assertTrue(c.getBeansOfType(PublicationRunner.class).isEmpty());});
    }
    @Test void explicitValidConfigurationComposesWorkerAndManagedScheduler() {
        dependencies().withPropertyValues(PROPS.toArray(String[]::new)).run(c->{
            assertNull(c.getStartupFailure());assertNotNull(c.getBean(PublicationWorker.class));assertTrue(c.getBean(PublicationRunner.class).isRunning());
        });
    }
    @ParameterizedTest @ValueSource(strings={"batch-size","lease","retry-delay","budget","interval","shutdown-timeout"})
    void everyEnabledBudgetIsRequired(String missing) {
        var properties=new ArrayList<>(PROPS);properties.removeIf(p->p.startsWith("ingestion.publication-worker."+missing+"="));
        dependencies().withPropertyValues(properties.toArray(String[]::new)).run(c->{
            var failure=assertInstanceOf(org.springframework.beans.factory.UnsatisfiedDependencyException.class,c.getStartupFailure());
            assertTrue(failure.getMessage().contains("ingestion.publication-worker."+missing));
            assertTrue(failure.getMessage().contains("Failed to convert"));
        });
    }
    @ParameterizedTest @ValueSource(strings={"batch-size=0","lease=0s","retry-delay=-1s","budget=0s","interval=0s","shutdown-timeout=0s"})
    void invalidEnabledBudgetsFailStartup(String invalid) {
        var properties=new ArrayList<>(PROPS);var key=invalid.substring(0,invalid.indexOf('='));properties.removeIf(p->p.startsWith("ingestion.publication-worker."+key+"="));properties.add("ingestion.publication-worker."+invalid);
        dependencies().withPropertyValues(properties.toArray(String[]::new)).run(c->{
            var cause=c.getStartupFailure();assertNotNull(cause);
            while(cause.getCause()!=null)cause=cause.getCause();
            assertEquals(IllegalArgumentException.class,cause.getClass());
            assertEquals(switch(key) {
                case "batch-size" -> "Positive publication batch required";
                case "interval","shutdown-timeout" -> "Explicit publication interval required";
                default -> "Positive publication durations required";
            },cause.getMessage());
        });
    }
    @Test void enabledWithoutActualCaptureHandlerFailsStartup() {
        runner.withBean(EntityManagerFactory.class,()->mock(EntityManagerFactory.class))
                .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).withBean(Clock.class,Clock::systemUTC)
                .withPropertyValues(PROPS.toArray(String[]::new)).run(c->{
                    var cause=c.getStartupFailure();assertNotNull(cause);
                    while(cause.getCause()!=null)cause=cause.getCause();
                    assertEquals(CaptureHandler.class,assertInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,cause).getBeanType());
                });
    }
    private ApplicationContextRunner dependencies(){return runner.withBean(EntityManagerFactory.class,()->mock(EntityManagerFactory.class)).withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class)).withBean(CaptureHandler.class,()->mock(CaptureHandler.class)).withBean(Clock.class,Clock::systemUTC);}
}
