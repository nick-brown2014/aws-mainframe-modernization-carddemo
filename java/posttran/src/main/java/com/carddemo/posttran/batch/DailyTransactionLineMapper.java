package com.carddemo.posttran.batch;

import com.carddemo.posttran.cobol.CobolFields;
import com.carddemo.posttran.domain.DailyTransaction;
import com.carddemo.posttran.layout.DailyTransactionLayout;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;

/**
 * Maps one DALYTRAN line onto a {@link DailyTransaction} using the CVTRA06Y fixed-length layout
 * ({@code 1000-DALYTRAN-GET-NEXT}: {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD}).
 *
 * <p>Alphanumeric fields keep their full COBOL width (space padded, not trimmed); numeric fields
 * are decoded from zoned decimal.
 */
public class DailyTransactionLineMapper implements LineMapper<DailyTransaction> {

    private final FixedLengthTokenizer tokenizer = DailyTransactionLayout.tokenizer();

    @Override
    public DailyTransaction mapLine(String line, int lineNumber) {
        String record = DailyTransactionLayout.pad(line);
        FieldSet fs = tokenizer.tokenize(record);

        DailyTransaction t = new DailyTransaction();
        t.setRawRecord(record);
        t.setTranId(fs.readRawString("tranId"));
        t.setTypeCd(fs.readRawString("typeCd"));
        t.setCatCd((int) CobolFields.parseUnsigned(fs.readRawString("catCd")));
        t.setSource(fs.readRawString("source"));
        t.setDescription(fs.readRawString("description"));
        t.setAmount(CobolFields.parseZoned(fs.readRawString("amount"), 2));
        t.setMerchantId(CobolFields.parseUnsigned(fs.readRawString("merchantId")));
        t.setMerchantName(fs.readRawString("merchantName"));
        t.setMerchantCity(fs.readRawString("merchantCity"));
        t.setMerchantZip(fs.readRawString("merchantZip"));
        t.setCardNum(fs.readRawString("cardNum"));
        t.setOrigTs(fs.readRawString("origTs"));
        t.setProcTs(fs.readRawString("procTs"));
        return t;
    }
}
