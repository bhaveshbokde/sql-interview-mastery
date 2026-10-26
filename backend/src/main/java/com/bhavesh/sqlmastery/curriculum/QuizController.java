package com.bhavesh.sqlmastery.curriculum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bhavesh.sqlmastery.progress.LearnerId;
import com.bhavesh.sqlmastery.progress.ProgressService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quiz mode: the theory questions that have a right answer and no query to run.
 *
 * <p>Questions are served without their answers and marked server-side. That is
 * not because a self-study quiz needs to be cheat-proof — anyone determined to
 * look up the answer can read the repository — but because sending the answer
 * key to the browser and asking it not to look is the kind of shortcut that
 * gets copied into an application where it does matter.
 */
@RestController
@RequestMapping("/api/quiz")
@Tag(name = "Quiz", description = "Multiple-choice theory questions, marked server-side.")
public class QuizController {

    private final CurriculumRepository repository;
    private final ProgressService progressService;

    public QuizController(CurriculumRepository repository, ProgressService progressService) {
        this.repository = repository;
        this.progressService = progressService;
    }

    @GetMapping("/questions")
    @Operation(summary = "A set of questions, without the answers",
               description = """
                       The same request returns the same questions in the same order, so
                       "I got question 3 wrong" still means something after a refresh.""")
    public JsonNode questions(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String difficulty,
            @RequestParam(defaultValue = "10") int count) {
        return repository.findQuizQuestions(topic, difficulty, Math.clamp(count, 1, 50));
    }

    @PostMapping("/submit")
    @Operation(summary = "Mark a set of answers",
               description = "Returns, per question, whether it was right and why.")
    public Map<String, Object> submit(
            @Valid @RequestBody QuizSubmission submission,
            HttpServletRequest http) {
        String learnerId = LearnerId.from(http);
        List<Map<String, Object>> results = new ArrayList<>(submission.answers().size());
        int correct = 0;
        for (Map.Entry<String, Integer> answer : submission.answers().entrySet()) {
            var stored = repository.findQuizAnswer(answer.getKey());
            if (stored.isEmpty()) {
                results.add(Map.of("id", answer.getKey(), "known", false));
                continue;
            }
            CurriculumRepository.QuizAnswer quizAnswer = stored.get();
            boolean right = quizAnswer.answerIndex() == answer.getValue();
            if (right) {
                correct++;
            }
            // Recorded server-side, for the same reason problem attempts are:
            // the marking happens here, so the history should be written here.
            progressService.recordQuizAttempt(
                    learnerId, quizAnswer.id(), answer.getValue(), right);
            results.add(Map.of(
                    "id", quizAnswer.id(),
                    "known", true,
                    "correct", right,
                    "chosenIndex", answer.getValue(),
                    "answerIndex", quizAnswer.answerIndex(),
                    "explanation", quizAnswer.explanation()));
        }
        return Map.of(
                "total", submission.answers().size(),
                "correct", correct,
                "results", results);
    }

    /** @param answers question id to the zero-based index the learner chose */
    public record QuizSubmission(@NotEmpty Map<String, Integer> answers) {
    }
}
