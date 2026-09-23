package com.carddemo.posttran.domain;

/** Result of processing one daily transaction: either posted or rejected. */
public sealed interface PostingOutcome permits PostedTransaction, RejectedTransaction {

    DailyTransaction source();
}
