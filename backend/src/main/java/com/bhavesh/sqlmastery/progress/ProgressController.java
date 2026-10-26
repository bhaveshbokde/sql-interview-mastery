package com.bhavesh.sqlmastery.progress;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Study progress and the spaced-revision queue.
 *
 * <p>Every endpoint is scoped to a learner taken from the {@code X-Learner-Id}
 * header, defaulting to {@code local}. See {@link LearnerId} for why that is
 * not a credential.
 *
 * <p>Note what is <b>not</b> here: an endpoint to record a problem attempt.
 * Attempts are written by the evaluation endpoint, which is the only place
 * that knows the real verdict. A client-supplied attempt record would be a
 * record of what the browser decided to say rather than of what the server
 * judged, and a progress history that can be fabricated is not worth keeping.
 */
@RestController
@RequestMapping("/api/progress")
@Tag(name = "Progress", description = "Study progress, attempt history and spaced revision.")
public class ProgressController {

    private final ProgressService service;

    public ProgressController(ProgressService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Dashboard figures",
               description = """
                       Lesson and problem completion, quiz accuracy, how many revisions are
                       due, per-module progress, and fourteen days of activity. The activity
                       series includes the days with nothing on them, because a sparkline with
                       the empty days missing is a lie about the shape of the week.""")
    public JsonNode overview(HttpServletRequest http) {
        return service.overview(LearnerId.from(http));
    }

    // ------------------------------------------------------------ lessons --

    @GetMapping("/lessons")
    @Operation(summary = "Every lesson with its status",
               description = "Includes lessons never opened, with status not_started.")
    public JsonNode lessons(HttpServletRequest http) {
        return service.lessonProgress(LearnerId.from(http));
    }

    @PostMapping("/lessons/{idOrSlug}/view")
    @Operation(summary = "Record that a lesson was opened",
               description = "Idempotent in effect; increments a view count and touches the timestamp.")
    public Map<String, String> viewLesson(@PathVariable String idOrSlug, HttpServletRequest http) {
        service.recordLessonView(LearnerId.from(http), idOrSlug);
        return Map.of("status", "recorded");
    }

    @PostMapping("/lessons/{idOrSlug}/status")
    @Operation(summary = "Mark a lesson complete, or reopen it",
               description = """
                       Completing a lesson also schedules it for revision tomorrow. A low
                       confidence rating lowers the item's starting ease, so it grows more
                       slowly through the schedule than one you were sure of.

                       Reopening deliberately leaves the revision schedule alone — rereading
                       something is not forgetting it.""")
    public Map<String, Object> setLessonStatus(
            @PathVariable String idOrSlug,
            @Valid @RequestBody LessonStatusRequest request,
            HttpServletRequest http) {
        service.setLessonStatus(LearnerId.from(http), idOrSlug,
                request.completed(), request.confidence());
        return Map.of(
                "status", request.completed() ? "completed" : "in_progress",
                "lesson", idOrSlug);
    }

    // ----------------------------------------------------------- problems --

    @GetMapping("/problems")
    @Operation(summary = "Every problem with its attempt summary",
               description = """
                       `solved` is whether the problem has *ever* been solved, not whether the
                       last attempt was right — someone who solved it and then experimented
                       has still solved it.""")
    public JsonNode problems(HttpServletRequest http) {
        return service.problemProgress(LearnerId.from(http));
    }

    @GetMapping("/problems/{idOrSlug}/attempts")
    @Operation(summary = "Previous submissions for one problem, newest first")
    public JsonNode attempts(
            @PathVariable String idOrSlug,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest http) {
        return service.attempts(LearnerId.from(http), idOrSlug, Math.clamp(limit, 1, 100));
    }

    // --------------------------------------------------------------- quiz --

    @GetMapping("/quiz")
    @Operation(summary = "Quiz accuracy per topic, weakest first",
               description = "The \"what should I study next\" query.")
    public JsonNode quiz(HttpServletRequest http) {
        return service.quizAccuracy(LearnerId.from(http));
    }

    // ----------------------------------------------------------- revision --

    @GetMapping("/revision/due")
    @Operation(summary = "Items due for review today or overdue",
               description = """
                       Ordered by due date, so the most overdue comes first. Items whose
                       content no longer exists after a curriculum change are filtered out
                       rather than returned as blanks.""")
    public JsonNode due(
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest http) {
        return service.due(LearnerId.from(http), Math.clamp(limit, 1, 100));
    }

    @PostMapping("/revision/{kind}/{idOrSlug}/review")
    @Operation(summary = "Grade a review and reschedule the item",
               description = """
                       Quality runs 0 to 5. Anything below 3 is a lapse: the interval resets
                       to one day and the item's ease is reduced, so it both returns
                       immediately and grows more slowly afterwards.

                       Otherwise the interval goes 1 day, 6 days, then multiplied by the
                       item's own ease factor — capped at 180 days, because an interview is
                       weeks away and an item scheduled a year out has left the course.""")
    public Map<String, Object> review(
            @PathVariable String kind,
            @PathVariable String idOrSlug,
            @Valid @RequestBody ReviewRequest request,
            HttpServletRequest http) {
        ProgressRepository.RevisionState next =
                service.review(LearnerId.from(http), kind, idOrSlug, request.quality());
        return Map.of(
                "kind", kind,
                "item", idOrSlug,
                "quality", request.quality(),
                "nextIntervalDays", next.intervalDays(),
                "ease", next.ease(),
                "reviews", next.reviews(),
                "lapses", next.lapses());
    }

    // -------------------------------------------------------------- reset --

    @PostMapping("/reset")
    @Operation(summary = "Delete this learner's progress",
               description = """
                       Removes lesson status, attempt history, quiz answers and the revision
                       schedule for this learner only. The curriculum and the dataset are
                       untouched — those are rebuilt from files and have their own reset.""")
    public Map<String, Object> reset(HttpServletRequest http) {
        String learnerId = LearnerId.from(http);
        return Map.of("learnerId", learnerId, "rowsDeleted", service.reset(learnerId));
    }

    /** @param confidence 1-5, or null to leave the existing rating alone */
    public record LessonStatusRequest(
            boolean completed,
            @Min(1) @Max(5) Integer confidence) {
    }

    /** @param quality 0 (blank) to 5 (perfect recall) */
    public record ReviewRequest(
            @Min(0) @Max(5) int quality) {
    }
}
