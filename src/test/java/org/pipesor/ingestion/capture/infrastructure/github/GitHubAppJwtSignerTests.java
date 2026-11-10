package org.pipesor.ingestion.capture.infrastructure.github;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GitHubAppJwtSignerTests {
    private static final Instant NOW=Instant.parse("2026-11-10T12:30:45.987654321Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final KeyPair RSA=key("RSA");
    private static KeyPair key(String algorithm){try{var generator=KeyPairGenerator.getInstance(algorithm);if(algorithm.equals("RSA"))generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    @Test void createsVerifiableRs256ClaimsForDistinctAppsAndExplicitDurations()throws Exception{
        for(var app:List.of("42","9007199254740993999999")){
            var signer=new GitHubAppJwtSigner(app,()->RSA.getPrivate(),CLOCK,Duration.ofSeconds(app.equals("42")?83:197),Duration.ofSeconds(37));
            var result=signer.get();var parts=result.token().split("\\.");assertEquals(3,parts.length);
            var decoder=Base64.getUrlDecoder();var json=JsonMapper.builder().build();
            var header=json.readTree(decoder.decode(parts[0]));var claims=json.readTree(decoder.decode(parts[1]));
            assertEquals("RS256",header.get("alg").asString());assertEquals("JWT",header.get("typ").asString());assertEquals(app,claims.get("iss").asString());
            assertEquals(NOW.getEpochSecond()-37,claims.get("iat").longValue());
            assertEquals(result.expiresAt().getEpochSecond(),claims.get("exp").longValue());
            assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(app.equals("42")?83:197),result.expiresAt());
            var verify=Signature.getInstance("SHA256withRSA");verify.initVerify(RSA.getPublic());verify.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));assertTrue(verify.verify(decoder.decode(parts[2])));
            assertFalse(result.toString().contains(result.token()));assertFalse(signer.toString().contains("PRIVATE"));
        }
    }
    @ParameterizedTest @ValueSource(longs={-1,0,601})
    void rejectsUnsupportedLifetimesWithoutAProviderDefault(long seconds){assertThrows(IllegalArgumentException.class,()->new GitHubAppJwtSigner("42",()->RSA.getPrivate(),CLOCK,Duration.ofSeconds(seconds),Duration.ZERO));}
    @Test void rejectsFractionalOrNegativeTimingAndStripsCustodyFailures(){
        assertThrows(IllegalArgumentException.class,()->new GitHubAppJwtSigner("42",()->RSA.getPrivate(),CLOCK,Duration.ofNanos(1),Duration.ZERO));
        assertThrows(IllegalArgumentException.class,()->new GitHubAppJwtSigner("42",()->RSA.getPrivate(),CLOCK,Duration.ofSeconds(60),Duration.ofSeconds(-1)));
        var signer=new GitHubAppJwtSigner("42",()->{throw new IllegalStateException("private-key-secret");},CLOCK,Duration.ofSeconds(60),Duration.ZERO);
        var failure=assertThrowsExactly(CaptureFailure.class,signer::get);assertEquals(CaptureFailure.Reason.UNAVAILABLE,failure.reason());assertNull(failure.getCause());assertFalse(failure.toString().contains("private-key-secret"));
        var wrong=new GitHubAppJwtSigner("42",()->key("EC").getPrivate(),CLOCK,Duration.ofSeconds(60),Duration.ZERO);
        assertEquals(CaptureFailure.Reason.UNAVAILABLE,assertThrowsExactly(CaptureFailure.class,wrong::get).reason());
    }
    @Test void refusesKeyCustodyInsideAnAmbientSqlTransaction(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();var signer=new GitHubAppJwtSigner("42",()->{calls.incrementAndGet();return RSA.getPrivate();},CLOCK,Duration.ofSeconds(60),Duration.ZERO);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertEquals(CaptureFailure.Reason.INVALID_STATE,assertThrowsExactly(CaptureFailure.class,signer::get).reason());}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(0,calls.get());
    }
}
