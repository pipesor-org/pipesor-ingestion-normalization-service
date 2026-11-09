package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.Arrays;

/** The digest algorithm belongs to the future browser protocol; raw OAuth state is never accepted here. */
public final class StateDigest {
    private final byte[] value;
    public StateDigest(byte[] value) {
        if (value == null || value.length == 0) { throw new IllegalArgumentException("Digest required"); }
        this.value = value.clone();
    }
    public byte[] value() { return value.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof StateDigest digest && java.security.MessageDigest.isEqual(value, digest.value);
    }
    @Override public int hashCode() { return Arrays.hashCode(value); }
    @Override public String toString() { return "[state digest]"; }
}
