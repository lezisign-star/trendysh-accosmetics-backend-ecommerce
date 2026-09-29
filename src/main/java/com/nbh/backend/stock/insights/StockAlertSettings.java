package com.nbh.backend.stock.insights;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Singleton row (created by V43) — the numbers the forecast and the alert emails run on. */
@Getter
@Setter
@Entity
@Table(name = "stock_alert_settings")
public class StockAlertSettings extends BaseEntity {

    /** Days between placing a supplier order and the stock arriving — sets the suggested reorder level. */
    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays = 7;

    /** Extra days of cover on top of the lead time, so a slow delivery doesn't mean a stock-out. */
    @Column(name = "safety_days", nullable = false)
    private int safetyDays = 3;

    @Column(name = "digest_enabled", nullable = false)
    private boolean digestEnabled = true;

    /** The day the daily digest last went out, so a restart at 07:00 can't send it twice. */
    @Column(name = "last_digest_on")
    private LocalDate lastDigestOn;
}
