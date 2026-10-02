package com.nbh.backend.tumaboda;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/** The only implementation of {@link TumaBodaReconciliationService} until real credentials exist — see its javadoc. */
@Service
public class NoopTumaBodaReconciliationService implements TumaBodaReconciliationService {

    @Override
    public Optional<BigDecimal> remoteBalanceOwed() {
        return Optional.empty();
    }
}
