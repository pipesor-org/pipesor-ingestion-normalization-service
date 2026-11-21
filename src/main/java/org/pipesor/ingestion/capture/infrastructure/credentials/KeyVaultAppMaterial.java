package org.pipesor.ingestion.capture.infrastructure.credentials;

import com.azure.security.keyvault.secrets.SecretClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Exact operator-configured App material; no latest lookup, writes, rotation or client-selected references. */
public final class KeyVaultAppMaterial {
    public record Reference(String name,String version) {
        public Reference {
            if(name==null || !name.matches("[A-Za-z0-9-]{1,127}") || version==null || !version.matches("[A-Za-z0-9-]{1,128}") || version.equalsIgnoreCase("latest"))
                throw new IllegalArgumentException("Exact App material reference required");
        }
        @Override public String toString(){return "AppMaterialReference[private]";}
    }
    private final SecretClient client;
    private final URI vault;
    private final String appId;
    private final Reference signing,webhook;
    private final int maxBytes;
    public KeyVaultAppMaterial(SecretClient client,URI approvedVault,String appId,Reference signing,Reference webhook,int maxBytes) {
        try {
            this.client=Objects.requireNonNull(client);this.vault=vault(approvedVault);this.appId=CaptureScope.external(appId);
            this.signing=Objects.requireNonNull(signing);this.webhook=Objects.requireNonNull(webhook);
            if(!vault.equals(vault(URI.create(client.getVaultUrl()))) || signing.name().equalsIgnoreCase(webhook.name()) || maxBytes<1 || maxBytes>25000)
                throw new IllegalArgumentException();
            this.maxBytes=maxBytes;
        }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid App material configuration");}
    }
    public String appId(){return appId;}
    public PrivateKey signingKey() {
        return decodeSigningKey(read(signing));
    }
    public static PrivateKey decodeSigningKey(String material) {
        byte[] der=null,encoded=null;
        try {
            var pem=material.strip();String label;
            if(pem.startsWith("-----BEGIN RSA PRIVATE KEY-----"))label="RSA PRIVATE KEY";
            else if(pem.startsWith("-----BEGIN PRIVATE KEY-----"))label="PRIVATE KEY";
            else throw new CaptureFailure(UNAVAILABLE);
            var begin="-----BEGIN "+label+"-----";var end="-----END "+label+"-----";
            if(!pem.endsWith(end))throw new CaptureFailure(UNAVAILABLE);
            var base64=pem.substring(begin.length(),pem.length()-end.length()).replaceAll("[\\r\\n\\t ]","");
            der=Base64.getDecoder().decode(base64);
            // PKCS#1 is wrapped in the standard PKCS#8 RSA envelope; JCA validates the actual key structure.
            encoded=label.equals("RSA PRIVATE KEY")?rsaPkcs8(der):der;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        }catch(CaptureFailure fixed){throw fixed;}
        catch(RuntimeException | GeneralSecurityException privateFailure){throw new CaptureFailure(UNAVAILABLE);}
        finally{if(der!=null)Arrays.fill(der,(byte)0);if(encoded!=null&&encoded!=der)Arrays.fill(encoded,(byte)0);}
    }
    public byte[] webhookSecret() {
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(read(webhook)));
            var result=new byte[encoded.remaining()];encoded.get(result);return result;
        }catch(CaptureFailure fixed){throw fixed;}
        catch(RuntimeException | CharacterCodingException privateFailure){throw new CaptureFailure(UNAVAILABLE);}
    }
    private String read(Reference reference) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new CaptureFailure(INVALID_STATE);
        try {
            var secret=client.getSecret(reference.name(),reference.version());var properties=Objects.requireNonNull(secret.getProperties());
            var id=URI.create(properties.getId());
            if(!reference.name().equals(secret.getName()) || !reference.name().equals(properties.getName()) || !reference.version().equals(properties.getVersion())
                    || !"https".equals(id.getScheme()) || !vault.getHost().equalsIgnoreCase(id.getHost()) || id.getPort()!=-1 || id.getUserInfo()!=null || id.getQuery()!=null || id.getFragment()!=null
                    || !("/secrets/"+reference.name()+"/"+reference.version()).equals(id.getRawPath()))throw new CaptureFailure(UNAVAILABLE);
            var raw=secret.getValue();
            if(raw==null || raw.isBlank() || raw.length()>maxBytes || raw.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw new CaptureFailure(UNAVAILABLE);
            return raw;
        }catch(CaptureFailure fixed){throw fixed;}
        catch(RuntimeException privateFailure){throw new CaptureFailure(UNAVAILABLE);}
    }
    private static URI vault(URI uri) {
        if(uri==null || !"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))throw new IllegalArgumentException();
        return URI.create("https://"+uri.getHost().toLowerCase(Locale.ROOT));
    }
    private static byte[] rsaPkcs8(byte[] pkcs1) {
        var body=new ByteArrayOutputStream();body.writeBytes(new byte[]{2,1,0});
        body.writeBytes(HexFormat.of().parseHex("300d06092a864886f70d0101010500"));body.writeBytes(der(4,pkcs1));return der(0x30,body.toByteArray());
    }
    private static byte[] der(int tag,byte[] content) {
        var output=new ByteArrayOutputStream();output.write(tag);
        if(content.length<128)output.write(content.length);
        else {int length=content.length;int count=(32-Integer.numberOfLeadingZeros(length)+7)/8;output.write(0x80|count);for(int i=count-1;i>=0;i--)output.write(length>>>(i*8));}
        output.writeBytes(content);return output.toByteArray();
    }
    @Override public String toString(){return "KeyVaultAppMaterial[configured]";}
}
