package com.bhavesh.sqlmastery.curriculum.model;

import java.util.List;

import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One interview problem, as authored in
 * {@code resources/curriculum/problems/*.yaml}.
 *
 * <p>Like {@link Lesson}, the components are the required parts of a complete
 * problem, and the loader refuses anything with a required part missing.
 *
 * <p>Hints and the solution are modelled here together because they are one
 * document to the person writing it, but they are stored in separate tables
 * and served by separate endpoints, so that asking for a problem cannot
 * accidentally return its answer.
 *
 * @param source          where the problem comes from, when it genuinely has a
 *                        single public source. Null means "a standard pattern
 *                        with no canonical origin" — the loader will not accept
 *                        an invented URL, and an unattributed problem is better
 *                        than a fabricated attribution
 * @param orderSensitive  whether row order is part of the answer. Evaluation
 *                        compares as multisets unless this is true, so a
 *                        correct query is not failed for ordering rows
 *                        differently when the question never asked for an order
 * @param tables          the dataset tables involved, so the UI can show their
 *                        schema and live contents beside the question
 * @param expectedColumns the column names the answer must produce, in order.
 *                        Checked before the rows are, because "your column is
 *                        called salary, the question asked for SecondHighest"
 *                        is a better message than a row mismatch
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Problem(
        String id,
        int position,
        String title,
        String slug,
        String difficulty,
        String topic,
        List<String> tags,
        Source source,
        boolean orderSensitive,
        String statement,
        List<String> tables,
        List<String> expectedColumns,
        List<String> edgeCases,
        List<String> hints,
        String solutionSql,
        QueryResult expected,
        Explanation explanation) {

    /** A real, checkable origin for a problem. */
    public record Source(String name, String url) {
    }

    /**
     * Everything shown after the answer is revealed.
     *
     * @param lineByLine     each clause of the solution with what it does
     * @param walkthrough    the solution traced against the actual sample rows
     * @param alternatives   other correct solutions, each executed and checked
     *                       to produce the same result as the reference one
     * @param performance    what this costs and how it scales, discussed in the
     *                       terms SQL actually has — indexes, sorts, scan
     *                       counts — rather than borrowed big-O notation
     * @param wrongApproaches answers that look right and are not, each with the
     *                       case that breaks it
     * @param extraTests     additional checks, including running the same query
     *                       against the learning_alt dataset
     * @param followUps      the questions an interviewer asks next
     * @param related        lesson and problem ids worth reading alongside
     */
    public record Explanation(
            List<Line> lineByLine,
            String walkthrough,
            List<Alternative> alternatives,
            String performance,
            List<WrongApproach> wrongApproaches,
            List<ExtraTest> extraTests,
            List<Lesson.QuestionAnswer> followUps,
            List<String> related) {
    }

    /**
     * One clause of the solution and what it contributes.
     *
     * <p>The component is called {@code clause} rather than {@code sql}
     * deliberately. {@link com.bhavesh.sqlmastery.curriculum.CurriculumSql}
     * treats every {@code sql} field it finds as an executable statement to be
     * run and recorded, and a line-by-line reading is made of fragments —
     * {@code where rnk = 2} is not a statement. Naming it differently is what
     * keeps the "every sql field is executed" rule simple enough to be true.
     */
    public record Line(String clause, String note) {
    }

    /**
     * Another way to solve it.
     *
     * @param equivalent whether this returns exactly the same result as the
     *                   reference solution. Recorded by executing both, not
     *                   asserted by the author. False is a legitimate value: an
     *                   alternative that differs on ties is worth showing
     *                   precisely because it differs
     */
    public record Alternative(String title, String sql, String when, boolean equivalent, QueryResult result) {
    }

    /** An answer that fails, and the input that exposes it. */
    public record WrongApproach(String title, String sql, String whyWrong) {
    }

    /**
     * An extra check run against the solution.
     *
     * <p>The {@code sql} is executed and recorded like every other statement in
     * the curriculum, which is what makes this a check rather than a claim. An
     * extra test that asserted "the solution also works on learning_alt"
     * without running it there would be exactly the kind of unverified
     * statement the recording step exists to prevent.
     *
     * @param schema which dataset to run against; {@code learning_alt} holds
     *               ties, NULLs and empty tables chosen to break naive answers
     */
    public record ExtraTest(String name, String schema, String note, String sql, QueryResult result) {
    }
}
