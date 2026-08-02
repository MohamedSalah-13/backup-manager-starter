package io.github.mohamedsalah13.backupmanager.core.schedule;

import io.github.mohamedsalah13.backupmanager.core.model.BackupException;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * Quartz-style cron parser with no external dependency, so the core module stays usable
 * outside Spring.
 *
 * <p>Accepts six fields ({@code second minute hour day-of-month month day-of-week}) or
 * five, in which case seconds default to {@code 0}. Supports {@code *}, {@code ?},
 * lists, ranges, steps, three-letter month and weekday names, {@code L} for the last day
 * of the month, and the macros {@code @yearly @annually @monthly @weekly @daily
 * @midnight @hourly}.
 *
 * <p>Day-of-month and day-of-week follow standard cron semantics: when both are
 * restricted the match is a union, and when either is {@code *} or {@code ?} only the
 * other constrains the date.
 */
public final class CronExpression {

    private static final Map<String, String> MACROS = Map.of(
            "@yearly", "0 0 0 1 1 *",
            "@annually", "0 0 0 1 1 *",
            "@monthly", "0 0 0 1 * *",
            "@weekly", "0 0 0 * * 0",
            "@daily", "0 0 0 * * *",
            "@midnight", "0 0 0 * * *",
            "@hourly", "0 0 * * * *");

    private static final String[] MONTH_NAMES =
            {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAY_NAMES =
            {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /** Give up rather than spin forever on an expression that can never match, e.g. Feb 30. */
    private static final int MAX_SEARCH_ITERATIONS = 40_000;

    private final String expression;
    private final BitSet seconds = new BitSet(60);
    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32);
    private final BitSet months = new BitSet(13);
    private final BitSet daysOfWeek = new BitSet(7);

    private boolean dayOfMonthUnrestricted;
    private boolean dayOfWeekUnrestricted;
    private boolean lastDayOfMonth;

    private CronExpression(String expression) {
        this.expression = expression;
    }

    /** @throws BackupException.Configuration when the expression is malformed */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new BackupException.Configuration("Cron expression must not be blank");
        }
        String normalized = expression.trim();
        String macro = MACROS.get(normalized.toLowerCase(Locale.ROOT));
        if (macro != null) {
            normalized = macro;
        }

