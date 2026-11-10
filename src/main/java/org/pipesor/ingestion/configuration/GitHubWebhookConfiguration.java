package org.pipesor.ingestion.configuration;

import java.time.Duration;
import java.util.List;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.pipesor.ingestion.capture.interfaces.webhook.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="ingestion.github-webhook.enabled",havingValue="true")
public class GitHubWebhookConfiguration {
    public static final String PATH="/webhooks/v1/github/workflow-runs";
    @Bean ServletRegistrationBean<GitHubWebhookServlet> githubWebhookServlet(GitHubWorkflowWebhook ingress,Environment env) {
        int bytes=number(env,"ingestion.github-machine.max-body-bytes"),headers=number(env,"ingestion.github-webhook.max-header-chars"),capacity=number(env,"ingestion.github-webhook.max-in-flight");
        Duration timeout;
        try{timeout=org.springframework.boot.convert.DurationStyle.detectAndParse(env.getRequiredProperty("ingestion.github-webhook.request-timeout"));timeout.toNanos();if(timeout.isNegative()||timeout.toMillis()<1)throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid or missing webhook setting: ingestion.github-webhook.request-timeout");}
        var registration=new ServletRegistrationBean<>(new GitHubWebhookServlet(ingress,bytes,headers,timeout,capacity),PATH);registration.setAsyncSupported(true);return registration;
    }
    @Bean @Order(0) SecurityFilterChain githubWebhookSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(PATH).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable())
                .formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable()).csrf(c->c.disable())
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.POST,PATH).permitAll().anyRequest().denyAll())
                .exceptionHandling(e->e.authenticationEntryPoint((q,r,f)->r.setStatus(403)).accessDeniedHandler((q,r,f)->r.setStatus(403))).build();
    }
    @Bean OpenApiCustomizer githubWebhookOpenApi(Environment env) {
        int bytes=number(env,"ingestion.github-machine.max-body-bytes"),headers=number(env,"ingestion.github-webhook.max-header-chars");
        return api->{
            var responses=new ApiResponses();GitHubWebhookServlet.RESPONSES.forEach((status,description)->responses.addApiResponse(status.toString(),new ApiResponse().description(description)));
            var operation=new Operation().operationId("receiveGitHubWorkflowRun").summary("Receive a signed GitHub delivery").security(List.of()).responses(responses)
                    .description("Vendor HMAC over original UTF-8 body bytes. No Entra bearer, browser identity or static API-key authentication. A 503 may follow an uncertain/late commit; retries use durable deduplication.")
                    .addParametersItem(header(GitHubWebhookServlet.SIGNATURE_HEADER,true,GitHubWebhookServlet.SIGNATURE_PATTERN,"Per-delivery SHA-256 HMAC computed over the exact transmitted bytes; never send the webhook secret."))
                    .addParametersItem(header(GitHubWebhookServlet.EVENT_HEADER,true,GitHubWebhookServlet.EVENT_PATTERN,"GitHub event header; signed selected content cannot be hidden by changing this unsigned header."))
                    .addParametersItem(header(GitHubWebhookServlet.DELIVERY_HEADER,true,GitHubWebhookServlet.CONTEXT_PATTERN,"Auxiliary delivery identifier; does not establish freshness or authority."))
                    .addParametersItem(header(GitHubWebhookServlet.CORRELATION_HEADER,false,GitHubWebhookServlet.CONTEXT_PATTERN,"Canonical correlation; assigned by the service if absent."))
                    .requestBody(new RequestBody().required(true).description("Original JSON object, UTF-8 only; compressed encodings are rejected. workflow_run/completed is selected; genuinely signed ping and unselected deliveries are acknowledged.").content(new Content().addMediaType("application/json",new io.swagger.v3.oas.models.media.MediaType().schema(new ObjectSchema().additionalProperties(true)))));
            operation.addExtension("x-max-body-bytes",bytes);operation.addExtension("x-max-header-chars",headers);
            api.path(PATH,new PathItem().post(operation));
        };
    }
    private static Parameter header(String name,boolean required,String pattern,String description){return new Parameter().name(name).in("header").required(required).description(description).schema(new StringSchema().pattern("^"+pattern+"$"));}
    private static int number(Environment env,String key){try{int value=env.getRequiredProperty(key,Integer.class);if(value<1)throw new IllegalArgumentException();return value;}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid or missing webhook setting: "+key);}}
}
