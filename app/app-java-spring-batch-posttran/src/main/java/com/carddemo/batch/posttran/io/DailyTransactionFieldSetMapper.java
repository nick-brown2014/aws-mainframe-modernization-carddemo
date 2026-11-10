package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.model.DailyTransaction;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;

import static com.carddemo.batch.posttran.io.FixedWidth.parseUnsigned;

/** Maps the 14 CVTRA06Y fields (spec section 4.2 / 6.3). */
public class DailyTransactionFieldSetMapper implements FieldSetMapper<DailyTransaction> {

    public static final String[] NAMES = {"tranId", "tranTypeCd", "tranCatCd", "tranSource", "tranDesc", "tranAmt",
            "merchantId", "merchantName", "merchantCity", "merchantZip", "cardNum", "origTs", "procTs", "filler"};

    public static final Range[] RANGES = {new Range(1, 16), new Range(17, 18), new Range(19, 22), new Range(23, 32),
            new Range(33, 132), new Range(133, 143), new Range(144, 152), new Range(153, 202), new Range(203, 252),
            new Range(253, 262), new Range(263, 278), new Range(279, 304), new Range(305, 330), new Range(331, 350)};

    public static FixedLengthTokenizer tokenizer() {
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setNames(NAMES);
        tokenizer.setColumns(RANGES);
        tokenizer.setStrict(true);
        return tokenizer;
    }

    @Override
    public DailyTransaction mapFieldSet(FieldSet fs) {
        return mapFieldSet(fs, null);
    }

    public DailyTransaction mapFieldSet(FieldSet fs, String rawRecord) {
        return new DailyTransaction(
                fs.readRawString("tranId"),
                fs.readRawString("tranTypeCd"),
                (int) parseUnsigned(fs.readRawString("tranCatCd"), "DALYTRAN-CAT-CD"),
                fs.readRawString("tranSource"),
                fs.readRawString("tranDesc"),
                ZonedDecimal.parse(fs.readRawString("tranAmt"), 2),
                parseUnsigned(fs.readRawString("merchantId"), "DALYTRAN-MERCHANT-ID"),
                fs.readRawString("merchantName"),
                fs.readRawString("merchantCity"),
                fs.readRawString("merchantZip"),
                fs.readRawString("cardNum"),
                fs.readRawString("origTs"),
                fs.readRawString("procTs"),
                fs.readRawString("filler"),
                rawRecord);
    }
}
