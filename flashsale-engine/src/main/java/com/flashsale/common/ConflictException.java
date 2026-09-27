package com.flashsale.common;

/** 409 with a machine-readable code, e.g. HOLD_EXPIRED. */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
