package com.carddemo.posttran.layout;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.DailyTransaction;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;

/**
 * Copybook CVTRA06Y ({@code DALYTRAN-RECORD}, RECLN 350). Ranges are 1-based inclusive, as in
 * Spring Batch's {@link Range}.
 */
public final class DailyTransactionLayout {

    public static final int RECORD_LENGTH = DailyTransaction.RECORD_LENGTH;

    public static final String[] FIELD_NAMES = {
        "tranId", "typeCd", "catCd", "source", "description", "amount", "merchantId",
        "merchantName", "merchantCity", "merchantZip", "cardNum", "origTs", "procTs", "filler"
    };

    public static final Range[] RANGES = {
        new Range(1, 16),     // DALYTRAN-ID            PIC X(16)
        new Range(17, 18),    // DALYTRAN-TYPE-CD       PIC X(02)
        new Range(19, 22),    // DALYTRAN-CAT-CD        PIC 9(04)
        new Range(23, 32),    // DALYTRAN-SOURCE        PIC X(10)
        new Range(33, 132),   // DALYTRAN-DESC          PIC X(100)
        new Range(133, 143),  // DALYTRAN-AMT           PIC S9(09)V99
        new Range(144, 152),  // DALYTRAN-MERCHANT-ID   PIC 9(09)
        new Range(153, 202),  // DALYTRAN-MERCHANT-NAME PIC X(50)
        new Range(203, 252),  // DALYTRAN-MERCHANT-CITY PIC X(50)
        new Range(253, 262),  // DALYTRAN-MERCHANT-ZIP  PIC X(10)
        new Range(263, 278),  // DALYTRAN-CARD-NUM      PIC X(16)
        new Range(279, 304),  // DALYTRAN-ORIG-TS       PIC X(26)
        new Range(305, 330),  // DALYTRAN-PROC-TS       PIC X(26)
        new Range(331, 350)   // FILLER                 PIC X(20)
    };

    private DailyTransactionLayout() {
    }

    public static FixedLengthTokenizer tokenizer() {
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setNames(FIELD_NAMES);
        tokenizer.setColumns(RANGES);
        tokenizer.setStrict(false);
        return tokenizer;
    }

    /** Pads a raw line to the fixed record length, as a fixed-block read would. */
    public static String pad(String line) {
        return CobolFields.alnum(line, RECORD_LENGTH);
    }
}
