package org.pipesor.ingestion.github.domain.model.valueobjects;

/** Private opaque custody metadata. Never a client URL or a secret value. */
public record SecretReference(String value) {
    public SecretReference { AccessContext.requireText(value); }
    @Override public String toString() { return "[private reference]"; }
    public static SecretReference generationObject(java.util.UUID generationId) {
        return new SecretReference("generation:" + java.util.Objects.requireNonNull(generationId));
    }
    public static SecretReference pkceObject(java.util.UUID attemptId) {
        return new SecretReference("pkce:" + java.util.Objects.requireNonNull(attemptId));
    }

    /** Confirmation from a trusted custody adapter, bound to the previously intended object. */
    public record SavedVersion(SecretReference object, SecretReference version) {
        public SavedVersion {
            java.util.Objects.requireNonNull(object, "Object required");
            java.util.Objects.requireNonNull(version, "Version required");
            if ("latest".equalsIgnoreCase(version.value().strip())) {
                throw new IllegalArgumentException("Exact secret version required");
            }
        }
    }
}