        String[] fields = normalized.split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw new BackupException.Configuration(
                    "Cron expression must have 5 or 6 fields but had " + fields.length + ": " + expression);
        }

        CronExpression cron = new CronExpression(expression.trim());
        int offset = fields.length == 6 ? 0 : -1;
        if (fields.length == 5) {
            cron.seconds.set(0);
        } else {
            cron.parseField(fields[0], cron.seconds, 0, 59, null, "second");
        }
        cron.parseField(fields[offset + 1], cron.minutes, 0, 59, null, "minute");
        cron.parseField(fields[offset + 2], cron.hours, 0, 23, null, "hour");
        cron.parseDayOfMonth(fields[offset + 3]);
        cron.parseField(fields[offset + 4], cron.months, 1, 12, MONTH_NAMES, "month");
        cron.parseDayOfWeek(fields[offset + 5]);

        if (cron.months.isEmpty() || cron.hours.isEmpty() || cron.minutes.isEmpty()) {
            throw new BackupException.Configuration("Cron expression matches nothing: " + expression);
        }
        return cron;
    }

    /** Validates without keeping the result. */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void parseDayOfMonth(String field) {
        String value = field.trim();
        dayOfMonthUnrestricted = "*".equals(value) || "?".equals(value);
        if (value.equalsIgnoreCase("L")) {
            lastDayOfMonth = true;
            return;
        }
        if (value.toUpperCase(Locale.ROOT).endsWith("L") && value.length() > 1) {
            throw new BackupException.Configuration("Unsupported day-of-month syntax: " + field);
        }
        parseField(value, daysOfMonth, 1, 31, null, "day-of-month");
    }

    private void parseDayOfWeek(String field) {
        String value = field.trim();
        dayOfWeekUnrestricted = "*".equals(value) || "?".equals(value);
        // Cron allows 7 as a second spelling of Sunday; fold it onto 0 before parsing.
        BitSet raw = new BitSet(8);
        parseField(value, raw, 0, 7, DAY_NAMES, "day-of-week");
        for (int i = raw.nextSetBit(0); i >= 0; i = raw.nextSetBit(i + 1)) {
            daysOfWeek.set(i % 7);
        }
    }

    private void parseField(String field, BitSet target, int min, int max, String[] names, String label) {
        String value = field.trim();
        if (value.isEmpty()) {
            throw new BackupException.Configuration("Empty " + label + " field in: " + expression);
        }
        for (String part : value.split(",")) {
            parseRange(part.trim(), target, min, max, names, label);
        }
    }

    private void parseRange(String part, BitSet target, int min, int max, String[] names, String label) {
        int step = 1;
        String range = part;

        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            String stepText = part.substring(slash + 1);
            step = parseInt(stepText, label, part);
            if (step < 1) {
                throw new BackupException.Configuration(
                        "Step must be positive in " + label + " field: " + part);
            }
        }

        int from;
        int to;
        if ("*".equals(range) || "?".equals(range)) {
            from = min;
            to = max;
        } else {
            int dash = range.indexOf('-');
            // Guard index 0 so a leading '-' is reported as a bad value, not a range.
            if (dash > 0) {
                from = parseValue(range.substring(0, dash), min, max, names, label);
                to = parseValue(range.substring(dash + 1), min, max, names, label);
            } else {
                from = parseValue(range, min, max, names, label);
                // A bare value with a step means "from here to the end", as in "5/15".
                to = slash >= 0 ? max : from;
            }
        }

        if (from > to) {
            // Wrapping range such as FRI-MON or 22-2.
            for (int i = from; i <= max; i += step) {
                target.set(i);
            }
            for (int i = min; i <= to; i += step) {
                target.set(i);
            }
        } else {
            for (int i = from; i <= to; i += step) {
                target.set(i);
            }
        }
    }

    private int parseValue(String text, int min, int max, String[] names, String label) {
        String value = text.trim();
        if (value.isEmpty()) {
            throw new BackupException.Configuration("Empty value in " + label + " field: " + expression);
        }
        int result = -1;
        if (names != null && !Character.isDigit(value.charAt(0))) {
            String upper = value.toUpperCase(Locale.ROOT);
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(upper)) {
                    result = names == MONTH_NAMES ? i + 1 : i;
                    break;
                }
            }
            if (result < 0) {
                throw new BackupException.Configuration(
                        "Unknown name '" + value + "' in " + label + " field: " + expression);
            }
        } else {
            result = parseInt(value, label, expression);
        }
        if (result < min || result > max) {
            throw new BackupException.Configuration(
                    label + " value " + result + " out of range [" + min + "," + max + "]: " + expression);
        }
        return result;
    }

    private int parseInt(String text, String label, String context) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new BackupException.Configuration(
                    "Invalid number '" + text + "' in " + label + " field: " + context, e);
        }
    }

    /**
     * First matching instant strictly after {@code after}, or {@code null} when the
     * expression cannot match within a few years (for example {@code 0 0 0 30 2 *}).
     */
    public ZonedDateTime next(ZonedDateTime after) {
        ZonedDateTime t = after.plusSeconds(1).withNano(0);

        for (int i = 0; i < MAX_SEARCH_ITERATIONS; i++) {
            if (!months.get(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).with(LocalTime.MIDNIGHT);
                continue;
            }
            if (!dayMatches(t)) {
                t = t.plusDays(1).with(LocalTime.MIDNIGHT);
                continue;
            }
            if (!hours.get(t.getHour())) {
                t = t.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!seconds.get(t.getSecond())) {
                t = t.plusSeconds(1);
                continue;
            }
            return t;
        }
        return null;
    }

    private boolean dayMatches(ZonedDateTime t) {
        if (lastDayOfMonth) {
            boolean isLast = t.getDayOfMonth() == t.toLocalDate().lengthOfMonth();
            if (dayOfWeekUnrestricted) {
                return isLast;
            }
            return isLast || daysOfWeek.get(t.getDayOfWeek().getValue() % 7);
        }

        boolean domMatch = daysOfMonth.get(t.getDayOfMonth());
        boolean dowMatch = daysOfWeek.get(t.getDayOfWeek().getValue() % 7);

        if (dayOfMonthUnrestricted && dayOfWeekUnrestricted) {
            return true;
        }
        if (dayOfMonthUnrestricted) {
            return dowMatch;
        }
        if (dayOfWeekUnrestricted) {
            return domMatch;
        }
        return domMatch || dowMatch;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }
}
