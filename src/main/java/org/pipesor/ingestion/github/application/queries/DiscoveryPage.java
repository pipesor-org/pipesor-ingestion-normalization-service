package org.pipesor.ingestion.github.application.queries;

import java.util.Locale;

/**
 * One keyset page of a discovery list (MVP HTTP API v1, paged discovery): at most {@code size} items that sort
 * strictly after {@code after} (null for the first page) and, when {@code filter} is present, whose case-insensitive
 * text contains it. Items sort by lower-cased name ({@link Locale#ROOT}), then numerically by ID.
 */
public record DiscoveryPage(int size, String filter, Position after) {
    public static final int MAX_SIZE = 100;
    public static final int MIN_FILTER_LENGTH = 2;
    public static final int MAX_FILTER_LENGTH = 100;

    public DiscoveryPage {
        if (size < 1 || size > MAX_SIZE) { throw new IllegalArgumentException("Bounded page size required"); }
        if (filter != null && filter.isBlank()) { throw new IllegalArgumentException("Nonblank filter required"); }
    }

    /**
     * Normalizes a requested filter: surrounding whitespace is removed, the remaining 2-100 characters are matched
     * case-insensitively. Returns null for an absent filter.
     */
    public static String filter(String requested) {
        if (requested == null) { return null; }
        var trimmed = requested.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_FILTER_LENGTH || length > MAX_FILTER_LENGTH) { throw new IllegalArgumentException("Bounded filter required"); }
        return key(trimmed);
    }

    /** The case-insensitive sort and match key of a name. */
    public static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    /**
     * Sort position of the last item of a page: its {@link #key(String) key} and numeric ID. A {@code prefix} key is
     * only a leading part of that item's key (bounded continuation); the page then resumes after the item with this ID
     * when it still carries the prefix, otherwise after the prefix itself, which may repeat items but never skips one.
     */
    public record Position(String key, String id, boolean prefix) {
        public Position {
            if (key == null || key.isEmpty()) { throw new IllegalArgumentException("Position key required"); }
            if (id == null || id.isEmpty() || !id.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("Numeric position ID required");
            }
        }
    }
}
