package org.pipesor.ingestion.capture.infrastructure.credentials;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.*;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.springframework.boot.test.system.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;
import static org.pipesor.ingestion.capture.infrastructure.credentials.KeyVaultAppMaterial.Reference;

@ExtendWith(OutputCaptureExtension.class)
class KeyVaultAppMaterialTests {
    private static final URI VAULT=URI.create("https://synthetic.vault.azure.net");
    private static final Reference SIGNING=new Reference("app-signing","key-version-1"),WEBHOOK=new Reference("app-webhook","hook-version-2");
    private static final KeyPair KEY=key();
    private SecretClient sdk;
    private KeyVaultAppMaterial material;
    private static KeyPair key(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    @BeforeEach void setup(){
        sdk=mock(SecretClient.class);when(sdk.getVaultUrl()).thenReturn(VAULT.toString());
        material=new KeyVaultAppMaterial(sdk,VAULT,"42",SIGNING,WEBHOOK,25000);
    }
    private KeyVaultSecret response(Reference ref,String value,String id,String name,String version){
        var secret=mock(KeyVaultSecret.class);var props=mock(SecretProperties.class);
        when(secret.getName()).thenReturn(name);when(secret.getValue()).thenReturn(value);when(secret.getProperties()).thenReturn(props);
        when(props.getName()).thenReturn(name);when(props.getVersion()).thenReturn(version);when(props.getId()).thenReturn(id);return secret;
    }
    private KeyVaultSecret response(Reference ref,String value){return response(ref,value,VAULT+"/secrets/"+ref.name()+"/"+ref.version(),ref.name(),ref.version());}
    private void supply(Reference ref,String value){doAnswer(inv->response(ref,value)).when(sdk).getSecret(ref.name(),ref.version());}
    private static String pem(String kind,byte[] bytes){return "-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+kind+"-----";}
    @Test void resolvesOnlyExactVersionsAndBothGitHubRsaPemFormats()throws Exception{
        for(var pkcs1:List.of(false,true)){
            var raw=pkcs1?pkcs1((RSAPrivateCrtKey)KEY.getPrivate()):KEY.getPrivate().getEncoded();
            supply(SIGNING,pem(pkcs1?"RSA PRIVATE KEY":"PRIVATE KEY",raw));
            var actual=material.signingKey();assertArrayEquals(KEY.getPrivate().getEncoded(),actual.getEncoded());
            var bytes="distinct-signed-message-é".getBytes(StandardCharsets.UTF_8);
            var signer=Signature.getInstance("SHA256withRSA");signer.initSign(actual);signer.update(bytes);var signed=signer.sign();
            var verify=Signature.getInstance("SHA256withRSA");verify.initVerify(KEY.getPublic());verify.update(bytes);assertTrue(verify.verify(signed));
        }
        verify(sdk,times(2)).getSecret(SIGNING.name(),SIGNING.version());
        verify(sdk,never()).getSecret(anyString());
    }
    @Test void webhookSecretUsesExactUtf8WithoutWhitespaceNormalizationOrCaching(CapturedOutput output){
        supply(WEBHOOK," synthetic-hook-é😀\n");var first=material.webhookSecret();
        assertArrayEquals(" synthetic-hook-é😀\n".getBytes(StandardCharsets.UTF_8),first);first[0]=0;
        assertArrayEquals(" synthetic-hook-é😀\n".getBytes(StandardCharsets.UTF_8),material.webhookSecret());
        assertEquals("42",material.appId());assertEquals("AppMaterialReference[private]",WEBHOOK.toString());
        assertEquals("KeyVaultAppMaterial[configured]",material.toString());assertFalse(output.getAll().contains("synthetic-hook"));
    }
    @ParameterizedTest @ValueSource(strings={"vault","name","version","path","query","fragment","userinfo","null"})
    void rejectsUnprovenObjectOrVersionBeforeReturningKeyMaterial(String mismatch){
        doAnswer(inv->{
            if(mismatch.equals("null"))return null;
            var id=VAULT+"/secrets/"+WEBHOOK.name()+"/"+WEBHOOK.version();
            id=switch(mismatch){case "vault"->id.replace("synthetic.vault","other.vault");case "path"->id+"/other";case "query"->id+"?private";case "fragment"->id+"#private";case "userinfo"->id.replace("https://","https://private@");default->id;};
            return response(WEBHOOK,"private-hook-secret",id,mismatch.equals("name")?"other-object":WEBHOOK.name(),mismatch.equals("version")?"other-version":WEBHOOK.version());
        }).when(sdk).getSecret(WEBHOOK.name(),WEBHOOK.version());
        failure(UNAVAILABLE,material::webhookSecret);
    }
    @ParameterizedTest @ValueSource(strings={"not-a-key-private", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nprivate\n-----END ENCRYPTED PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----\n!invalid!\n-----END PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----\nAQID\n-----END RSA PRIVATE KEY-----"})
    void rejectsUnsupportedOrMalformedPemWithoutEchoingIt(String key){supply(SIGNING,key);failure(UNAVAILABLE,material::signingKey);}
    @Test void rejectsEmptyOversizeInvalidUnicodeAndPrivateProviderFailuresWithoutRetries(CapturedOutput logs){
        supply(WEBHOOK,"");failure(UNAVAILABLE,material::webhookSecret);
        supply(WEBHOOK,"é".repeat(13000));failure(UNAVAILABLE,material::webhookSecret);
        supply(WEBHOOK,"private\uD800");failure(UNAVAILABLE,material::webhookSecret);
        doThrow(new IllegalStateException("private-key-secret-from-provider")).when(sdk).getSecret(SIGNING.name(),SIGNING.version());
        failure(UNAVAILABLE,material::signingKey);verify(sdk,times(1)).getSecret(SIGNING.name(),SIGNING.version());
        assertFalse(logs.getAll().contains("private-key-secret-from-provider"));
    }
    @Test void requiresApprovedVaultSeparateObjectsAndAnExactVersion(){
        assertThrows(IllegalArgumentException.class,()->new Reference("unsafe/path","version"));
        assertThrows(IllegalArgumentException.class,()->new Reference("name","latest"));
        assertThrows(IllegalArgumentException.class,()->new KeyVaultAppMaterial(sdk,URI.create("https://other.vault.azure.net"),"42",SIGNING,WEBHOOK,100));
        assertThrows(IllegalArgumentException.class,()->new KeyVaultAppMaterial(sdk,VAULT,"42",SIGNING,new Reference("APP-SIGNING","other"),100));
        assertThrows(IllegalArgumentException.class,()->new KeyVaultAppMaterial(sdk,VAULT,"42",SIGNING,WEBHOOK,25001));
    }
    @Test void secretReadsNeverOccurInsideSqlTransactions(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{failure(INVALID_STATE,material::signingKey);failure(INVALID_STATE,material::webhookSecret);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verify(sdk,never()).getSecret(anyString(),anyString());
    }
    private void failure(CaptureFailure.Reason reason,org.junit.jupiter.api.function.Executable action){var failure=assertThrowsExactly(CaptureFailure.class,action);assertEquals(reason,failure.reason());assertNull(failure.getCause());assertFalse(failure.toString().contains("private"));}
    private static byte[] pkcs1(RSAPrivateCrtKey key){
        var body=new ByteArrayOutputStream();
        for(var number:List.of(BigInteger.ZERO,key.getModulus(),key.getPublicExponent(),key.getPrivateExponent(),key.getPrimeP(),key.getPrimeQ(),key.getPrimeExponentP(),key.getPrimeExponentQ(),key.getCrtCoefficient()))body.writeBytes(tag(2,number.toByteArray()));
        return tag(0x30,body.toByteArray());
    }
    private static byte[] tag(int tag,byte[] value){
        var result=new ByteArrayOutputStream();result.write(tag);
        if(value.length<128)result.write(value.length);else{int bytes=(Integer.SIZE-Integer.numberOfLeadingZeros(value.length)+7)/8;result.write(0x80|bytes);for(int i=bytes-1;i>=0;i--)result.write(value.length>>>(8*i));}
        result.writeBytes(value);return result.toByteArray();
    }
}
