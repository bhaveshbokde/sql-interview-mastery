package com.bhavesh.sqlmastery.curriculum;

import java.util.List;
import java.util.Map;

import com.bhavesh.sqlmastery.error.ResourceNotFoundException;
import com.bhavesh.sqlmastery.evaluation.AnswerEvaluationService;
import com.bhavesh.sqlmastery.progress.LearnerId;
import com.bhavesh.sqlmastery.progress.ProgressService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The interview problem bank.
 *
 * <p>Hints and solutions have their own endpoints, and the problem endpoint
 * reads a table that does not contain them. That is the point: "do not include
 * the answer in the response" is a rule somebody has to keep remembering,
 * whereas "the answer is in a different table that this query does not join to"
 * stays true on its own.
 *
 * <p>Hints are served one at a time, so asking for the first hint does not
 * hand over the third.
 */
@RestController
@RequestMapping("/api/problems")
@Tag(name = "Problems", description = "Interview problems, hints, solutions and answer checking.")
public class ProblemController {

    private final CurriculumRepository repository;
    private final AnswerEvaluationService evaluationService;
    private final ProgressService progressService;

    public ProblemController(
            CurriculumRepository repository,
            AnswerEvaluationService evaluationService,
            ProgressService progressService) {
        this.repository = repository;
        this.evaluationService = evaluationService;
        this.progressService = progressService;
    }

    @GetMapping
    @Operation(summary = "Problems, filtered by topic, difficulty, tag or free text")
    public JsonNode list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q) {
        return repository.findProblems(topic, difficulty, tag, q);
    }

    @GetMapping("/{idOrSlug}")
    @Operation(summary = "One problem: statement, tables, required columns, edge cases",
               description = "Deliberately excludes the hints and the solution.")
    public JsonNode problem(@PathVariable String idOrSlug) {
        return repository.findProblem(idOrSlug)
                .orElseThrow(() -> new ResourceNotFoundException("problem", idOrSlug));
    }

    @GetMapping("/{idOrSlug}/hints/{ordinal}")
    @Operation(summary = "One hint",
               description = """
                       Hints are numbered from 1 and get progressively more specific. Asking
                       for hint 1 returns only hint 1; the later ones stay behind their own
                       request, so a glance at the network tab is not a way to skip ahead.""")
    public Map<String, Object> hint(@PathVariable String idOrSlug, @PathVariable int ordinal) {
        List<String> hints = repository.findHints(idOrSlug);
        if (hints.isEmpty()) {
            throw new ResourceNotFoundException("problem", idOrSlug);
        }
        if (ordinal < 1 || ordinal > hints.size()) {
            throw new ResourceNotFoundException("hint " + ordinal + " for problem", idOrSlug);
        }
        return Map.of(
                "ordinal", ordinal,
                "total", hints.size(),
                "hint", hints.get(ordinal - 1));
    }

    @GetMapping("/{idOrSlug}/hints/count")
    @Operation(summary = "How many hints a problem has, without revealing any")
    public Map<String, Integer> hintCount(@PathVariable String idOrSlug) {
        List<String> hints = repository.findHints(idOrSlug);
        if (hints.isEmpty()) {
            throw new ResourceNotFoundException("problem", idOrSlug);
        }
        return Map.of("total", hints.size());
    }

    @GetMapping("/{idOrSlug}/solution")
    @Operation(summary = "The reference solution and its full explanation",
               description = """
                       Includes the line-by-line reading, a walkthrough over the real sample
                       rows, alternative solutions with whether each returns an identical
                       result, the approaches that look right and are not, the extra checks
                       that were run, and the follow-up questions an interviewer asks next.""")
    public JsonNode solution(@PathVariable String idOrSlug) {
        return repository.findSolution(idOrSlug)
                .orElseThrow(() -> new ResourceNotFoundException("problem solution", idOrSlug));
    }

    @PostMapping("/{idOrSlug}/evaluate")
    @Operation(summary = "Check an answer",
               description = """
                       Runs the submission and the reference solution over two datasets and
                       compares the results. The second dataset contains the ties, NULLs and
                       empty tables that separate a correct answer from one that happens to
                       work on the sample data, so a verdict of CORRECT_ON_SAMPLE_ONLY is a
                       real and useful outcome.

                       The response always carries a statement of what the check does and does
                       not establish. Deciding whether two arbitrary SQL queries are equivalent
                       is not something any tool can do, and this one does not claim to.""")
    public AnswerEvaluationService.Evaluation evaluate(
            @PathVariable String idOrSlug,
            @Valid @RequestBody SubmissionRequest request,
            HttpServletRequest http) {
        AnswerEvaluationService.Evaluation evaluation = evaluationService.evaluate(idOrSlug, request.sql());
        // Recorded here rather than by the client, because this is the only
        // place that knows the real verdict. A progress history assembled from
        // what a browser chose to report is a history of claims.
        progressService.recordProblemAttempt(
                LearnerId.from(http),
                evaluation.problemId(),
                request.sql(),
                evaluation.verdict(),
                evaluation.correct(),
                request.hintsUsed() == null ? 0 : request.hintsUsed());
        return evaluation;
    }

    /**
     * A candidate answer.
     *
     * @param hintsUsed how many hints the learner had revealed, which the
     *                  server cannot know — hints are fetched one at a time
     *                  and nothing links those requests to this one. It is
     *                  self-reported and used only to adjust how soon the
     *                  problem comes back for revision, so an inaccurate value
     *                  costs the learner a slightly wrong schedule and nothing
     *                  else.
     */
    public record SubmissionRequest(
            @NotBlank @Size(max = 10_000) String sql,
            @Min(0) @Max(20) Integer hintsUsed) {
    }
}
