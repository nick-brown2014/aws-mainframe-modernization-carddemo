package com.carddemo.posttran;

import java.math.BigDecimal;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
public class PostingProcessor implements ItemProcessor<DailyTransaction, PostingItem> {
    private final PostingRepository repository;

    public PostingProcessor(PostingRepository repository) {
        this.repository = repository;
    }

    @Override
    public PostingItem process(DailyTransaction daily) {
        var card = repository.card(daily.cardNum());
        if (card.isEmpty()) {
            return PostingItem.rejected(daily, 100, "INVALID CARD NUMBER FOUND");
        }
        var account = repository.account(card.orElseThrow().acctId());
        if (account.isEmpty()) {
            return PostingItem.rejected(daily, 101, "ACCOUNT RECORD NOT FOUND");
        }
        Account current = account.orElseThrow();
        BigDecimal tempBalance = current.currCycCredit().subtract(current.currCycDebit()).add(daily.amount());
        int reason = 0;
        String description = "";
        if (current.creditLimit().compareTo(tempBalance) < 0) {
            reason = 102;
            description = "OVERLIMIT TRANSACTION";
        }
        if (current.expirationDate().compareTo(daily.origTs().substring(0, 10)) < 0) {
            reason = 103;
            description = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
        }
        return reason == 0 ? PostingItem.valid(daily, current.id())
                : PostingItem.rejected(daily, reason, description);
    }
}
