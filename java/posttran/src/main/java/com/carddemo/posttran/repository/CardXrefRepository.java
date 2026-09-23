package com.carddemo.posttran.repository;

import com.carddemo.posttran.domain.CardXref;
import org.springframework.data.jpa.repository.JpaRepository;

/** XREFFILE (VSAM KSDS keyed by {@code XREF-CARD-NUM}). */
public interface CardXrefRepository extends JpaRepository<CardXref, String> {
}
