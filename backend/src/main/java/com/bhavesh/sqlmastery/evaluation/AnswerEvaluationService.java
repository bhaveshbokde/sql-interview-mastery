package com.bhavesh.sqlmastery.evaluation;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.bhavesh.sqlmastery.curriculum.CurriculumRepository;
import com.bhavesh.sqlmastery.error.ResourceNotFoundException;
import com.bhavesh.sqlmastery.playground.QueryExecutionService;
import com.bhavesh.sqlmastery.playground.SqlErrorMapper;
import com.bhavesh.sqlmastery.playground.SqlRejectedException;
import com.bhavesh.sqlmastery.playground.dto.QueryError;
import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import org.springframework.stereotype.Service;

/**
 * Decides whether a submitted answer solves a problem.
 *
 * <p>The submission is executed exactly like any other playground query —
 * same validator, same least-privileged role, same read-only transaction, same
 * timeout. Evaluation is not a privileged path, and there is nothing a learner
 * can submit here that they could not type into the editor.
 *
 * <p>Correctness is decided by running the submission and the reference
 * solution over <b>two datasets</b> and comparing the results:
 *
 * <ul>
 *   <li>{@code learning} — the data the problem was written against;</li>
 *   <li>{@code learning_alt} — the same tables with rows chosen to break the
 *       usual wrong answers: salary ties, a NULL manager, a department with
 *       one employee, an empty {@code order_items}, duplicate keys.</li>
 * </ul>
 *
 * <p>The second dataset is what separates "returns the right answer" from
 * "returns the right answer here". A submission that hardcodes a value it read
 * off the first result, or that uses {@code LIMIT 1 OFFSET 1} where the
 * question needs {@code DENSE_RANK}, passes on {@code learning} and fails on
 * {@code learning_alt} — and the report says which one failed, because the
 * distinction is the lesson.
 *
 * <p>What this cannot do is stated in {@link ResultComparator} and repeated in
 * the response: agreement on two datasets is evidence, not proof. The service
 * never claims more than it has.
 */
@Service
public class AnswerEvaluationService {

    private static final String PRIMARY = "learning";
    private static final String ALTERNATE = "learning_alt";

    private final QueryExecutionService executionService;
    private final CurriculumRepository repository;
    private final ResultComparator comparator;
    private final SqlErrorMapper errorMapper;

    public AnswerEvaluationService(
            QueryExecutionService executionService,
            CurriculumRepository repository,
            ResultComparator comparator,
            SqlErrorMapper errorMapper) {
        this.executionService = executionService;
        this.repository = repository;
        this.comparator = comparator;
        this.errorMapper = errorMapper;
    }

    public Evaluation evaluate(String problemIdOrSlug, String submittedSql) {
        CurriculumRepository.ProblemMeta meta = repository.findProblemMeta(problemIdOrSlug)
                .orElseThrow(() -> new ResourceNotFoundException("problem", problemIdOrSlug));
        String referenceSql = repository.findSolutionSql(problemIdOrSlug)
                .orElseThrow(() -> new ResourceNotFoundException("problem solution", problemIdOrSlug));

        List<DatasetOutcome> outcomes = new ArrayList<>(2);
        QueryResult primaryResult = null;

        for (String schema : List.of(PRIMARY, ALTERNATE)) {
            QueryResult submitted;
            try {
                submitted = executionService.execute(submittedSql, schema);
            } catch (SqlRejectedException e) {
                return Evaluation.refused(meta, new QueryError(
                        e.reason().name(), e.getMessage(), null, null, null));
            } catch (SQLException e) {
                // A submission that does not run is not "wrong", it is broken,
                // and saying so with the database's own message and position is
                // more use than a row comparison that could never happen.
                return Evaluation.failedToRun(meta, errorMapper.toQueryError(e), schema);
            }
            if (schema.equals(PRIMARY)) {
                primaryResult = submitted;
            }

            QueryResult reference;
            try {
                reference = executionService.execute(referenceSql, schema);
            } catch (SQLException | SqlRejectedException e) {
                // The reference answer failing is a defect in the curriculum,
                // not in the submission, and must not be reported as the
                // learner being wrong.
                throw new IllegalStateException(
                        "The reference solution for problem " + meta.id()
                        + " failed on schema " + schema + ": " + e.getMessage(), e);
            }

            ResultComparator.Comparison comparison =
                    comparator.compare(submitted, reference, meta.expectedColumns(), meta.orderSensitive());
            outcomes.add(new DatasetOutcome(schema, comparison.matches(), comparison));
        }

        boolean correct = outcomes.stream().allMatch(DatasetOutcome::matches);
        return new Evaluation(
                meta.id(), meta.title(), correct, verdictFor(outcomes),
                outcomes, primaryResult, null, LIMITS);
    }

    private static String verdictFor(List<DatasetOutcome> outcomes) {
        boolean primary = outcomes.get(0).matches();
        boolean alternate = outcomes.size() > 1 && outcomes.get(1).matches();
        if (primary && alternate) {
            return "CORRECT";
        }
        if (primary) {
            // The interesting case, and the reason the second dataset exists.
            return "CORRECT_ON_SAMPLE_ONLY";
        }
        return "INCORRECT";
    }

    private static final String LIMITS = """
            This check runs your query and the reference solution over two datasets and \
            compares the rows that come back. Agreeing on both is good evidence and not a \
            proof: no tool can decide whether two arbitrary SQL queries are equivalent. \
            Row order is ignored unless the question asks for an order, and numbers are \
            compared by value, so 150 and 150.00 match.""";

    /** How the submission did against one dataset. */
    public record DatasetOutcome(String schema, boolean matches, ResultComparator.Comparison comparison) {
    }

    /**
     * The verdict.
     *
     * @param verdict CORRECT, CORRECT_ON_SAMPLE_ONLY, INCORRECT, DID_NOT_RUN
     *                or REFUSED
     * @param result  what the submission returned on the primary dataset, so
     *                the learner can see their own output next to the verdict
     * @param limits  what this check does and does not establish, returned on
     *                every response rather than buried in documentation
     */
    public record Evaluation(
            String problemId,
            String problemTitle,
            boolean correct,
            String verdict,
            List<DatasetOutcome> datasets,
            QueryResult result,
            QueryError error,
            String limits) {

        static Evaluation refused(CurriculumRepository.ProblemMeta meta, QueryError error) {
            return new Evaluation(meta.id(), meta.title(), false, "REFUSED",
                    List.of(), null, error, LIMITS);
        }

        static Evaluation failedToRun(CurriculumRepository.ProblemMeta meta, QueryError error, String schema) {
            QueryError annotated = new QueryError(
                    error.code(),
                    error.message() + (schema.equals(ALTERNATE)
                            ? " (while running against the alternate dataset)" : ""),
                    error.explanation(), error.position(), error.hint());
            return new Evaluation(meta.id(), meta.title(), false, "DID_NOT_RUN",
                    List.of(), null, annotated, LIMITS);
        }
    }
}
