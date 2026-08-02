package com.backupmanager.core.schedule;

import com.backupmanager.core.model.BackupException;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute, int second) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0, UTC);
    }

    private static ZonedDateTime next(String expression, ZonedDateTime from) {
        return CronExpression.parse(expression).next(from);
    }

    @Test
    void firesDailyAtTheGivenTime() {
        ZonedDateTime result = next("0 30 2 * * *", at(2026, 8, 2, 1, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 2, 2, 30, 0));
    }

    @Test
    void rollsToTomorrowWhenTodayHasPassed() {
        ZonedDateTime result = next("0 30 2 * * *", at(2026, 8, 2, 3, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 3, 2, 30, 0));
    }

    @Test
    void acceptsFiveFieldExpressionsWithImplicitSeconds() {
        ZonedDateTime result = next("30 2 * * *", at(2026, 8, 2, 1, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 2, 2, 30, 0));
    }

    @Test
    void handlesStepValues() {
        ZonedDateTime result = next("0 */15 * * * *", at(2026, 8, 2, 10, 7, 0));

        assertThat(result).isEqualTo(at(2026, 8, 2, 10, 15, 0));
    }

    @Test
    void handlesLists() {
        ZonedDateTime result = next("0 0 6,12,18 * * *", at(2026, 8, 2, 7, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 2, 12, 0, 0));
    }

    @Test
    void handlesRanges() {
        ZonedDateTime result = next("0 0 9-17 * * *", at(2026, 8, 2, 6, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 2, 9, 0, 0));
    }

    @Test
    void handlesWrappingRanges() {
        // 22-02 spans midnight and must wrap rather than match nothing.
        ZonedDateTime result = next("0 0 22-2 * * *", at(2026, 8, 2, 12, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 2, 22, 0, 0));
    }

    @Test
    void handlesDayOfWeekNames() {
        // 2026-08-02 is a Sunday, so the next Monday is the 3rd.
        ZonedDateTime result = next("0 0 3 ? * MON", at(2026, 8, 2, 12, 0, 0));

        assertThat(result).isEqualTo(at(2026, 8, 3, 3, 0, 0));
    }

    @Test
    void treatsSevenAsSunday() {
        ZonedDateTime fromSeven = next("0 0 0 ? * 7", at(2026, 8, 3, 0, 0, 0));
        ZonedDateTime fromZero = next("0 0 0 ? * 0", at(2026, 8, 3, 0, 0, 0));

        assertThat(fromSeven).isEqualTo(fromZero);
        assertThat(fromSeven.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
    }

    @Test
    void handlesMonthNames() {
        ZonedDateTime result = next("0 0 0 1 JAN *", at(2026, 8, 2, 0, 0, 0));

        assertThat(result).isEqualTo(at(2027, 1, 1, 0, 0, 0));
    }

    @Test
    void handlesLastDayOfMonth() {
        ZonedDateTime result = next("0 0 23 L * *", at(2026, 2, 10, 0, 0, 0));

        assertThat(result).isEqualTo(at(2026, 2, 28, 23, 0, 0));
    }

    @Test
    void unionsDayOfMonthAndDayOfWeekWhenBothRestricted() {
        // Standard cron: with both fields set, either one matching is enough.
        // The 1st of August 2026 is a Saturday; the 3rd is the next Monday.
        CronExpression cron = CronExpression.parse("0 0 0 15 * MON");

        assertThat(cron.next(at(2026, 8, 2, 0, 0, 0))).isEqualTo(at(2026, 8, 3, 0, 0, 0));
        assertThat(cron.next(at(2026, 8, 12, 0, 0, 0))).isEqualTo(at(2026, 8, 15, 0, 0, 0));
    }

    @Test
    void expandsMacros() {
        assertThat(next("@daily", at(2026, 8, 2, 5, 0, 0))).isEqualTo(at(2026, 8, 3, 0, 0, 0));
        assertThat(next("@hourly", at(2026, 8, 2, 5, 30, 0))).isEqualTo(at(2026, 8, 2, 6, 0, 0));
        assertThat(next("@monthly", at(2026, 8, 2, 5, 0, 0))).isEqualTo(at(2026, 9, 1, 0, 0, 0));
    }

    @Test
    void returnsNullWhenNothingCanEverMatch() {
        // 30 February exists in no year.
        assertThat(next("0 0 0 30 2 *", at(2026, 1, 1, 0, 0, 0))).isNull();
    }

    @Test
    void neverReturnsTheInstantItWasGiven() {
        ZonedDateTime moment = at(2026, 8, 2, 2, 30, 0);

        assertThat(next("0 30 2 * * *", moment)).isAfter(moment);
    }

    @Test
    void rejectsMalformedExpressions() {
        assertThatThrownBy(() -> CronExpression.parse("not a cron"))
                .isInstanceOf(BackupException.Configuration.class);
        assertThatThrownBy(() -> CronExpression.parse("0 0"))
                .isInstanceOf(BackupException.Configuration.class)
                .hasMessageContaining("5 or 6 fields");
        assertThatThrownBy(() -> CronExpression.parse("0 0 99 * * *"))
                .isInstanceOf(BackupException.Configuration.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> CronExpression.parse("0 0 0 * * NOTADAY"))
                .isInstanceOf(BackupException.Configuration.class);
        assertThatThrownBy(() -> CronExpression.parse("0 */0 * * * *"))
                .isInstanceOf(BackupException.Configuration.class)
                .hasMessageContaining("Step must be positive");
    }

    @Test
    void validatesWithoutThrowing() {
        assertThat(CronExpression.isValid("0 0 2 * * *")).isTrue();
        assertThat(CronExpression.isValid("nonsense")).isFalse();
    }

    @Test
    void survivesTheSpringForwardGap() {
        // Europe/London jumps 01:00 -> 02:00 on 2026-03-29, so 01:30 does not exist.
        ZoneId london = ZoneId.of("Europe/London");
        CronExpression cron = CronExpression.parse("0 30 1 * * *");

        ZonedDateTime result = cron.next(ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, london));

        assertThat(result).isNotNull();
        assertThat(result).isAfter(ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, london));
    }
}
