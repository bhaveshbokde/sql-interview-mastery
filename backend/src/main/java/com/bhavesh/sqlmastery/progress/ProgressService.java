package com.bhavesh.sqlmastery.progress;

import com.bhavesh.sqlmastery.error.ResourceNotFoundException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The progress feature's business rules, such as they are.
 *
 * <p>Three of them are worth stating, because they are decisions rather than
 * mechanics:
 *
 * <ul>
 *   <li><b>Completing a lesson schedules it for revision.</b> Otherwise the
 *       revision queue only ever contains what somebody remembered to add,
 *       which in practice is nothing.</li>
 *   <li><b>Solving a problem schedules it too</b>, and the first interval
 *       depends on how many hints were used — a problem solved with three
 *       hints is not learnt and comes back tomorrow.</li>
 *   <li><b>An id that names no content is a 404</b>, checked here rather than
 *       relying on the absence of a foreign key. The tables are deliberately
 *       soft-referenced so a curriculum change cannot delete history, and the
 *       price of that is validating on the way in.</li>
 * </ul>
 */
@Service
public class ProgressService {

    private final ProgressRepository repository;
    private final RevisionScheduler scheduler;

    public ProgressService(ProgressRepository repository, RevisionScheduler scheduler) {
        this.repository = repository;
        this.scheduler = scheduler;
    }

    // ------------------------------------------------------------ lessons --

    @Transactional
    public void recordLessonView(String learnerId, String idOrSlug) {
        repository.recordLessonView(learnerId, requireLesson(idOrSlug));
    }

    /**
     * Marks a lesson complete or reopens it.
     *
     * <p>Completing also puts the lesson into the revision queue, due
     * tomorrow. Reopening deliberately leaves the revision row alone: a
     * learner who reopens a lesson to reread it has not forgotten it, and
     * resetting their schedule would punish the rereading.
     */
    @Transactional
    public void setLessonStatus(String learnerId, String idOrSlug, boolean completed, Integer confidence) {
        String lessonId = requireLesson(idOrSlug);
        repository.setLessonStatus(learnerId, lessonId, completed, confidence);
        if (completed && repository.findRevisionState(learnerId, "lesson", lessonId) == null) {
            ProgressRepository.RevisionState initial = scheduler.initial();
            // A low self-reported confidence shortens nothing here — the first
            // interval is one day regardless — but it lowers the starting ease
            // so the item grows more slowly from the outset. That is the one
            // place the confidence rating has a mechanical effect rather than
            // being a note to self.
            double ease = confidence == null
                    ? initial.ease()
                    : RevisionScheduler.round2(Math.max(RevisionScheduler.MINIMUM_EASE,
                               RevisionScheduler.DEFAULT_EASE - (5 - confidence) * 0.15));
            repository.upsertRevision(learnerId, "lesson", lessonId,
                    initial.intervalDays(), ease, 0, 0, confidence == null ? 3 : confidence);
        }
    }

    public ArrayNode lessonProgress(String learnerId) {
        return repository.findLessonProgress(learnerId);
    }

    // ----------------------------------------------------------- problems --

    /**
     * Records an attempt and, if it was right, schedules the problem.
     *
     * <p>Called by the evaluation endpoint rather than by the client, so the
     * history is a record of what the server actually judged rather than of
     * what a browser chose to report.
     */
    @Transactional
    public void recordProblemAttempt(
            String learnerId, String problemId, String sql,
            String verdict, boolean correct, int hintsUsed) {
        repository.recordProblemAttempt(learnerId, problemId, sql, verdict, correct, hintsUsed);
        if (!correct) {
            return;
        }
        if (repository.findRevisionState(learnerId, "problem", problemId) == null) {
            ProgressRepository.RevisionState initial = scheduler.initial();
            // Hints are a signal about how well the problem is understood, so
            // they lower the starting ease the same way a low confidence
            // rating does for a lesson.
            double ease = RevisionScheduler.round2(Math.max(RevisionScheduler.MINIMUM_EASE,
                                   RevisionScheduler.DEFAULT_EASE - hintsUsed * 0.2));
            repository.upsertRevision(learnerId, "problem", problemId,
                    initial.intervalDays(), ease, 0, 0, Math.max(0, 5 - hintsUsed));
        }
    }

    public ArrayNode problemProgress(String learnerId) {
        return repository.findProblemProgress(learnerId);
    }

    public ArrayNode attempts(String learnerId, String idOrSlug, int limit) {
        requireProblem(idOrSlug);
        return repository.findAttempts(learnerId, idOrSlug, limit);
    }

    // --------------------------------------------------------------- quiz --

    @Transactional
    public void recordQuizAttempt(String learnerId, String questionId, int chosenIndex, boolean correct) {
        repository.recordQuizAttempt(learnerId, questionId, chosenIndex, correct);
    }

    public ArrayNode quizAccuracy(String learnerId) {
        return repository.findQuizAccuracyByTopic(learnerId);
    }

    // ----------------------------------------------------------- revision --

    public ArrayNode due(String learnerId, int limit) {
        return repository.findDueRevisions(learnerId, limit);
    }

    /**
     * Grades a review and reschedules the item.
     *
     * @param quality 0 to 5, where anything below 3 resets the interval
     * @return the new state, so the caller can show when it will next appear
     */
    @Transactional
    public ProgressRepository.RevisionState review(
            String learnerId, String kind, String idOrSlug, int quality) {
        String itemId = switch (kind) {
            case "lesson" -> requireLesson(idOrSlug);
            case "problem" -> requireProblem(idOrSlug);
            case "quiz" -> requireQuizQuestion(idOrSlug);
            default -> throw new ResourceNotFoundException("revision kind", kind);
        };
        ProgressRepository.RevisionState current = repository.findRevisionState(learnerId, kind, itemId);
        ProgressRepository.RevisionState next = scheduler.next(quality, current);
        repository.upsertRevision(learnerId, kind, itemId,
                next.intervalDays(), next.ease(), next.reviews(), next.lapses(), quality);
        return next;
    }

    // -------------------------------------------------------------- stats --

    public ObjectNode overview(String learnerId) {
        return repository.overview(learnerId);
    }

    @Transactional
    public int reset(String learnerId) {
        return repository.reset(learnerId);
    }

    // ------------------------------------------------------------ helpers --

    private String requireLesson(String idOrSlug) {
        String id = repository.resolveLessonId(idOrSlug);
        if (id == null) {
            throw new ResourceNotFoundException("lesson", idOrSlug);
        }
        return id;
    }

    private String requireProblem(String idOrSlug) {
        String id = repository.resolveProblemId(idOrSlug);
        if (id == null) {
            throw new ResourceNotFoundException("problem", idOrSlug);
        }
        return id;
    }

    private String requireQuizQuestion(String id) {
        if (!repository.quizQuestionExists(id)) {
            throw new ResourceNotFoundException("quiz question", id);
        }
        return id;
    }
}
