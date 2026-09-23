package com.carddemo.batch.posttran.repo;

import com.carddemo.batch.posttran.model.CardXref;

import java.util.Optional;

public interface CardXrefRepository {

    /** XREFFILE random READ. */
    Optional<CardXref> findByCardNum(String cardNum);

    void insert(CardXref xref);
}
