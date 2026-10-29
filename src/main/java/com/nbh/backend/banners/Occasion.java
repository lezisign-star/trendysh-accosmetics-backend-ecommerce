package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/** One of the shop's moments (V84): when it is on, and how banners for it should feel. See {@link OccasionCalendar}. */
@Getter
@Setter
@Entity
@Table(name = "occasion")
public class Occasion extends BaseEntity {

    public enum Kind { WEEKLY, MONTHLY, YEARLY, DATES }

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    /** WEEKLY: 1 = Monday .. 7 = Sunday. */
    @Column(name = "start_dow")
    private Integer startDow;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_dow")
    private Integer endDow;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** MONTHLY: day of the month; the window wraps past the month's end when end_day is smaller. */
    @Column(name = "start_day")
    private Integer startDay;

    @Column(name = "end_day")
    private Integer endDay;

    /** YEARLY: "MM-DD". */
    @Column(name = "start_md", length = 5)
    private String startMd;

    @Column(name = "end_md", length = 5)
    private String endMd;

    /** DATES: once. */
    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(nullable = false)
    private int priority = 10;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String tone;

    @Column(name = "mood_line", nullable = false, length = 120)
    private String moodLine;

    /** "#RRGGBB,#RRGGBB": the main colour, then the light one. */
    @Column(nullable = false, length = 40)
    private String colors;
}
