package com.nbh.backend.banners;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which occasions are on at a moment, in Nairobi time, and when each ends (owner, 2026-10-29). Plain rules, so the
 * shop needs no calendar service: a weekly window (the weekend), a monthly one that may wrap past the month's end
 * (payday, the 25th to the 2nd), a yearly one (Black November, December) and fixed dates (staff's own).
 */
public final class OccasionCalendar {

    public static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private OccasionCalendar() {
    }

    /** When the occasion's current window ends, if it is on at {@code now}; empty when it is not on (or switched off). */
    public static Optional<Instant> endsAt(Occasion o, Instant now) {
        if (!o.isEnabled() || o.getKind() == null) return Optional.empty();
        ZonedDateTime t = now.atZone(NAIROBI);
        return switch (o.getKind()) {
            case WEEKLY -> weekly(o, t);
            case MONTHLY -> monthly(o, t);
            case YEARLY -> yearly(o, t);
            case DATES -> dates(o, t);
        };
    }

    /** The occasions on at {@code now}, the highest priority first. */
    public static List<Occasion> active(List<Occasion> all, Instant now) {
        return all.stream()
                .filter(o -> endsAt(o, now).isPresent())
                .sorted(Comparator.comparingInt(Occasion::getPriority).reversed().thenComparing(Occasion::getCode))
                .toList();
    }

    private static Optional<Instant> weekly(Occasion o, ZonedDateTime t) {
        if (o.getStartDow() == null || o.getEndDow() == null) return Optional.empty();
        LocalTime from = o.getStartTime() == null ? LocalTime.MIDNIGHT : o.getStartTime();
        LocalTime to = o.getEndTime() == null ? LocalTime.of(23, 59) : o.getEndTime();
        // This week's window, and last week's (a window running from Friday to Monday is still on on Monday).
        for (int back = 0; back <= 1; back++) {
            LocalDate monday = t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(back);
            LocalDateTime start = monday.plusDays(o.getStartDow() - 1L).atTime(from);
            LocalDateTime end = monday.plusDays(o.getEndDow() - 1L).atTime(to);
            if (end.isBefore(start)) end = end.plusWeeks(1);
            LocalDateTime local = t.toLocalDateTime();
            if (!local.isBefore(start) && local.isBefore(end.plusMinutes(1))) {
                return Optional.of(end.plusMinutes(1).atZone(NAIROBI).toInstant());
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> monthly(Occasion o, ZonedDateTime t) {
        if (o.getStartDay() == null || o.getEndDay() == null) return Optional.empty();
        int day = t.getDayOfMonth();
        int from = o.getStartDay();
        int to = o.getEndDay();
        YearMonth month = YearMonth.from(t);
        if (from <= to) {
            if (day < from || day > Math.min(to, month.lengthOfMonth())) return Optional.empty();
            return Optional.of(endOfDay(month.atDay(Math.min(to, month.lengthOfMonth()))));
        }
        // Wraps: the 25th to the 2nd.
        if (day >= from) return Optional.of(endOfDay(month.plusMonths(1).atDay(Math.min(to, month.plusMonths(1).lengthOfMonth()))));
        if (day <= to) return Optional.of(endOfDay(month.atDay(to)));
        return Optional.empty();
    }

    private static Optional<Instant> yearly(Occasion o, ZonedDateTime t) {
        if (o.getStartMd() == null || o.getEndMd() == null) return Optional.empty();
        MonthDay from = MonthDay.parse("--" + o.getStartMd());
        MonthDay to = MonthDay.parse("--" + o.getEndMd());
        MonthDay today = MonthDay.from(t);
        int year = t.getYear();
        if (!from.isAfter(to)) {
            if (today.isBefore(from) || today.isAfter(to)) return Optional.empty();
            return Optional.of(endOfDay(to.atYear(year)));
        }
        // Wraps the new year: 20 December to 5 January.
        if (!today.isBefore(from)) return Optional.of(endOfDay(to.atYear(year + 1)));
        if (!today.isAfter(to)) return Optional.of(endOfDay(to.atYear(year)));
        return Optional.empty();
    }

    private static Optional<Instant> dates(Occasion o, ZonedDateTime t) {
        if (o.getStartsOn() == null || o.getEndsOn() == null) return Optional.empty();
        LocalDate d = t.toLocalDate();
        if (d.isBefore(o.getStartsOn()) || d.isAfter(o.getEndsOn())) return Optional.empty();
        return Optional.of(endOfDay(o.getEndsOn()));
    }

    private static Instant endOfDay(LocalDate d) {
        return d.plusDays(1).atStartOfDay(NAIROBI).toInstant();
    }
}
