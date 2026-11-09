package org.pipesor.ingestion.github.domain.model.valueobjects;

public enum AccessOperation {
    VIEW(false), OPERATE(false), CONFIGURE(false), REGISTER(false),
    GRANT_VIEW(true), GRANT_OPERATE(true), GRANT_CONFIGURE(true), DISCOVER(false);

    private final boolean grant;

    AccessOperation(boolean grant) {
        this.grant = grant;
    }

    public boolean permitsDifferentSubject() { return grant; }
}
