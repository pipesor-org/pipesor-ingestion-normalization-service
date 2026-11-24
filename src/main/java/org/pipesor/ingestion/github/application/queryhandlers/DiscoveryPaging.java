package org.pipesor.ingestion.github.application.queryhandlers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.pipesor.ingestion.github.application.queries.DiscoveryPage;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;

import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.UNAVAILABLE;

/**
 * Filters, orders and slices one complete, already verified discovery list into a keyset page. The order is total:
 * two items with the same key and ID make the list ambiguous and the read unavailable rather than silently paged.
 */
final class DiscoveryPaging {
    private DiscoveryPaging() {}

    record Slice<T>(List<T> items, DiscoveryPage.Position next) {}

    private record Key(String key, BigInteger number, String id) {}
    private record Entry<T>(Key key, T item) {}

    private static final Comparator<Key> ORDER = Comparator.comparing(Key::key)
            .thenComparing(Key::number).thenComparing(Key::id);

    static <T> Slice<T> slice(List<T> items, Function<T, String> name, Function<T, String> id,
                              Function<T, List<String>> searchable, DiscoveryPage page) {
        var entries = new ArrayList<Entry<T>>();
        for (var item : items) {
            if (page.filter() != null && searchable.apply(item).stream()
                    .noneMatch(text -> DiscoveryPage.key(text).contains(page.filter()))) { continue; }
            entries.add(new Entry<>(key(DiscoveryPage.key(name.apply(item)), id.apply(item)), item));
        }
        entries.sort(Comparator.comparing(Entry::key, ORDER));
        for (int index = 1; index < entries.size(); index++) {
            if (ORDER.compare(entries.get(index - 1).key(), entries.get(index).key()) == 0) { throw new GitHubAccessFailure(UNAVAILABLE); }
        }
        int start = 0;
        if (page.after() != null) {
            var bound = bound(entries, page.after());
            while (start < entries.size() && ORDER.compare(entries.get(start).key(), bound) <= 0) { start++; }
        }
        int end = Math.min(entries.size(), start + page.size());
        var selected = entries.subList(start, end).stream().map(Entry::item).toList();
        if (end == entries.size()) { return new Slice<>(selected, null); }
        var last = entries.get(end - 1).key();
        return new Slice<>(selected, new DiscoveryPage.Position(last.key(), last.id(), false));
    }

    /** A prefix position resumes after the exact item while it still exists with that prefix; otherwise after the prefix. */
    private static <T> Key bound(List<Entry<T>> entries, DiscoveryPage.Position after) {
        if (after.prefix()) {
            for (var entry : entries) {
                if (entry.key().id().equals(after.id()) && entry.key().key().startsWith(after.key())) { return entry.key(); }
            }
        }
        return key(after.key(), after.id());
    }

    private static Key key(String key, String id) {
        if (id == null || id.isEmpty() || !id.chars().allMatch(c -> c >= '0' && c <= '9')) { throw new GitHubAccessFailure(UNAVAILABLE); }
        return new Key(key, new BigInteger(id), id);
    }
}
