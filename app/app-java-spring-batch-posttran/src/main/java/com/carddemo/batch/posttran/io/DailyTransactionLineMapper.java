package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.model.DailyTransaction;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;

/** Tokenizes a 350-byte line and retains it verbatim as {@code rawRecord}. */
public class DailyTransactionLineMapper implements LineMapper<DailyTransaction> {

    private final FixedLengthTokenizer tokenizer = DailyTransactionFieldSetMapper.tokenizer();
    private final DailyTransactionFieldSetMapper mapper = new DailyTransactionFieldSetMapper();

    @Override
    public DailyTransaction mapLine(String line, int lineNumber) {
        return mapper.mapFieldSet(tokenizer.tokenize(line), line);
    }
}
