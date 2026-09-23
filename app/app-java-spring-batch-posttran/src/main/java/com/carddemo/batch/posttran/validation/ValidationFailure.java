package com.carddemo.batch.posttran.validation;

/** WS-VALIDATION-TRAILER content: 9(04) reason code + X(76) description. */
public record ValidationFailure(int code, String description) {

    public static final ValidationFailure INVALID_CARD = new ValidationFailure(100, "INVALID CARD NUMBER FOUND");
    public static final ValidationFailure ACCOUNT_NOT_FOUND = new ValidationFailure(101, "ACCOUNT RECORD NOT FOUND");
    public static final ValidationFailure OVERLIMIT = new ValidationFailure(102, "OVERLIMIT TRANSACTION");
    public static final ValidationFailure EXPIRED = new ValidationFailure(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
}
