package org.pipesor.ingestion.normalization.domain.model.valueobjects;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Aggregate source locator grammar of protected workflow evidence 2.2 (a superset of 2.1). */
public final class SourceLocator {
    private static final Pattern SHAPE = Pattern.compile(
            "v2:job:([1-9][0-9]{0,19}):member:((?:[A-Za-z0-9._~-]|%[0-9A-F]{2}){1,765}):lines:([1-9][0-9]{0,8})-([1-9][0-9]{0,8}):sha256:[0-9a-f]{64}");
    public static final int MAX_LINES = 200;

    private SourceLocator() { }

    /** Builds a locator for lines {@code first..last} (1-based, inclusive) of an archive member. */
    public static String of(String job, String member, int first, int last, byte[] digest) {
        String locator = "v2:job:" + job + ":member:" + encode(member) + ":lines:" + first + "-" + last + ":sha256:"
                + HexFormat.of().formatHex(digest);
        if (!valid(locator, job)) throw new IllegalArgumentException("Invalid source locator");
        return locator;
    }

    /** True when the locator is valid and names the given authenticated job id. */
    public static boolean valid(String locator, String job) {
        if (locator == null || job == null) return false;
        var match = SHAPE.matcher(locator);
        if (!match.matches() || !match.group(1).equals(job)) return false;
        long first = Long.parseLong(match.group(3));
        long last = Long.parseLong(match.group(4));
        if (last < first || last - first >= MAX_LINES) return false;
        String member = decode(match.group(2));
        return member != null && validMember(member);
    }

    static String encode(String member) {
        var out = new StringBuilder();
        for (byte b : member.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static boolean validMember(String member) {
        int bytes = member.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1 || bytes > 255 || member.startsWith("/") || member.contains("\\")) return false;
        if (member.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) return false;
        String[] segments = member.split("/", -1);
        if (segments.length > 2) return false;
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals("..") || segment.equals(".")) return false;
        }
        return true;
    }

    private static String decode(String encoded) {
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                bytes.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }
}
