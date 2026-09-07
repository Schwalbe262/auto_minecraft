package dev.schwalbe.autovalley.ui;

/** Converts user-facing wine AGE / future reservations without changing a barrel's production cohort. */
public final class WineCohortRules {
    public static final int MAX_FUTURE_OFFSET = 1000;

    private WineCohortRules() {}

    /** A future reservation is never displayed as a negative AGE. */
    public record Display(int years, boolean future) {}

    public static int parse(String text, Integer currentYear) {
        requireClock(currentYear);
        String value = text == null ? "" : text.trim();
        boolean future = value.startsWith("+");
        String digits = future ? value.substring(1) : value;
        if (!digits.matches("[0-9]+")) throw invalid("classifier");
        int years;
        try { years = Integer.parseInt(digits); }
        catch (NumberFormatException e) { throw invalid(future ? "wine_reserve" : "classifier"); }
        if (future) {
            if (years < 1 || years > MAX_FUTURE_OFFSET || (long) currentYear + years > Integer.MAX_VALUE)
                throw invalid("wine_reserve");
            return currentYear + years;
        }
        if (years > currentYear) throw invalid("wine_age");
        return currentYear - years;
    }

    /** A form captures its native calendar year; crossing a year boundary requires reselecting the barrel. */
    public static int parseChecked(String text, Integer formYear, Integer currentYear) {
        requireClock(formYear);
        requireClock(currentYear);
        if (!formYear.equals(currentYear)) throw invalid("wine_clock_changed");
        return parse(text, formYear);
    }

    public static Display describe(Integer cohort, Integer currentYear) {
        if (cohort == null || cohort < 0 || currentYear == null || currentYear < 0) return null;
        return cohort > currentYear ? new Display(cohort - currentYear, true) : new Display(currentYear - cohort, false);
    }

    /** Suitable for editing an existing registered barrel; unavailable clock data never becomes AGE 0. */
    public static String editValue(Integer cohort, Integer currentYear) {
        Display display = describe(cohort, currentYear);
        return display == null ? null : (display.future() ? "+" : "") + display.years();
    }

    private static void requireClock(Integer year) {
        if (year == null || year < 0) throw invalid("wine_clock");
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("autovalley.error." + key);
    }
}
