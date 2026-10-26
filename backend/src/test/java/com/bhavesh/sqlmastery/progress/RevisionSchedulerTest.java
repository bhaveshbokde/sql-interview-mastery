package com.bhavesh.sqlmastery.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The scheduler is pure, so it can be tested exhaustively without a database —
 * which is the reason the scheduling logic was put in its own class rather
 * than written as a SQL expression in the upsert.
 */
class RevisionSchedulerTest {

    private final RevisionScheduler scheduler = new RevisionScheduler();

    private static ProgressRepository.RevisionState state(
            int intervalDays, double ease, int reviews, int lapses) {
        return new ProgressRepository.RevisionState(intervalDays, ease, reviews, lapses);
    }

    @Nested
    @DisplayName("a brand new item")
    class NewItem {

        @Test
        @DisplayName("first successful review is due in one day")
        void firstReviewIsOneDay() {
            var next = scheduler.next(4, null);
            assertThat(next.intervalDays()).isEqualTo(1);
            assertThat(next.reviews()).isEqualTo(1);
            assertThat(next.lapses()).isZero();
        }

        @Test
        @DisplayName("grade 4 leaves the ease exactly where it started")
        void gradeFourIsNeutral() {
            // SM-2's curve is built so that 4 is the "no adjustment" point.
            // If this ever changes, every other expectation here shifts.
            assertThat(scheduler.next(4, null).ease())
                    .isCloseTo(RevisionScheduler.DEFAULT_EASE, within(0.001));
        }

        @Test
        @DisplayName("grade 5 raises the ease, grade 3 lowers it")
        void easeMovesWithQuality() {
            assertThat(scheduler.next(5, null).ease()).isGreaterThan(RevisionScheduler.DEFAULT_EASE);
            assertThat(scheduler.next(3, null).ease()).isLessThan(RevisionScheduler.DEFAULT_EASE);
        }

        @Test
        @DisplayName("initial() produces a state due tomorrow with the default ease")
        void initialState() {
            var initial = scheduler.initial();
            assertThat(initial.intervalDays()).isEqualTo(1);
            assertThat(initial.ease()).isEqualTo(RevisionScheduler.DEFAULT_EASE);
            assertThat(initial.reviews()).isZero();
            assertThat(initial.lapses()).isZero();
        }
    }

    @Nested
    @DisplayName("the interval ladder")
    class Intervals {

        @Test
        @DisplayName("goes 1 day, then 6, then multiplied by the ease")
        void firstThreeIntervals() {
            var first = scheduler.next(4, null);
            assertThat(first.intervalDays()).isEqualTo(1);

            var second = scheduler.next(4, first);
            assertThat(second.intervalDays()).isEqualTo(RevisionScheduler.SECOND_INTERVAL_DAYS);

            var third = scheduler.next(4, second);
            // 6 days multiplied by the unchanged default ease of 2.5.
            assertThat(third.intervalDays()).isEqualTo(15);
        }

        @Test
        @DisplayName("a lower ease produces a shorter next interval")
        void easeControlsGrowth() {
            var easy = scheduler.next(4, state(10, 2.5, 5, 0));
            var hard = scheduler.next(4, state(10, 1.4, 5, 0));
            assertThat(easy.intervalDays()).isGreaterThan(hard.intervalDays());
            assertThat(easy.intervalDays()).isEqualTo(25);
            assertThat(hard.intervalDays()).isEqualTo(14);
        }

        @Test
        @DisplayName("is capped, so a well-known item does not leave the course")
        void intervalIsCapped() {
            var next = scheduler.next(5, state(150, 2.5, 20, 0));
            assertThat(next.intervalDays()).isEqualTo(RevisionScheduler.MAXIMUM_INTERVAL_DAYS);
        }

