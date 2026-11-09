package org.pipesor.ingestion.shared.interfaces.rest;

public final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    public ApiFailure(int status, String code) { super(code, null, false, false); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiFailure unavailable() { return new ApiFailure(503, "VERIFICATION_UNAVAILABLE"); }
}
