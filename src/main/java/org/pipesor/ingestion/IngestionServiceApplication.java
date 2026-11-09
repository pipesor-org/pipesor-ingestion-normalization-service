package org.pipesor.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IngestionServiceApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(IngestionServiceApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
