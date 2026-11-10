package com.carddemo.batch.posttran.posting;

import com.carddemo.batch.posttran.model.Account;
import com.carddemo.batch.posttran.model.CardXref;
import com.carddemo.batch.posttran.model.DailyTransaction;
import com.carddemo.batch.posttran.model.PostingResult;
import com.carddemo.batch.posttran.model.PostingResult.Rejected;
import com.carddemo.batch.posttran.repo.AccountRepository;
import com.carddemo.batch.posttran.repo.CardXrefRepository;
import com.carddemo.batch.posttran.validation.TransactionValidator;
import com.carddemo.batch.posttran.validation.ValidationFailure;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 1500-VALIDATE-TRAN followed by 2000-POST-TRANSACTION (spec section 6.4). */
@Component
public class PostTranItemProcessor implements ItemProcessor<DailyTransaction, PostingResult> {

    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final ChunkStateCache cache;
    private final TransactionValidator validator = new TransactionValidator();
    private final PostingService postingService;

    public PostTranItemProcessor(CardXrefRepository cardXrefRepository, AccountRepository accountRepository,
                                 ChunkStateCache cache, PostingService postingService) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.cache = cache;
        this.postingService = postingService;
    }

    @Override
    public PostingResult process(DailyTransaction tran) {
        Optional<CardXref> xref = cardXrefRepository.findByCardNum(tran.cardNum());
        if (xref.isEmpty()) {
            return new Rejected(tran, ValidationFailure.INVALID_CARD);
        }

        Optional<Account> acct = cache.account(xref.get().acctId())
                .or(() -> accountRepository.findByIdForUpdate(xref.get().acctId()));
        if (acct.isEmpty()) {
            return new Rejected(tran, ValidationFailure.ACCOUNT_NOT_FOUND);
        }

        Optional<ValidationFailure> failure = validator.validateAgainstAccount(tran, acct.get());
        if (failure.isPresent()) {
            return new Rejected(tran, failure.get());
        }

        return postingService.post(tran, xref.get(), acct.get());
    }
}
