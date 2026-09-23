package com.carddemo.posttran.domain;

/**
 * {@code WS-VALIDATION-TRAILER}: a 4-digit failure reason ({@code PIC 9(04)}) and a 76-character
 * description. Reason 0 means the transaction passed validation.
 */
public record ValidationResult(int reasonCode, String description) {

    public static final int REASON_CODE_LENGTH = 4;
    public static final int DESCRIPTION_LENGTH = 76;
    public static final int TRAILER_LENGTH = REASON_CODE_LENGTH + DESCRIPTION_LENGTH;

    public static final ValidationResult VALID = new ValidationResult(0, "");

    public static final ValidationResult INVALID_CARD_NUMBER =
            new ValidationResult(100, "INVALID CARD NUMBER FOUND");
    public static final ValidationResult ACCOUNT_NOT_FOUND =
            new ValidationResult(101, "ACCOUNT RECORD NOT FOUND");
    public static final ValidationResult OVERLIMIT =
            new ValidationResult(102, "OVERLIMIT TRANSACTION");
    public static final ValidationResult AFTER_EXPIRATION =
            new ValidationResult(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

    public boolean isValid() {
        return reasonCode == 0;
    }

    public boolean isRejected() {
        return !isValid();
    }
}
