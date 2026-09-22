package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.PromoCampaignRepository;
import com.nbh.backend.common.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one site-wide discount campaign. At most one may be "currently active" at a time — creating
 * or activating one that overlaps an already-active campaign is refused with a clear reason,
 * rather than silently letting two stack (a shopper would see whichever query ran to resolve the
 * price, which is exactly the kind of ambiguity {@code EffectivePriceResolver} is built to avoid).
 */
@Service
public class PromoCampaignService {

    private final PromoCampaignRepository repository;
    private final Clock clock;

    public PromoCampaignService(PromoCampaignRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PromoCampaign getActiveOrNull() {
        return repository.findCurrentlyActive(Instant.now(clock)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PromoCampaign> list() {
        return repository.findAllByOrderByStartsAtDesc();
    }

    @Transactional
    public PromoCampaign create(String name, BigDecimal percentOff, Instant startsAt, Instant endsAt, boolean isActive) {
        validate(name, percentOff, startsAt, endsAt);
        PromoCampaign campaign = new PromoCampaign();
        campaign.setName(name.trim());
        campaign.setPercentOff(percentOff);
        campaign.setStartsAt(startsAt);
        campaign.setEndsAt(endsAt);
        campaign.setActive(isActive);
        if (isActive) assertNoOverlap(campaign, null);
        return repository.save(campaign);
    }

    @Transactional
    public PromoCampaign update(UUID id, String name, BigDecimal percentOff, Instant startsAt, Instant endsAt, boolean isActive) {
        PromoCampaign campaign = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No campaign: " + id));
        validate(name, percentOff, startsAt, endsAt);
        campaign.setName(name.trim());
        campaign.setPercentOff(percentOff);
        campaign.setStartsAt(startsAt);
        campaign.setEndsAt(endsAt);
        campaign.setActive(isActive);
        if (isActive) assertNoOverlap(campaign, id);
        return repository.save(campaign);
    }

    /** The staff kill-switch — turns a campaign off without touching its dates, so it can be re-enabled as-is. */
    @Transactional
    public PromoCampaign setActive(UUID id, boolean isActive) {
        PromoCampaign campaign = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No campaign: " + id));
        campaign.setActive(isActive);
        if (isActive) assertNoOverlap(campaign, id);
        return repository.save(campaign);
    }

    private void validate(String name, BigDecimal percentOff, Instant startsAt, Instant endsAt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A campaign needs a name");
        }
        if (percentOff == null || percentOff.signum() <= 0 || percentOff.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("percentOff must be greater than 0 and at most 90");
        }
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
    }

    /** Whether any OTHER active campaign's window overlaps this one's. */
    private void assertNoOverlap(PromoCampaign candidate, UUID selfId) {
        boolean overlaps = repository.findAll().stream()
                .filter(PromoCampaign::isActive)
                .filter(c -> selfId == null || !c.getId().equals(selfId))
                .anyMatch(c -> candidate.getStartsAt().isBefore(c.getEndsAt()) && c.getStartsAt().isBefore(candidate.getEndsAt()));
        if (overlaps) {
            throw new ConflictException(
                    "Another active campaign already covers part of that window — turn it off first, or pick dates that don't overlap");
        }
    }
}
