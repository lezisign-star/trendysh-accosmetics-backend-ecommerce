package com.nbh.backend.banners;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The occasion rules in Nairobi time (owner, 2026-10-29): weekend, payday, Black November, December, fixed dates. */
class OccasionCalendarTest {

    private static Instant at(String nairobi) {
        return LocalDateTime.parse(nairobi).atZone(OccasionCalendar.NAIROBI).toInstant();
    }

    private static Occasion weekend() {
        Occasion o = new Occasion();
        o.setCode("weekend");
        o.setKind(Occasion.Kind.WEEKLY);
        o.setStartDow(5);
        o.setStartTime(LocalTime.of(16, 0));
        o.setEndDow(7);
        o.setEndTime(LocalTime.of(23, 59));
        o.setPriority(10);
        return o;
    }

    private static Occasion payday() {
        Occasion o = new Occasion();
        o.setCode("payday");
        o.setKind(Occasion.Kind.MONTHLY);
        o.setStartDay(25);
        o.setEndDay(2);
        o.setPriority(20);
        return o;
    }

    private static Occasion yearly(String code, String from, String to, int priority) {
        Occasion o = new Occasion();
        o.setCode(code);
        o.setKind(Occasion.Kind.YEARLY);
        o.setStartMd(from);
        o.setEndMd(to);
        o.setPriority(priority);
        return o;
    }

    @Test
    void theWeekendRunsFromFridayFourPmToSundayNight() {
        // 30 October 2026 is a Friday.
        assertThat(OccasionCalendar.endsAt(weekend(), at("2026-10-30T15:59"))).isEmpty();
        assertThat(OccasionCalendar.endsAt(weekend(), at("2026-10-30T16:00"))).contains(at("2026-11-02T00:00"));
        assertThat(OccasionCalendar.endsAt(weekend(), at("2026-11-01T23:59"))).isPresent();
        assertThat(OccasionCalendar.endsAt(weekend(), at("2026-11-02T00:00"))).isEmpty();
        assertThat(OccasionCalendar.endsAt(weekend(), at("2026-11-04T12:00"))).isEmpty();
    }

    @Test
    void paydayWrapsPastTheMonthsEnd() {
        assertThat(OccasionCalendar.endsAt(payday(), at("2026-10-24T23:00"))).isEmpty();
        assertThat(OccasionCalendar.endsAt(payday(), at("2026-10-25T00:00"))).contains(at("2026-11-03T00:00"));
        assertThat(OccasionCalendar.endsAt(payday(), at("2026-11-02T20:00"))).contains(at("2026-11-03T00:00"));
        assertThat(OccasionCalendar.endsAt(payday(), at("2026-11-03T00:00"))).isEmpty();
        // across the year's end
        assertThat(OccasionCalendar.endsAt(payday(), at("2026-12-28T10:00"))).contains(at("2027-01-03T00:00"));
    }

    @Test
    void yearlyOccasionsAndTheMostImportantOneFirst() {
        Occasion blackNovember = yearly("black-november", "11-01", "11-30", 40);
        assertThat(OccasionCalendar.endsAt(blackNovember, at("2026-11-15T09:00"))).contains(at("2026-12-01T00:00"));
        assertThat(OccasionCalendar.endsAt(blackNovember, at("2026-12-01T00:00"))).isEmpty();
        Occasion newYear = yearly("new-year", "12-20", "01-05", 30);
        assertThat(OccasionCalendar.endsAt(newYear, at("2026-12-31T22:00"))).contains(at("2027-01-06T00:00"));
        assertThat(OccasionCalendar.endsAt(newYear, at("2027-01-03T10:00"))).contains(at("2027-01-06T00:00"));

        // Black Friday weekend: Black November wins over payday and the weekend.
        List<Occasion> on = OccasionCalendar.active(List.of(weekend(), payday(), blackNovember), at("2026-11-27T18:00"));
        assertThat(on).extracting(Occasion::getCode).containsExactly("black-november", "payday", "weekend");
    }

    @Test
    void fixedDatesAndSwitchedOffOccasions() {
        Occasion own = new Occasion();
        own.setCode("anniversary");
        own.setKind(Occasion.Kind.DATES);
        own.setStartsOn(LocalDate.of(2026, 11, 10));
        own.setEndsOn(LocalDate.of(2026, 11, 12));
        assertThat(OccasionCalendar.endsAt(own, at("2026-11-12T23:00"))).contains(at("2026-11-13T00:00"));
        assertThat(OccasionCalendar.endsAt(own, at("2026-11-13T00:00"))).isEmpty();
        own.setEnabled(false);
        assertThat(OccasionCalendar.endsAt(own, at("2026-11-11T12:00"))).isEmpty();
    }
}
