package org.pipesor.ingestion.github.interfaces.rest.transform;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.github.application.queries.DiscoveryPage;
import org.pipesor.ingestion.shared.interfaces.rest.ApiFailure;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryPageTokensTests {
    private static final String SUBJECT = "tenant:subject";
    private static final DiscoveryPageTokens.Binding REPOSITORIES = DiscoveryPageTokens.Binding.repositories(SUBJECT, "repo");

    @Test void roundTripsAnOpaqueUrlSafeTokenOnlyForItsBinding() {
        var position = new DiscoveryPage.Position("example/service", "823001", false);
        var token = DiscoveryPageTokens.encode(position, REPOSITORIES);
        assertTrue(token.matches("[A-Za-z0-9_-]+") && token.length() <= DiscoveryPageTokens.MAX_LENGTH, token);
        assertFalse(token.contains("example"));
        assertEquals(position, DiscoveryPageTokens.decode(token, DiscoveryPageTokens.Binding.repositories(SUBJECT, "repo")));
        assertNull(DiscoveryPageTokens.encode(null, REPOSITORIES));
        assertNull(DiscoveryPageTokens.decode(null, REPOSITORIES));
        for (var other : List.of(DiscoveryPageTokens.Binding.repositories(SUBJECT, null), DiscoveryPageTokens.Binding.repositories(SUBJECT, "repo-"),
                DiscoveryPageTokens.Binding.repositories("tenant:other", "repo"), DiscoveryPageTokens.Binding.workflows("823001", SUBJECT, "repo"))) {
            invalid(token, other);
        }
        var workflows = DiscoveryPageTokens.encode(position, DiscoveryPageTokens.Binding.workflows("1", SUBJECT, null));
        invalid(workflows, DiscoveryPageTokens.Binding.workflows("11", SUBJECT, null));
        invalid(workflows, DiscoveryPageTokens.Binding.repositories(SUBJECT, null));
    }

    @Test void boundsLongKeysToAPrefixWithinTheTokenLimit() {
        var key = "é😀中".repeat(400);
        var token = DiscoveryPageTokens.encode(new DiscoveryPage.Position(key, "9".repeat(32), false), REPOSITORIES);
        assertTrue(token.length() <= DiscoveryPageTokens.MAX_LENGTH, Integer.toString(token.length()));
        var decoded = DiscoveryPageTokens.decode(token, REPOSITORIES);
        assertTrue(decoded.prefix());
        assertTrue(key.startsWith(decoded.key()) && decoded.key().length() < key.length());
        var exact = "a".repeat(DiscoveryPageTokens.MAX_KEY_BYTES);
        assertFalse(DiscoveryPageTokens.decode(DiscoveryPageTokens.encode(new DiscoveryPage.Position(exact, "1", false), REPOSITORIES), REPOSITORIES).prefix());
        var failure = assertThrows(ApiFailure.class, () -> DiscoveryPageTokens.encode(new DiscoveryPage.Position("a", "1".repeat(33), false), REPOSITORIES));
        assertEquals(503, failure.status());
    }

    @Test void rejectsEveryNonCanonicalOrMalformedToken() throws Exception {
        var token = DiscoveryPageTokens.encode(new DiscoveryPage.Position("example/service", "823001", false), REPOSITORIES);
        var digest = Arrays.copyOfRange(Base64.getUrlDecoder().decode(token), 1, 17);
        for (var candidate : List.of("", "a", "====", token + "=", " " + token, token + "A", token + "AA", token.substring(0, token.length() - 1),
                "A".repeat(DiscoveryPageTokens.MAX_LENGTH + 1), token.replace('A', '+'))) {
            if (!candidate.equals(token)) { invalid(candidate, REPOSITORIES); }
        }
        invalid(encode(payload(2, digest, 0, "823001", "example/service")), REPOSITORIES);
        invalid(encode(payload(1, digest, 2, "823001", "example/service")), REPOSITORIES);
        invalid(encode(payload(1, digest, 0, "82a", "example/service")), REPOSITORIES);
        invalid(encode(payload(1, digest, 0, "", "example/service")), REPOSITORIES);
        invalid(encode(payload(1, digest, 0, "823001", "")), REPOSITORIES);
        invalid(encode(payload(1, digest, 0, "1".repeat(33), "example/service")), REPOSITORIES);
        invalid(encode(payload(1, digest, 0, "823001", "a".repeat(DiscoveryPageTokens.MAX_KEY_BYTES + 1))), REPOSITORIES);
        var trailing = Arrays.copyOf(payload(1, digest, 0, "823001", "example/service"), payload(1, digest, 0, "823001", "example/service").length + 1);
        invalid(encode(trailing), REPOSITORIES);
        // 'a' as an overlong two-byte sequence decodes, but is not the canonical encoding.
        var head = payload(1, digest, 0, "823001", "x");
        int at = head.length - 3;
        var overlong = Arrays.copyOf(head, at + 5);
        overlong[at] = 0; overlong[at + 1] = 3; overlong[at + 2] = (byte) 0xC1; overlong[at + 3] = (byte) 0xA1; overlong[at + 4] = 'b';
        invalid(encode(overlong), REPOSITORIES);
        assertEquals(new DiscoveryPage.Position("example/service", "823001", false),
                DiscoveryPageTokens.decode(encode(payload(1, digest, 0, "823001", "example/service")), REPOSITORIES));
    }

    private static void invalid(String token, DiscoveryPageTokens.Binding binding) {
        var failure = assertThrows(ApiFailure.class, () -> DiscoveryPageTokens.decode(token, binding), token);
        assertEquals(400, failure.status());
        assertEquals("INVALID_PAGE_TOKEN", failure.code());
    }
    private static byte[] payload(int version, byte[] digest, int flag, String id, String key) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(version); out.write(digest); out.writeByte(flag); out.writeUTF(id); out.writeUTF(key);
        }
        return bytes.toByteArray();
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
