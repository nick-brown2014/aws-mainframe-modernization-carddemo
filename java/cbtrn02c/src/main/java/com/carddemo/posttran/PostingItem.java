package com.carddemo.posttran;

import java.util.Locale;

public record PostingItem(DailyTransaction daily, String accountId, int failReason, String failDescription) {
    public static PostingItem valid(DailyTransaction daily, String accountId) {
        return new PostingItem(daily, accountId, 0, "");
    }

    public static PostingItem rejected(DailyTransaction daily, int reason, String description) {
        return new PostingItem(daily, null, reason, description);
    }

    public boolean isRejected() {
        return failReason != 0;
    }

    public String rejectRecord() {
        if (!isRejected() || failDescription.length() > 76) {
            throw new IllegalStateException("Invalid rejection trailer");
        }
        return daily.originalRecord() + String.format(Locale.ROOT, "%04d%-76s", failReason, failDescription);
    }
}
