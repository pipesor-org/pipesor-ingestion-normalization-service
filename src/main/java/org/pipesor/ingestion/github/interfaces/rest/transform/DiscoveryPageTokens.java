package org.pipesor.ingestion.github.interfaces.rest.transform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import org.pipesor.ingestion.github.application.queries.DiscoveryPage;
import org.pipesor.ingestion.shared.interfaces.rest.ApiFailure;

/**
 * Opaque, bounded keyset continuation of paged discovery (MVP HTTP API v1). A token is the unpadded base64url
 * encoding of: version byte {@code 1}; a 16-byte binding (SHA-256 prefix over route scope, subject and normalized
 * {@code q}); a flag byte ({@code 1} when the key is a bounded prefix); then the last item's ID and lower-cased sort key,
 * each as length-prefixed modified UTF-8 ({@link DataOutputStream#writeUTF}). Keys longer than
 * {@value #MAX_KEY_BYTES} encoded bytes are cut to a prefix, so a token never exceeds {@value #MAX_LENGTH} characters.
 * Decoding is strict: only the canonical encoding of a well-formed token for the same binding is accepted.
 */
public final class DiscoveryPageTokens {
    public static final int MAX_LENGTH = 512;
    static final int MAX_ID_LENGTH = 32;
    static final int MAX_KEY_BYTES = 320;
    private static final int VERSION = 1;
    private static final int BINDING_BYTES = 16;
    private static final String DOMAIN = "pipesor.github.discovery-page.v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private DiscoveryPageTokens() {}

    /** The scope a token is issued for; a token is valid only for the same scope, subject and normalized filter. */
    public static final class Binding {
        private final byte[] digest;
        private Binding(byte[] digest) { this.digest = digest; }

        public static Binding repositories(String subjectRef, String filter) { return of("repositories", subjectRef, filter); }
        public static Binding workflows(String repositoryId, String subjectRef, String filter) {
            return of("repositories/" + repositoryId + "/workflows", subjectRef, filter);
        }
        private static Binding of(String scope, String subjectRef, String filter) {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF(DOMAIN);
                out.writeUTF(scope);
                out.writeUTF(subjectRef);
                out.writeBoolean(filter != null);
                out.writeUTF(filter == null ? "" : filter);
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
            try {
                return new Binding(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()), BINDING_BYTES));
            } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
        }
    }

    /** Encodes the next position, or returns null on the last page. */
    public static String encode(DiscoveryPage.Position next, Binding binding) {
        if (next == null) { return null; }
        if (next.id().length() > MAX_ID_LENGTH) { throw ApiFailure.unavailable(); }
        int end = 0;
        for (int bytes = 0; end < next.key().length(); end++) {
            bytes += encodedLength(next.key().charAt(end));
            if (bytes > MAX_KEY_BYTES) { break; }
        }
        return ENCODER.encodeToString(payload(binding.digest, end < next.key().length() || next.prefix(), next.id(),
                next.key().substring(0, end)));
    }

    /** Decodes a continuation, or returns null for the first page; anything else is {@code 400 INVALID_PAGE_TOKEN}. */
    public static DiscoveryPage.Position decode(String token, Binding binding) {
        if (token == null) { return null; }
        if (token.isEmpty() || token.length() > MAX_LENGTH || !token.chars().allMatch(DiscoveryPageTokens::alphabet)) { throw invalid(); }
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(token); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        if (!ENCODER.encodeToString(bytes).equals(token)) { throw invalid(); }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != VERSION) { throw invalid(); }
            var digest = new byte[BINDING_BYTES];
            in.readFully(digest);
            int flag = in.readUnsignedByte();
            var id = in.readUTF();
            var key = in.readUTF();
            if (in.read() != -1 || flag > 1 || id.isEmpty() || id.length() > MAX_ID_LENGTH || key.isEmpty()
                    || key.chars().map(c -> encodedLength((char) c)).sum() > MAX_KEY_BYTES
                    || !Arrays.equals(bytes, payload(digest, flag == 1, id, key))) { throw invalid(); }
            if (!MessageDigest.isEqual(digest, binding.digest)) { throw invalid(); }
            return new DiscoveryPage.Position(key, id, flag == 1);
        } catch (IOException | IllegalArgumentException malformed) { throw invalid(); }
    }

    private static byte[] payload(byte[] digest, boolean prefix, String id, String key) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.write(digest);
            out.writeBoolean(prefix);
            out.writeUTF(id);
            out.writeUTF(key);
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        return bytes.toByteArray();
    }
    /** Byte length of one UTF-16 unit in modified UTF-8. */
    private static int encodedLength(char value) { return value >= 0x0001 && value <= 0x007F ? 1 : value <= 0x07FF ? 2 : 3; }
    private static boolean alphabet(int c) { return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'; }
    private static ApiFailure invalid() { return new ApiFailure(400, "INVALID_PAGE_TOKEN"); }
}
