package com.nbh.backend.tumaboda;

import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.tumaboda.TumaBodaSettlementDtos.OutstandingSummary;
import com.nbh.backend.tumaboda.TumaBodaSettlementDtos.RecordSettlementRequest;
import com.nbh.backend.tumaboda.TumaBodaSettlementDtos.SettlementDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The TumaBoda settlement ledger (TUMABODA_PARITY_PLAN.md Section B) — what's owed, and recording a
 * remittance. Collection from customers is unchanged throughout: the TumaBoda fee is still bundled
 * into the order total and charged upfront via M-Pesa. This is purely the other side — what Trendysh
 * owes TumaBoda for deliveries it already booked, settled periodically by bank transfer.
 */
@Service
public class TumaBodaSettlementService {

    private final CustomerOrderRepository orderRepository;
    private final TumaBodaSettlementRepository settlementRepository;
    private final TumaBodaReconciliationService reconciliation;

    public TumaBodaSettlementService(CustomerOrderRepository orderRepository,
                                      TumaBodaSettlementRepository settlementRepository,
                                      TumaBodaReconciliationService reconciliation) {
        this.orderRepository = orderRepository;
        this.settlementRepository = settlementRepository;
        this.reconciliation = reconciliation;
    }

    @Transactional(readOnly = true)
    public OutstandingSummary outstanding() {
        return new OutstandingSummary(
                orderRepository.findOutstandingTumabodaDeliveries(),
                orderRepository.totalOutstandingTumabodaCost());
    }

    /** TumaBoda's own view of the balance, when reconciliation is configured — see {@link TumaBodaReconciliationService}. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> remoteBalance() {
        return reconciliation.remoteBalanceOwed();
    }

    @Transactional(readOnly = true)
    public List<SettlementDto> history() {
        return settlementRepository.findAllByOrderBySettledAtDescCreatedAtDesc().stream()
                .map(s -> SettlementDto.from(s, orderRepository.countByTumabodaSettlementId(s.getId())))
                .toList();
    }

    /**
     * Records a remittance Trendysh actually made and sweeps every currently-outstanding costed
     * delivery into it — a settlement always covers everything owed at the moment it's recorded,
     * not a hand-picked subset (matching how this is actually paid: periodically, in full).
     */
    @Transactional
    public SettlementDto record(RecordSettlementRequest request, UUID staffUserId) {
        TumaBodaSettlement settlement = new TumaBodaSettlement();
        settlement.setSettledAt(request.settledAt());
        settlement.setAmount(request.amount());
        settlement.setReference(request.reference());
        settlement.setNotes(request.notes());
        settlement.setCreatedByUserId(staffUserId);
        settlement = settlementRepository.save(settlement);

        int swept = orderRepository.sweepOutstandingTumabodaDeliveries(settlement.getId());
        return SettlementDto.from(settlement, swept);
    }
}
