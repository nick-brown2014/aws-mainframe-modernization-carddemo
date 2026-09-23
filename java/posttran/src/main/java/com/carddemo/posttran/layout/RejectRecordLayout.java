package com.carddemo.posttran.layout;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.domain.RejectedTransaction;
import com.carddemo.posttran.domain.ValidationResult;

/**
 * DALYREJS record ({@code REJECT-RECORD}, LRECL 430): the original 350-byte DALYTRAN record
 * followed by the 80-byte {@code WS-VALIDATION-TRAILER} (4-digit reason + 76-char description).
 */
public final class RejectRecordLayout {

    public static final int RECORD_LENGTH = DailyTransaction.RECORD_LENGTH + ValidationResult.TRAILER_LENGTH;

    private RejectRecordLayout() {
    }

    public static String format(RejectedTransaction rejected) {
        return format(rejected.source(), rejected.reason());
    }

    public static String format(DailyTransaction source, ValidationResult reason) {
        return CobolFields.alnum(source.getRawRecord(), DailyTransaction.RECORD_LENGTH)
                + CobolFields.formatUnsigned(reason.reasonCode(), ValidationResult.REASON_CODE_LENGTH)
                + CobolFields.alnum(reason.description(), ValidationResult.DESCRIPTION_LENGTH);
    }
}
