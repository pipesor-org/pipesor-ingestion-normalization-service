package org.pipesor.ingestion.capture.infrastructure.github;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Private JWT supplier; key custody, attribution and rotation are explicit external responsibilities. */
public final class GitHubAppJwtSigner implements Supplier<GitHubMachineTransport.AppJwt> {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Base64.Encoder BASE64=Base64.getUrlEncoder().withoutPadding();
    private final String appId;
    private final Supplier<PrivateKey> keys;
    private final Clock clock;
    private final Duration ttl,issuedAtSkew;
    public GitHubAppJwtSigner(String appId,Supplier<PrivateKey> keys,Clock clock,Duration ttl,Duration issuedAtSkew) {
        this.appId=CaptureScope.external(appId);this.keys=Objects.requireNonNull(keys);this.clock=Objects.requireNonNull(clock);
        if(ttl==null || issuedAtSkew==null || ttl.isNegative() || ttl.isZero() || ttl.getNano()!=0 || ttl.compareTo(Duration.ofMinutes(10))>0
                || issuedAtSkew.isNegative() || issuedAtSkew.getNano()!=0)throw new IllegalArgumentException("Explicit whole-second GitHub JWT durations required");
        this.ttl=ttl;this.issuedAtSkew=issuedAtSkew;
    }
    @Override public GitHubMachineTransport.AppJwt get() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new CaptureFailure(INVALID_STATE);
        try {
            var key=Objects.requireNonNull(keys.get());
            if(!"RSA".equalsIgnoreCase(key.getAlgorithm()))throw new CaptureFailure(UNAVAILABLE);
            var now=clock.instant().truncatedTo(ChronoUnit.SECONDS);var expires=now.plus(ttl);
            var header=JSON.createObjectNode().put("alg","RS256").put("typ","JWT");
            var claims=JSON.createObjectNode().put("iss",appId).put("iat",now.minus(issuedAtSkew).getEpochSecond()).put("exp",expires.getEpochSecond());
            var signing=BASE64.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8))+"."+BASE64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
            var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key);signature.update(signing.getBytes(StandardCharsets.US_ASCII));
            var token=signing+"."+BASE64.encodeToString(signature.sign());
            if(!expires.isAfter(clock.instant()))throw new CaptureFailure(UNAVAILABLE);
            return new GitHubMachineTransport.AppJwt(appId,token,expires);
        }catch(CaptureFailure fixed){throw fixed;}
        catch(RuntimeException | GeneralSecurityException privateFailure){throw new CaptureFailure(UNAVAILABLE);}
    }
    @Override public String toString(){return "GitHubAppJwtSigner[configured]";}
}
