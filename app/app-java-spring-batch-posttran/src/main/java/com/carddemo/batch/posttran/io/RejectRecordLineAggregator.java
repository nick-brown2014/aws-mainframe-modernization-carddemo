package com.carddemo.batch.posttran.io;

import com.carddemo.batch.posttran.model.RejectRecord;
import org.springframework.batch.item.file.transform.LineAggregator;

/** 430-byte reject record: 350-byte original + 9(04) reason + X(76) description (spec section 7.1). */
public class RejectRecordLineAggregator implements LineAggregator<RejectRecord> {

    @Override
    public String aggregate(RejectRecord r) {
        return FixedWidth.padRight(r.rawRecord(), 350)
                + FixedWidth.zeroPad(r.failure().code(), 4)
                + FixedWidth.padRight(r.failure().description(), 76);
    }
}
