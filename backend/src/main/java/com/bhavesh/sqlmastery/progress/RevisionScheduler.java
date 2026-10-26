package com.bhavesh.sqlmastery.progress;

import org.springframework.stereotype.Component;

/**
 * Decides when an item should next be reviewed.
 *
 * <p>This is the SM-2 algorithm, which is what Anki and most spaced-repetition
 * tools are built on. It is small enough to state completely:
 *
 * <ol>
 *   <li>The learner grades the review from 0 to 5.</li>
 *   <li>A grade below 3 is a <b>lapse</b>: the interval resets to one day and
 *       the review count starts again. Getting something wrong means seeing it
 *       tomorrow, whatever its history.</li>
 *   <li>Otherwise the interval grows — 1 day, then 6, then the previous
 *       interval multiplied by the item's <b>ease factor</b>.</li>
 *   <li>The ease factor itself moves with the grade, so an item consistently
 *       graded 5 accelerates away and one scraping 3 barely grows. It is
 *       floored at 1.3, because an item whose ease collapses further is one
 *       the schedule cannot help with and the learner should reread.</li>
 * </ol>
 *
 * <p>The per-item ease is the part that makes this adaptive rather than a
 * fixed ladder, and it is the part most simplified implementations drop.
 *
 * <p>Deliberately pure and stateless: given the current state and a grade it
 * returns the next state, touching nothing. That makes it the one piece of
 * this feature that can be tested exhaustively without a database, which is
 * why the scheduling logic lives here rather than inside the repository or a
 * SQL expression.
 */
@Component
public class RevisionScheduler {

    /** Below this, the review counts as a failure and the interval resets. */
    static final int PASS_THRESHOLD = 3;

    /** SM-2's floor. An item at 1.3 grows by 30% per successful review. */
    static final double MINIMUM_EASE = 1.3;

    /** SM-2's starting ease for a new item. */
    static final double DEFAULT_EASE = 2.5;

    /** The second interval is fixed at six days in SM-2, not derived from the ease. */
    static final int SECOND_INTERVAL_DAYS = 6;

    /**
     * A cap, which SM-2 does not have.
     *
     * <p>Without one, an item graded 5 a dozen times is scheduled years out
     * and effectively leaves the course. For interview preparation — where the
     * horizon is weeks, not a lifetime of vocabulary — six months is already
     * further than anyone will look.
     */
    static final int MAXIMUM_INTERVAL_DAYS = 180;

    /**
     * @param quality the learner's self-grade, 0 (blank) to 5 (perfect)
     * @param current the item's existing state, or null if never reviewed
     * @return the state to store
     * @throws IllegalArgumentException if quality is outside 0-5
     */
    public ProgressRepository.RevisionState next(int quality, ProgressRepository.RevisionState current) {
        if (quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality must be between 0 and 5, was " + quality);
        }

        int reviews = current == null ? 0 : current.reviews();
        int lapses = current == null ? 0 : current.lapses();
        double ease = current == null ? DEFAULT_EASE : current.ease();
        int interval = current == null ? 1 : current.intervalDays();

        // The ease adjustment is SM-2's, unchanged. It is worth reading as a
        // curve rather than a formula: grade 5 adds 0.10, grade 4 leaves it
        // alone, grade 3 subtracts 0.14, and the penalty accelerates below
        // that — which is why a run of barely-passed reviews slows an item
        // down rather than holding it steady.
        double adjusted = ease + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        // Rounded to two places to match the numeric(4,2) column. Without
        // this, the value returned to the caller carries binary
        // floating-point noise — 1.6099999999999999 — while the stored value
        // is 1.61, and the two disagree on the next read. Rounding at the
        // point of computation keeps one number in play.
        double nextEase = round2(Math.max(MINIMUM_EASE, adjusted));

        if (quality < PASS_THRESHOLD) {
            // A lapse. The interval resets but the ease keeps its reduction,
            // so an item that keeps failing comes back tomorrow *and* grows
            // more slowly once it starts passing again.
            return new ProgressRepository.RevisionState(1, nextEase, 0, lapses + 1);
        }

        int nextInterval = switch (reviews) {
            case 0 -> 1;
            case 1 -> SECOND_INTERVAL_DAYS;
            default -> (int) Math.round(interval * nextEase);
        };
        nextInterval = Math.clamp(nextInterval, 1, MAXIMUM_INTERVAL_DAYS);

        return new ProgressRepository.RevisionState(nextInterval, nextEase, reviews + 1, lapses);
    }

    /**
     * The state for an item being scheduled for the first time without a
     * review — used when a lesson is marked complete, so it enters the
     * revision queue tomorrow rather than only after someone remembers to
     * grade it.
     */
    public ProgressRepository.RevisionState initial() {
        return new ProgressRepository.RevisionState(1, DEFAULT_EASE, 0, 0);
    }

    /** Two decimal places, matching the {@code numeric(4,2)} column. */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
