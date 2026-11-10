package org.pipesor.ingestion.configuration;

import com.azure.security.keyvault.secrets.SecretClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.infrastructure.credentials.KeyVaultAppMaterial;
import org.pipesor.ingestion.capture.infrastructure.github.*;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook;
import org.pipesor.ingestion.github.infrastructure.custody.KeyVaultCredentialCustody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Explicit local composition only; no route, scheduler, secret provisioning or restored human authorization. */
/** shortcut: one configured pilot context; define multi-context routing before enabling additional capture domains. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ingestion.github-machine.enabled",havingValue="true")
public class GitHubMachineConfiguration {
    private static final String PREFIX="ingestion.github-machine.";
    record Settings(String appId,String version,Path file,int maxFileBytes,int maxEntries,URI vault,
                    KeyVaultAppMaterial.Reference key,KeyVaultAppMaterial.Reference webhook,int maxSecretBytes,
                    Duration vaultTimeout,Duration jwtTtl,Duration skew,Duration operationBudget,int pageSize,int maxPages,
                    int maxJobs,int maxResponseBytes,int maxLogBytes,Set<String> downloadHosts,UUID tenant,String domain,int maxBodyBytes) {
        @Override public String toString(){return "GitHubMachineSettings[private]";}
    }
    @Bean Settings githubMachineSettings(Environment env) {
        return new Settings(value(env,"app-id",s->decimal(s)),value(env,"api-version",s->matches(s,"[0-9]{4}-[0-9]{2}-[0-9]{2}")),value(env,"authority-file",Path::of),
                number(env,"max-file-bytes"),number(env,"max-entries"),value(env,"vault-url",s->{var u=URI.create(s);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getPort()!=-1||u.getQuery()!=null||u.getFragment()!=null||!(u.getRawPath().isEmpty()||u.getRawPath().equals("/")))throw new IllegalArgumentException();return u;}),
                reference(env,"key-secret-name","key-version"),reference(env,"webhook-secret-name","webhook-version"),number(env,"max-secret-bytes"),duration(env,"vault-timeout",false),duration(env,"jwt-ttl",false),duration(env,"jwt-issued-at-skew",true),duration(env,"operation-budget",false),
                number(env,"page-size"),number(env,"max-pages"),number(env,"max-jobs"),number(env,"max-response-bytes"),number(env,"max-log-bytes"),value(env,"download-hosts",s->{var hosts=new HashSet<String>();for(var host:s.split(",",-1)){host=host.strip().toLowerCase(Locale.ROOT);matches(host,"[A-Za-z0-9][A-Za-z0-9.-]*");if(host.endsWith(".")||!hosts.add(host))throw new IllegalArgumentException();}return Set.copyOf(hosts);}),
                value(env,"webhook-tenant",s->{var u=UUID.fromString(s);if(!u.toString().equalsIgnoreCase(s)||u.equals(new UUID(0,0)))throw new IllegalArgumentException();return u;}),value(env,"capture-domain",s->matches(s,"[A-Za-z0-9._:-]{1,128}")),number(env,"max-body-bytes"));
    }
    @Bean GitHubFileAuthority githubFileAuthority(Settings s,Clock clock) {
        var authority=new GitHubFileAuthority(s.file(),s.appId(),clock,s.maxFileBytes(),s.maxEntries());authority.validate();return authority;
    }
    @Bean SecretClient githubAppSecretClient(Settings s) {return KeyVaultCredentialCustody.managedIdentityClient(s.vault(),s.vaultTimeout());}
    @Bean KeyVaultAppMaterial githubAppMaterial(Settings s,SecretClient githubAppSecretClient) {return new KeyVaultAppMaterial(githubAppSecretClient,s.vault(),s.appId(),s.key(),s.webhook(),s.maxSecretBytes());}
    @Bean GitHubAppJwtSigner githubAppJwtSigner(Settings s,KeyVaultAppMaterial material,Clock clock) {return new GitHubAppJwtSigner(s.appId(),material::signingKey,clock,s.jwtTtl(),s.skew());}
    @Bean GitHubMachineTransport githubMachineTransport(Settings s,GitHubFileAuthority authority,GitHubAppJwtSigner signer,Clock clock) {return new GitHubMachineTransport(authority,signer,s.appId(),s.version(),s.operationBudget(),s.pageSize(),s.maxPages(),s.maxJobs(),s.maxResponseBytes(),s.maxLogBytes(),s.downloadHosts(),clock);}
    @Bean GitHubWorkflowWebhook githubWorkflowWebhook(Settings s,GitHubFileAuthority authority,KeyVaultAppMaterial material,CaptureHandler handler) {
        var hook=new GitHubWorkflowWebhook.HookContext(s.domain(),s.tenant(),s.appId());
        return new GitHubWorkflowWebhook(hook,material::webhookSecret,authority.webhook(hook),Set.of("completed"),s.maxBodyBytes(),handler);
    }
    private static KeyVaultAppMaterial.Reference reference(Environment env,String name,String version){return new KeyVaultAppMaterial.Reference(value(env,name,s->matches(s,"[A-Za-z0-9-]{1,127}")),value(env,version,s->{matches(s,"[A-Za-z0-9-]{1,128}");if(s.equalsIgnoreCase("latest"))throw new IllegalArgumentException();return s;}));}
    private static String decimal(String s){return matches(s,"[1-9][0-9]*");}
    private static String matches(String s,String pattern){if(!s.matches(pattern))throw new IllegalArgumentException();return s;}
    private static int number(Environment env,String key){return value(env,key,s->{int n=Integer.parseInt(s);if(n<1)throw new IllegalArgumentException();return n;});}
    private static Duration duration(Environment env,String key,boolean allowZero){return value(env,key,s->{var d=org.springframework.boot.convert.DurationStyle.detectAndParse(s);if(d.isNegative()||!allowZero&&d.isZero())throw new IllegalArgumentException();d.toNanos();return d;});}
    private static <T>T value(Environment env,String key,Function<String,T> convert){try {var raw=env.getRequiredProperty(PREFIX+key);if(raw.isBlank())throw new IllegalArgumentException();return convert.apply(raw);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid or missing GitHub machine setting: "+key);}}
}
