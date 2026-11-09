package org.pipesor.ingestion.normalization.infrastructure.protection;

import java.util.Locale;
import java.util.Optional;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;

/** Explicit, bounded known-pattern policy; it does not identify arbitrary names or unknown encodings. */
public final class BoundedEvidenceProtection implements EvidenceProtection {
    private static final String MASK = "[redacted]";
    private final String policyVersion;
    private final int maxInputBytes;
    private final int maxOutputChars;
    private final int maxRedactions;

    public BoundedEvidenceProtection(String policyVersion, int maxInputBytes, int maxOutputChars, int maxRedactions) {
        if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._:-]{1,128}")
                || maxInputBytes < 1 || maxOutputChars < 1 || maxRedactions < 1) {
            throw new IllegalArgumentException("Invalid evidence protection configuration");
        }
        this.policyVersion = policyVersion;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputChars = maxOutputChars;
        this.maxRedactions = maxRedactions;
    }

    public String policyVersion() { return policyVersion; }
    public int maxInputBytes() { return maxInputBytes; }
    public int maxOutputChars() { return maxOutputChars; }
    public int maxRedactions() { return maxRedactions; }
    @Override public String toString() { return "BoundedEvidenceProtection[configured]"; }

    @Override
    public Optional<Texts> protect(Texts source) {
        if (source == null) { return Optional.empty(); }
        var values = new String[] {source.branch(), source.service(), source.environment(), source.failureSummary(),
                source.job(), source.excerpt(), source.correlationId(), source.scopeRevision()};
        try {
            validateInput(values);
            var budget = new Budget();
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) { continue; }
                var assignments = maskAssignments(values[i], budget);
                // Unknown escaped representations need a separately accepted decoding policy.
                if (assignments.contains("\\u") || assignments.contains("\\\"")) { throw new Refusal(); }
                var protectedText = maskTokens(assignments, budget).strip();
                if (protectedText.isEmpty() || (i >= 6 && !protectedText.equals(values[i]))) { throw new Refusal(); }
                budget.outputChars += protectedText.length();
                if (budget.outputChars > maxOutputChars) { throw new Refusal(); }
                values[i] = protectedText;
            }
            if (values[0] == null || values[6] == null || values[7] == null) { throw new Refusal(); }
            return Optional.of(new Texts(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]));
        } catch (Refusal refused) { return Optional.empty(); }
    }

    private void validateInput(String[] values) {
        long bytes = 0;
        for (var value : values) {
            if (value == null) { continue; }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { throw new Refusal(); }
                    bytes += 4;
                } else {
                    if (Character.isLowSurrogate(c) || Character.getType(c) == Character.FORMAT
                            || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                        throw new Refusal();
                    }
                    bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
                }
                if (bytes > maxInputBytes) { throw new Refusal(); }
            }
            if (value.toUpperCase(Locale.ROOT).contains("PRIVATE KEY")) { throw new Refusal(); }
        }
    }

    private String maskAssignments(String text, Budget budget) {
        var output = new StringBuilder();
        int copied = 0;
        for (int i = 0; i < text.length();) {
            int end;
            String key;
            if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                if (i > 0 && text.charAt(i - 1) == '\\') { i++; continue; }
                var quoted = quoted(text, i);
                if (quoted == null) { i++; continue; }
                key = quoted.decoded();
                end = quoted.end();
            } else if (Character.isLetter(text.charAt(i)) || text.charAt(i) == '_') {
                end = i + 1;
                while (end < text.length() && keyCharacter(text.charAt(end))) { end++; }
                key = text.substring(i, end);
            } else { i++; continue; }
            int separator = whitespace(text, end);
            if (sensitiveKey(key) && separator < text.length() && (text.charAt(separator) == ':' || text.charAt(separator) == '=')) {
                int start = whitespace(text, separator + 1);
                if (start == text.length()) { throw new Refusal(); }
                // Compound credentials cannot be safely delimited by the scalar scanner.
                if (text.charAt(start) == '[' || text.charAt(start) == '{') { throw new Refusal(); }
                int valueEnd;
                if (text.charAt(start) == '"' || text.charAt(start) == '\'') {
                    var value = quoted(text, start);
                    if (value == null) { throw new Refusal(); }
                    valueEnd = value.end();
                } else {
                    valueEnd = start;
                    while (valueEnd < text.length() && "\r\n".indexOf(text.charAt(valueEnd)) < 0) { valueEnd++; }
                    if (valueEnd == start) { throw new Refusal(); }
                }
                output.append(text, copied, i).append(budget.mask());
                copied = valueEnd;
                i = valueEnd;
            } else { i = text.charAt(i) == '"' || text.charAt(i) == '\'' ? i + 1 : end; }
        }
        return output.append(text, copied, text.length()).toString();
    }

    private static boolean sensitiveKey(String key) {
        var normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.endsWith("password") || normalized.endsWith("passwd") || normalized.endsWith("secret")
                || normalized.endsWith("token") || normalized.endsWith("apikey") || normalized.endsWith("privatekey")
                || normalized.equals("pwd") || normalized.equals("authorization") || normalized.equals("credential")
                || normalized.equals("credentials") || normalized.equals("connectionstring");
    }

    private String maskTokens(String text, Budget budget) {
        var output = new StringBuilder();
        for (int i = 0; i < text.length();) {
            if (!tokenCharacter(text.charAt(i))) { output.append(text.charAt(i++)); continue; }
            int end = i + 1;
            while (end < text.length() && tokenCharacter(text.charAt(end))) { end++; }
            var token = text.substring(i, end);
            if (token.equalsIgnoreCase("Bearer")) {
                int start = whitespace(text, end);
                if (start == end || start == text.length()) { throw new Refusal(); }
                end = start;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                        && "\"',;{}[]<>".indexOf(text.charAt(end)) < 0) { end++; }
                if (end == start) { throw new Refusal(); }
                output.append(budget.mask());
            } else if (knownSecret(token) || email(token) || address(token)) { output.append(budget.mask()); }
            else { output.append(token); }
            i = end;
        }
        return output.toString();
    }

    private static boolean knownSecret(String token) {
        var lower = token.toLowerCase(Locale.ROOT);
        return lower.contains("github_pat_") || lower.contains("ghp_") || lower.contains("gho_")
                || lower.contains("ghu_") || lower.contains("ghs_") || lower.contains("ghr_")
                || jwt(token);
    }

    private static boolean jwt(String token) {
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        if (first < 1 || second <= first + 1 || second == token.length() - 1) { return false; }
        try {
            var header = new String(Base64.getUrlDecoder().decode(token.substring(0, first)), StandardCharsets.UTF_8).stripLeading();
            return header.startsWith("{");
        } catch (IllegalArgumentException invalidHeader) { return false; }
    }

    private static boolean email(String token) {
        int at = token.indexOf('@');
        return at > 0 && at < token.length() - 1;
    }

    private static boolean address(String token) {
        for (int i = 0; i < token.length();) {
            if (token.charAt(i) < '0' || token.charAt(i) > '9') { i++; continue; }
            int end = i + 1;
            while (end < token.length() && ((token.charAt(end) >= '0' && token.charAt(end) <= '9') || token.charAt(end) == '.')) { end++; }
            int trimmed = end;
            while (trimmed > i && token.charAt(trimmed - 1) == '.') { trimmed--; }
            if (ipv4(token.substring(i, trimmed))) { return true; }
            i = end;
        }
        int slash = token.indexOf('/');
        var candidate = slash < 0 ? token : token.substring(0, slash);
        int trimmedEnd = candidate.length();
        while (trimmedEnd > 0 && candidate.charAt(trimmedEnd - 1) == '.') { trimmedEnd--; }
        candidate = candidate.substring(0, trimmedEnd);
        int colon = candidate.indexOf(':');
        if (ipv4(colon < 0 ? candidate : candidate.substring(0, colon))) { return true; }
        if (colon < 0 || candidate.indexOf(':', colon + 1) < 0) { return false; }
        if (!candidate.substring(0, colon).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            candidate = candidate.substring(colon + 1);
        }
        int zone = candidate.indexOf('%');
        if (zone >= 0) { candidate = candidate.substring(0, zone); }
        int compression = candidate.indexOf("::");
        if (compression != candidate.lastIndexOf("::")) { return false; }
        int groups = 0;
        for (var group : candidate.split(":", -1)) {
            if (group.isEmpty()) { continue; }
            if (group.indexOf('.') >= 0) { if (!ipv4(group)) { return false; } groups += 2; }
            else {
                if (group.length() > 4 || !group.chars().allMatch(c -> Character.digit(c, 16) >= 0)) { return false; }
                groups++;
            }
        }
        return compression >= 0 ? groups < 8 : groups == 8;
    }

    private static boolean ipv4(String text) {
        var parts = text.split("\\.", -1);
        if (parts.length != 4) { return false; }
        for (var part : parts) {
            if (part.isEmpty() || part.length() > 3) { return false; }
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') { return false; }
                value = value * 10 + digit - '0';
            }
            if (value > 255) { return false; }
        }
        return true;
    }

    private static Quoted quoted(String text, int start) {
        char quote = text.charAt(start);
        var decoded = new StringBuilder();
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) { return new Quoted(decoded.toString(), i + 1); }
            if (c == '\\') {
                if (++i == text.length()) { return null; }
                c = text.charAt(i);
                if (c == 'u') {
                    if (i + 4 >= text.length()) { return null; }
                    int code = 0;
                    for (int j = 0; j < 4; j++) {
                        int digit = Character.digit(text.charAt(++i), 16);
                        if (digit < 0) { return null; }
                        code = code * 16 + digit;
                    }
                    c = (char) code;
                }
            }
            decoded.append(c);
        }
        return null;
    }

    private static int whitespace(String text, int start) {
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) { start++; }
        return start;
    }
    private static boolean keyCharacter(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '-'; }
    private static boolean tokenCharacter(char c) { return Character.isLetterOrDigit(c) || "._-+%@:/".indexOf(c) >= 0; }
    private record Quoted(String decoded, int end) {}
    private final class Budget {
        private int redactions;
        private long outputChars;
        private String mask() { if (++redactions > maxRedactions) { throw new Refusal(); } return MASK; }
    }
    private static final class Refusal extends RuntimeException {
        private Refusal() { super("Evidence protection refused", null, false, false); }
    }
}
