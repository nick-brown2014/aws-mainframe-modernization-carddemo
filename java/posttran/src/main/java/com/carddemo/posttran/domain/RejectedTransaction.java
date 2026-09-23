package com.carddemo.posttran.domain;

/** A daily transaction that failed {@code 1500-VALIDATE-TRAN}, destined for DALYREJS. */
public record RejectedTransaction(DailyTransaction source, ValidationResult reason) implements PostingOutcome {
}
