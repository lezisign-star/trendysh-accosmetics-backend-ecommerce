package com.nbh.backend.delivery.transport;

import com.nbh.backend.common.exception.NotFoundException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Metros (owner, 2026-10-28): the operators a shopper can send an order with, and the drop-off locations shoppers and
 * staff have named for each, with the likely fare. A location typed at checkout is kept straight away, tidied
 * ({@link TransportStops#clean}), and offered to the next shopper of that operator; a similar spelling finds the same
 * place ({@link TransportStops#key}). The fare is paid to the conductor on delivery, for now.
 */
@Service
public class TransportService {

    /** How many locations a shopper is offered as they type. */
    static final int SUGGESTED = 8;

    public record OperatorDto(UUID id, String name, String areas) {}

    public record StopDto(UUID id, String name, BigDecimal fareMin, BigDecimal fareMax, boolean fareConfirmed) {
        static StopDto from(TransportStop s) {
            return new StopDto(s.getId(), s.getName(), s.getFareMin(), s.getFareMax(), s.isFareConfirmed());
        }
    }

    public record StaffOperatorDto(UUID id, String name, String areas, boolean active, int sortOrder, int picks) {
        static StaffOperatorDto from(TransportOperator o) {
            return new StaffOperatorDto(o.getId(), o.getName(), o.getAreas(), o.isActive(), o.getSortOrder(), o.getPicks());
        }
    }

    public record StaffStopDto(UUID id, String name, BigDecimal fareMin, BigDecimal fareMax, boolean fareConfirmed,
                               int uses, boolean hidden, String addedBy) {
        static StaffStopDto from(TransportStop s) {
            return new StaffStopDto(s.getId(), s.getName(), s.getFareMin(), s.getFareMax(), s.isFareConfirmed(),
                    s.getUses(), s.isHidden(), s.getAddedBy());
        }
    }

    public record SaveOperator(@NotBlank @Size(max = 80) String name, @Size(max = 300) String areas,
                               Boolean active, Integer sortOrder) {}

    public record SaveStop(@NotBlank @Size(max = 120) String name,
                           @DecimalMin("0") BigDecimal fareMin, @DecimalMin("0") BigDecimal fareMax, Boolean hidden) {}

    /** What an order carries from the shopper's Metro choice: the operator, the tidied location and its likely fare. */
    public record Resolved(UUID operatorId, String operatorName, String stopName, BigDecimal fareMin, BigDecimal fareMax) {}

    private final TransportOperatorRepository operators;
    private final TransportStopRepository stops;

    public TransportService(TransportOperatorRepository operators, TransportStopRepository stops) {
        this.operators = operators;
        this.stops = stops;
    }

    // ---- shoppers ----

    @Transactional(readOnly = true)
    public List<OperatorDto> operatorsForShoppers() {
        return operators.activeForShoppers().stream()
                .map(o -> new OperatorDto(o.getId(), o.getName(), o.getAreas())).toList();
    }

    @Transactional(readOnly = true)
    public List<StopDto> suggest(UUID operatorId, String typed) {
        String key = TransportStops.key(typed == null ? "" : typed);
        return stops.suggest(operatorId, key, PageRequest.of(0, SUGGESTED)).stream().map(StopDto::from).toList();
    }

    /**
     * At checkout: the operator the shopper chose and where it goes. A location not seen before for that operator is
     * kept for the next shopper; one seen before (by its key) is the same place, with its fare. Null when the operator is
     * unknown or switched off (the order then goes as plain text, as before).
     */
    @Transactional
    public Resolved resolveForOrder(UUID operatorId, String typedStop) {
        if (operatorId == null) return null;
        TransportOperator operator = operators.findById(operatorId).filter(TransportOperator::isActive).orElse(null);
        if (operator == null) return null;
        operators.countPick(operator.getId());
        String name = TransportStops.clean(typedStop);
        if (name == null) return new Resolved(operator.getId(), operator.getName(), null, null, null);
        String key = TransportStops.key(name);
        TransportStop stop = stops.findByOperatorIdAndNameKey(operator.getId(), key).orElse(null);
        if (stop == null) {
            stops.addIfNew(operator.getId(), name, key);
            stop = stops.findByOperatorIdAndNameKey(operator.getId(), key).orElseThrow();
        }
        stops.countUse(stop.getId());
        return new Resolved(operator.getId(), operator.getName(), stop.getName(), stop.getFareMin(), stop.getFareMax());
    }

    // ---- staff ----

    @Transactional(readOnly = true)
    public List<StaffOperatorDto> operatorsForStaff() {
        return operators.allForStaff().stream().map(StaffOperatorDto::from).toList();
    }

    @Transactional
    public StaffOperatorDto saveOperator(UUID id, SaveOperator body) {
        TransportOperator o = id == null ? new TransportOperator()
                : operators.findById(id).orElseThrow(() -> new NotFoundException("No such operator: " + id));
        String name = body.name().trim().replaceAll("\\s+", " ");
        operators.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(o.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("There is already an operator called " + other.getName());
                });
        o.setName(name);
        o.setAreas(body.areas() == null || body.areas().isBlank() ? null : body.areas().trim());
        if (body.active() != null) o.setActive(body.active());
        if (body.sortOrder() != null) o.setSortOrder(body.sortOrder());
        return StaffOperatorDto.from(operators.save(o));
    }

    @Transactional(readOnly = true)
    public List<StaffStopDto> stopsForStaff(UUID operatorId) {
        return stops.allForStaff(operatorId).stream().map(StaffStopDto::from).toList();
    }

    /** Staff add a location or correct one: its name tidied like a shopper's, and a fare set by staff counts as confirmed. */
    @Transactional
    public StaffStopDto saveStop(UUID operatorId, UUID stopId, SaveStop body) {
        TransportOperator operator = operators.findById(operatorId)
                .orElseThrow(() -> new NotFoundException("No such operator: " + operatorId));
        TransportStop s = stopId == null ? new TransportStop()
                : stops.findById(stopId).filter(x -> x.getOperator().getId().equals(operatorId))
                        .orElseThrow(() -> new NotFoundException("No such location: " + stopId));
        String name = TransportStops.clean(body.name());
        if (name == null) throw new IllegalArgumentException("Give the location a name");
        String key = TransportStops.key(name);
        stops.findByOperatorIdAndNameKey(operatorId, key)
                .filter(other -> !other.getId().equals(s.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException(operator.getName() + " already has " + other.getName());
                });
        if (body.fareMin() != null && body.fareMax() != null && body.fareMin().compareTo(body.fareMax()) > 0) {
            throw new IllegalArgumentException("The lowest fare is more than the highest");
        }
        if (stopId == null) {
            s.setOperator(operator);
            s.setAddedBy("STAFF");
        }
        s.setName(name);
        s.setNameKey(key);
        s.setFareMin(body.fareMin());
        s.setFareMax(body.fareMax() != null ? body.fareMax() : body.fareMin());
        s.setFareConfirmed(body.fareMin() != null || body.fareMax() != null);
        if (body.hidden() != null) s.setHidden(body.hidden());
        return StaffStopDto.from(stops.save(s));
    }
}