        @Test
        @DisplayName("never drops below one day")
        void intervalHasAFloor() {
            var next = scheduler.next(3, state(1, RevisionScheduler.MINIMUM_EASE, 2, 0));
            assertThat(next.intervalDays()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a lapse")
    class Lapses {

        @ParameterizedTest(name = "grade {0}")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("resets the interval to one day whatever the item's history")
        void resetsInterval(int quality) {
            var mature = state(90, 2.6, 8, 0);
            var next = scheduler.next(quality, mature);
            assertThat(next.intervalDays()).isEqualTo(1);
            assertThat(next.reviews()).isZero();
        }

        @Test
        @DisplayName("increments the lapse count without losing the earlier ones")
        void countsLapses() {
            var next = scheduler.next(1, state(30, 2.0, 5, 2));
            assertThat(next.lapses()).isEqualTo(3);
        }

        @Test
        @DisplayName("keeps the ease reduction, so the item regrows more slowly")
        void keepsTheEasePenalty() {
            // This is the part a naive "reset on failure" implementation
            // loses: the interval resets, the ease does not, so an item that
            // keeps failing both returns immediately and grows slower
            // afterwards.
            var before = state(30, 2.5, 5, 0);
            var after = scheduler.next(0, before);
            assertThat(after.ease()).isLessThan(before.ease());
        }

        @Test
        @DisplayName("grade 3 is a pass, grade 2 is not — the boundary")
        void passThreshold() {
            assertThat(scheduler.next(RevisionScheduler.PASS_THRESHOLD, state(10, 2.5, 5, 0)).lapses())
                    .isZero();
            assertThat(scheduler.next(RevisionScheduler.PASS_THRESHOLD - 1, state(10, 2.5, 5, 0)).lapses())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the ease factor")
    class Ease {

        @Test
        @DisplayName("never falls below the floor, however badly it goes")
        void respectsTheFloor() {
            var current = state(1, RevisionScheduler.MINIMUM_EASE, 0, 10);
            for (int i = 0; i < 20; i++) {
                current = scheduler.next(0, current);
            }
            assertThat(current.ease()).isGreaterThanOrEqualTo(RevisionScheduler.MINIMUM_EASE);
        }

        @Test
        @DisplayName("is rounded to two places, matching the stored column")
        void isRounded() {
            // Without rounding the returned value carries floating-point
            // noise that disagrees with the numeric(4,2) the database keeps.
            var next = scheduler.next(1, state(10, 2.5, 3, 0));
            assertThat(next.ease()).isEqualTo(RevisionScheduler.round2(next.ease()));
        }

        @Test
        @DisplayName("rises over a run of perfect reviews")
        void risesWithPerfectReviews() {
            var current = scheduler.next(5, null);
            double first = current.ease();
            for (int i = 0; i < 5; i++) {
                current = scheduler.next(5, current);
            }
            assertThat(current.ease()).isGreaterThan(first);
        }
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @ParameterizedTest(name = "quality {0}")
        @ValueSource(ints = {-1, 6, 100, Integer.MIN_VALUE})
        void rejectsQualityOutsideRange(int quality) {
            assertThatThrownBy(() -> scheduler.next(quality, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0 and 5");
        }

        @ParameterizedTest(name = "quality {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        void acceptsEveryValidQuality(int quality) {
            assertThat(scheduler.next(quality, null)).isNotNull();
        }
    }

    @Test
    @DisplayName("a realistic sequence behaves the way a learner would expect")
    void realisticSequence() {
        // Learn it, get it right twice, forget it, relearn it.
        var s = scheduler.next(4, null);
        assertThat(s.intervalDays()).isEqualTo(1);

        s = scheduler.next(4, s);
        assertThat(s.intervalDays()).isEqualTo(6);

        s = scheduler.next(5, s);
        assertThat(s.intervalDays()).isGreaterThan(6);
        int beforeLapse = s.intervalDays();

        s = scheduler.next(1, s);
        assertThat(s.intervalDays()).isEqualTo(1);
        assertThat(s.lapses()).isEqualTo(1);

        s = scheduler.next(4, s);
        s = scheduler.next(4, s);
        s = scheduler.next(4, s);
        // Back on the ladder, but growing more slowly than before the lapse
        // because the ease took the hit and kept it.
        assertThat(s.intervalDays()).isLessThan(beforeLapse * 3);
        assertThat(s.lapses()).isEqualTo(1);
    }
}
