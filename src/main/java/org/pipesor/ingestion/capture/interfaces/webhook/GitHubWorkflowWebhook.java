package org.pipesor.ingestion.capture.interfaces.webhook;

import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Accept;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Private ingress, not an HTTP endpoint. Headers never select a secret, tenant or capture authority. */
public final class GitHubWorkflowWebhook {
    public static final class InvalidSignature extends RuntimeException {
        public InvalidSignature(){super("Invalid webhook signature",null,false,false);}
    }
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record HookContext(String captureDomain,UUID tenantId,String appId) {
        public HookContext { CaptureScope.safe(captureDomain);Objects.requireNonNull(tenantId);if(tenantId.equals(new UUID(0,0)))throw new IllegalArgumentException("Webhook tenant required");CaptureScope.external(appId); }
        @Override public String toString(){return "HookContext[private]";}
    }
    /** Signed source fields only; workflow sender is deliberately not an AIOps actor. */
    public record Source(String action,String installationId,String accountId,String repositoryId,String workflowId,String runId,int runAttempt) {
        @Override public String toString(){return "WebhookSource[private]";}
    }
    public record Authorization(CaptureScope scope,Instant replayUntil) {
        public Authorization { Objects.requireNonNull(scope);Objects.requireNonNull(replayUntil); }
        @Override public String toString(){return "WebhookAuthorization[private]";}
    }
    private final HookContext hook;
    private final Supplier<byte[]> secret;
    private final Function<Source,Authorization> resolver;
    private final Set<String> actions;
    private final int maxBodyBytes;
    private final CaptureHandler handler;
    public GitHubWorkflowWebhook(HookContext hook,Supplier<byte[]> secret,Function<Source,Authorization> resolver,
            Set<String> allowedActions,int maxBodyBytes,CaptureHandler handler) {
        this.hook=Objects.requireNonNull(hook);this.secret=Objects.requireNonNull(secret);this.resolver=Objects.requireNonNull(resolver);
        this.actions=Set.copyOf(allowedActions);this.handler=Objects.requireNonNull(handler);
        if(actions.isEmpty() || !Set.of("requested","in_progress","completed").containsAll(actions) || maxBodyBytes<1)
            throw new IllegalArgumentException("Explicit webhook actions and byte budget required");
        this.maxBodyBytes=maxBodyBytes;
    }
    /** Returns only after the existing ledger acceptance transaction commits; no raw body is retained. */
    public CaptureReceipt accept(byte[] originalBody,String signature,String event,String deliveryId,String correlationId) {
        var body=authenticateSnapshot(originalBody,signature);
        CaptureScope.safe(deliveryId);CaptureScope.safe(correlationId);
        if(!"workflow_run".equals(event))throw new CaptureFailure(INVALID_INPUT);
        return acceptAuthenticated(body,source(body),deliveryId,correlationId);
    }
    private CaptureReceipt acceptAuthenticated(byte[] body,Source source,String deliveryId,String correlationId) {
        Authorization authorization;
        try { authorization=resolver.apply(source); }
        catch(RuntimeException unavailable){throw new CaptureFailure(UNAVAILABLE);}
        if(authorization==null)throw new CaptureFailure(SCOPE_CHANGED);
        var approved=authorization.scope();
        var authenticated=new CaptureScope(hook.captureDomain(),hook.tenantId(),hook.appId(),source.accountId(),
                source.installationId(),source.repositoryId(),source.workflowId(),approved.scopeRevision());
        if(!authenticated.equals(approved))throw new CaptureFailure(SCOPE_CHANGED);
        try {
            return handler.accept(new Accept(authenticated,approved,"github_actions",deliveryId,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),source.runId(),source.runAttempt(),
                    correlationId,authorization.replayUntil()));
        } catch(CaptureFailure fixed){
            if(fixed.reason()==INVALID_INPUT)throw new CaptureFailure(UNAVAILABLE);
            throw fixed;
        }
        catch(NoSuchAlgorithmException | RuntimeException unavailable){throw new CaptureFailure(UNAVAILABLE);}
    }
    /** Selected deliveries commit; validly signed unselected deliveries are acknowledged without a capture intent. */
    public Optional<CaptureReceipt> receive(byte[] originalBody,String signature,String event,String deliveryId,String correlationId) {
        var body=authenticateSnapshot(originalBody,signature);
        CaptureScope.safe(deliveryId);CaptureScope.safe(correlationId);
        var root=parse(body);
        if(root.has("workflow_run") && !"workflow_run".equals(event))throw new CaptureFailure(INVALID_INPUT);
        if(!"workflow_run".equals(event))return Optional.empty();
        if(!actions.contains(text(root,"action")))return Optional.empty();
        return Optional.of(acceptAuthenticated(body,source(body),deliveryId,correlationId));
    }
    private byte[] authenticateSnapshot(byte[] originalBody,String signature) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new CaptureFailure(INVALID_STATE);
        if(originalBody==null || originalBody.length==0 || originalBody.length>maxBodyBytes)throw new CaptureFailure(INVALID_INPUT);
        var body=originalBody.clone();authenticate(body,signature);return body;
    }
    private static JsonNode parse(byte[] body) {
        try {
            var text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body));
            var parsed=JSON.readTree(text.toString());if(parsed==null || !parsed.isObject())throw new CaptureFailure(INVALID_INPUT);return parsed;
        }catch(CaptureFailure fixed){throw fixed;}
        catch(java.nio.charset.CharacterCodingException | RuntimeException malformed){throw new CaptureFailure(INVALID_INPUT);}
    }
    private void authenticate(byte[] body,String signature) {
        if(signature==null || !signature.matches("sha256=[0-9a-fA-F]{64}"))throw new InvalidSignature();
        byte[] key=null;
        try {
            var supplied=secret.get();
            if(supplied==null || supplied.length==0)throw new CaptureFailure(UNAVAILABLE);
            key=supplied.clone();
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7))))throw new InvalidSignature();
        } catch(InvalidSignature | CaptureFailure fixed){throw fixed;}
        catch(RuntimeException | GeneralSecurityException unavailable){throw new CaptureFailure(UNAVAILABLE);}
        finally {if(key!=null)Arrays.fill(key,(byte)0);}
    }
    private Source source(byte[] body) {
        try {
            var root=parse(body);
            var action=text(root,"action");if(!actions.contains(action))throw new CaptureFailure(INVALID_INPUT);
            var repo=object(root,"repository");var run=object(root,"workflow_run");var runRepo=object(run,"repository");
            var repoId=id(repo,"id");var owner=id(object(repo,"owner"),"id");
            if(!repoId.equals(id(runRepo,"id")) || !owner.equals(id(object(runRepo,"owner"),"id")))throw new CaptureFailure(INVALID_INPUT);
            var attempt=run.get("run_attempt");
            if(attempt==null || !attempt.isIntegralNumber() || !attempt.canConvertToInt() || attempt.intValue()<1)throw new CaptureFailure(INVALID_INPUT);
            return new Source(action,id(object(root,"installation"),"id"),owner,repoId,id(run,"workflow_id"),id(run,"id"),attempt.intValue());
        } catch(CaptureFailure fixed){throw fixed;}
        catch(RuntimeException malformed){throw new CaptureFailure(INVALID_INPUT);}
    }
    private static JsonNode object(JsonNode value,String field) {
        var result=value.get(field);if(result==null || !result.isObject())throw new CaptureFailure(INVALID_INPUT);return result;
    }
    private static String text(JsonNode value,String field) {
        var result=value.get(field);if(result==null || !result.isString() || result.asString().isBlank())throw new CaptureFailure(INVALID_INPUT);return result.asString();
    }
    private static String id(JsonNode value,String field) {
        var result=value.get(field);if(result==null || !result.isIntegralNumber())throw new CaptureFailure(INVALID_INPUT);
        return CaptureScope.external(result.asString());
    }
    @Override public String toString(){return "GitHubWorkflowWebhook[configured]";}
}
