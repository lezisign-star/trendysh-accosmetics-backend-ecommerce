package com.nbh.backend.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of the overview's trend lines (days with no orders are present with zeros). */
public record DailyPointDto(LocalDate date, long orders, BigDecimal revenue) {}
