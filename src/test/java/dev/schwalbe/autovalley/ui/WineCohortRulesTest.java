package dev.schwalbe.autovalley.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WineCohortRulesTest {
    @Test void ageZeroAndPastAgesResolveToFixedCohorts() {
        assertEquals(17, WineCohortRules.parse("0", 17));
        assertEquals(15, WineCohortRules.parse("2", 17));
        assertEquals(0, WineCohortRules.parse("17", 17));
        assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse("18", 17));
    }

    @Test void eightExistingBarrelsKeepTheirCohortsWhenTheYearRollsOver() {
        int[] cohorts = {10, 11, 12, 13, 14, 15, 16, 17};
        for (int cohort : cohorts) {
            assertEquals(new WineCohortRules.Display(17 - cohort, false), WineCohortRules.describe(cohort, 17));
            assertEquals(new WineCohortRules.Display(18 - cohort, false), WineCohortRules.describe(cohort, 18));
            assertEquals(cohort, WineCohortRules.parse(WineCohortRules.editValue(cohort, 18), 18));
        }
        assertEquals("0", WineCohortRules.editValue(17, 17));
        assertEquals("1", WineCohortRules.editValue(17, 18));
    }

    @Test void reservedNextYearBecomesAgeZeroAndTheFollowingYearCanBeReserved() {
        int reserved = WineCohortRules.parse("+1", 17);
        assertEquals(18, reserved);
        assertEquals(new WineCohortRules.Display(1, true), WineCohortRules.describe(reserved, 17));
        assertEquals("+1", WineCohortRules.editValue(reserved, 17));
        assertEquals(new WineCohortRules.Display(0, false), WineCohortRules.describe(reserved, 18));
        assertEquals("0", WineCohortRules.editValue(reserved, 18));
        assertEquals(19, WineCohortRules.parse("+1", 18));
    }

    @Test void presentAndPastCohortsRoundTripThroughTheEditor() {
        for (int currentYear : new int[]{0, 1, 17, 1001, Integer.MAX_VALUE}) {
            for (int cohort : new int[]{0, currentYear / 2, currentYear}) {
                String value = WineCohortRules.editValue(cohort, currentYear);
                assertNotNull(value);
                assertEquals(cohort, WineCohortRules.parse(value, currentYear));
                assertEquals(new WineCohortRules.Display(currentYear - cohort, false),
                        WineCohortRules.describe(cohort, currentYear));
            }
        }
    }

    @Test void futureCohortsWithinReservationLimitRoundTripThroughTheEditor() {
        for (int offset : new int[]{1, 2, 17, 999, 1000}) {
            int cohort = 17 + offset;
            assertEquals("+" + offset, WineCohortRules.editValue(cohort, 17));
            assertEquals(cohort, WineCohortRules.parse(WineCohortRules.editValue(cohort, 17), 17));
            assertEquals(new WineCohortRules.Display(offset, true), WineCohortRules.describe(cohort, 17));
        }
    }

    @Test void futureOffsetsIncludeOneAndOneThousandButExcludeZeroAndOneThousandOne() {
        assertEquals(18, WineCohortRules.parse("+1", 17));
        assertEquals(1017, WineCohortRules.parse("+1000", 17));
        assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse("+0", 17));
        assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse("+1001", 17));
    }

    @Test void malformedSignsNegativeNumbersAndDecimalsAreRejected() {
        for (String text : new String[]{"", "+", "++1", "+-1", "-1", "-0", "1+", "1.0", "+1.0", "1e2", "+ 1", "1 0"}) {
            assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse(text, 17), text);
        }
        assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse(null, 17));
    }

    @Test void onlyAsciiDigitsAreAccepted() {
        for (String text : new String[]{"\u0661", "\uff11", "+\u0661", "+\uff11", "\u22121"}) {
            assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse(text, 17), text);
        }
    }

    @Test void integerTextOverflowIsRejected() {
        for (String text : new String[]{"2147483648", "999999999999999999999", "+2147483648", "+999999999999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse(text, Integer.MAX_VALUE), text);
        }
    }

    @Test void futureAdditionMustNotOverflowTheCohortInteger() {
        assertEquals(Integer.MAX_VALUE, WineCohortRules.parse("+1", Integer.MAX_VALUE - 1));
        assertEquals(Integer.MAX_VALUE, WineCohortRules.parse("+1000", Integer.MAX_VALUE - 1000));
        assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse("+1", Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> WineCohortRules.parse("+1000", Integer.MAX_VALUE - 999));
    }

    @Test void rawCohortsAndPresentAgesAreNotArbitrarilyCapped() {
        assertEquals(Integer.MAX_VALUE, WineCohortRules.parse("0", Integer.MAX_VALUE));
        assertEquals(0, WineCohortRules.parse("2147483647", Integer.MAX_VALUE));
        assertEquals(new WineCohortRules.Display(0, false), WineCohortRules.describe(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(new WineCohortRules.Display(Integer.MAX_VALUE, false), WineCohortRules.describe(0, Integer.MAX_VALUE));
        assertEquals("2147483647", WineCohortRules.editValue(0, Integer.MAX_VALUE));
    }

    @Test void distantExistingFutureCohortsRemainVisibleWithoutApplyingTheNewReservationCap() {
        assertEquals(new WineCohortRules.Display(Integer.MAX_VALUE, true), WineCohortRules.describe(Integer.MAX_VALUE, 0));
        assertEquals("+2147483647", WineCohortRules.editValue(Integer.MAX_VALUE, 0));
        assertEquals(new WineCohortRules.Display(1001, true), WineCohortRules.describe(1018, 17));
    }

    @Test void parseRequiresAnAvailableNonnegativeClock() {
        for (Integer currentYear : new Integer[]{null, -1, Integer.MIN_VALUE}) {
            assertEquals("autovalley.error.wine_clock", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parse("0", currentYear)).getMessage());
            assertEquals("autovalley.error.wine_clock", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parse("+1", currentYear)).getMessage());
        }
    }

    @Test void invalidCohortsAndClocksHaveNoDisplayOrEditorValue() {
        for (Integer cohort : new Integer[]{null, -1, Integer.MIN_VALUE}) {
            assertNull(WineCohortRules.describe(cohort, 17));
            assertNull(WineCohortRules.editValue(cohort, 17));
        }
        for (Integer currentYear : new Integer[]{null, -1, Integer.MIN_VALUE}) {
            assertNull(WineCohortRules.describe(17, currentYear));
            assertNull(WineCohortRules.editValue(17, currentYear));
        }
    }

    @Test void checkedParseAcceptsAnUnchangedClockForAgeAndReservation() {
        assertEquals(15, WineCohortRules.parseChecked("2", 17, 17));
        assertEquals(18, WineCohortRules.parseChecked("+1", 17, 17));
        assertEquals(Integer.MAX_VALUE, WineCohortRules.parseChecked("0", Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void checkedParseRejectsUnavailableClockEvenWhenBothSnapshotsMatch() {
        for (Integer invalidYear : new Integer[]{null, -1, Integer.MIN_VALUE}) {
            assertEquals("autovalley.error.wine_clock", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parseChecked("0", invalidYear, 17)).getMessage());
            assertEquals("autovalley.error.wine_clock", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parseChecked("0", 17, invalidYear)).getMessage());
            assertEquals("autovalley.error.wine_clock", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parseChecked("0", invalidYear, invalidYear)).getMessage());
        }
    }

    @Test void checkedParseRejectsForwardAndBackwardClockChangesBeforeReinterpretingInput() {
        for (String text : new String[]{"0", "+1"}) {
            assertEquals("autovalley.error.wine_clock_changed", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parseChecked(text, 17, 18)).getMessage());
            assertEquals("autovalley.error.wine_clock_changed", assertThrows(IllegalArgumentException.class,
                    () -> WineCohortRules.parseChecked(text, 18, 17)).getMessage());
        }
    }
}
