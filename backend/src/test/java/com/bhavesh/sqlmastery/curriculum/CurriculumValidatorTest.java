package com.bhavesh.sqlmastery.curriculum;

import java.util.List;

import com.bhavesh.sqlmastery.curriculum.model.Lesson;
import com.bhavesh.sqlmastery.curriculum.model.Module;
import com.bhavesh.sqlmastery.curriculum.model.Problem;
import com.bhavesh.sqlmastery.curriculum.model.QuizQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The validator turns "every lesson must be complete" from a convention into a
 * failed startup. These tests check that the gate is actually closed, one
 * missing field at a time.
 */
class CurriculumValidatorTest {

    private final CurriculumValidator validator = new CurriculumValidator();

    private static Module module() {
        return new Module("m1", 1, "Module", "module", "A summary", "icon");
    }

    private static Lesson completeLesson() {
        return new Lesson(
                "l1", "m1", 10, "Title", "slug", "Beginner", 20, "Summary",
                List.of("tag"),
                List.of(),
                List.of("An objective"),
                "The concept",
                List.of(new Lesson.SyntaxForm("SELECT", "notes")),
                List.of("employees"),
                List.of(new Lesson.Example("Example", "select 1", null, "A walkthrough", null)),
                List.of(new Lesson.EdgeCase("Edge", "body", "select 1", null, null)),
                List.of(new Lesson.Pitfall("mistake", "why", "fix")),
                "Performance notes",
                List.of(new Lesson.QuestionAnswer("Q?", "A.")),
                List.of(new Lesson.Exercise("Prompt", "Easy", "hint", "select 1", null)),
                List.of("A checklist item"),
                // `related` is required to have at least one entry, so the
                // fixture points at itself. Artificial, and it keeps each
                // test self-contained rather than needing a second lesson
                // that exists only to be referenced.
                List.of("l1"),
                new Lesson.Dialect("standard", "postgres", "mysql"));
    }

    private static Problem completeProblem() {
        return new Problem(
                "p1", 10, "Title", "slug", "Easy", "Topic", List.of("tag"),
                null, false, "The statement",
                List.of("employees"), List.of("a"), List.of("An edge case"),
                List.of("A hint"), "select 1", null,
                new Problem.Explanation(
                        List.of(new Problem.Line("select 1", "note")),
                        "A walkthrough",
                        List.of(),
                        "Performance notes",
                        List.of(new Problem.WrongApproach("Wrong", "select 2", "because")),
                        List.of(new Problem.ExtraTest("Test", "learning_alt", "note", "select 1", null)),
                        List.of(new Lesson.QuestionAnswer("Follow up?", "Answer.")),
                        List.of("p1")));
    }

    private static QuizQuestion completeQuestion() {
        return new QuizQuestion("q1", "Topic", "Easy", "Question?",
                List.of("right", "wrong"), 0, "Because.");
    }

    private void validate(Lesson lesson) {
        validator.validate(List.of(module()), List.of(lesson), List.of(), List.of());
    }

    private List<String> errorsFor(Lesson lesson) {
        try {
            validate(lesson);
        } catch (CurriculumValidator.InvalidCurriculumException e) {
            return e.errors();
        }
        throw new AssertionError("Expected the lesson to be rejected");
    }

    @Test
    @DisplayName("a complete curriculum passes")
    void acceptsCompleteContent() {
        assertThatCode(() -> validator.validate(
                List.of(module()), List.of(completeLesson()),
                List.of(completeProblem()), List.of(completeQuestion())))
                .doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("refuses an incomplete lesson")
    class IncompleteLessons {

        @Test
        void requiresObjectives() {
            Lesson l = withObjectives(List.of());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("objectives"));
        }

        @Test
        void requiresConcept() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), "   ", c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("concept"));
        }

        @Test
        void requiresExamples() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), List.of(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("examples"));
        }

        @Test
        void requiresDialectNotes() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), null);
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("dialect"));
        }

        @Test
        @DisplayName("performance notes are allowed to be absent")
        void performanceIsOptional() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), null, c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
            // Not every lesson has something true to say about performance,
            // and a paragraph written to fill a slot is worse than none.
            assertThatCode(() -> validate(l)).doesNotThrowAnyException();
        }

        @Test
        void rejectsAnUnknownDifficulty() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    "Fiendish", c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("difficulty"));
        }

        @Test
        void rejectsAModuleThatDoesNotExist() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), "no-such-module", c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("no-such-module"));
        }

        private Lesson withObjectives(List<String> objectives) {
            Lesson c = completeLesson();
            return new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    objectives, c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
        }
    }

    @Nested
    @DisplayName("cross-references")
    class References {

        @Test
        @DisplayName("a dangling related id is an error")
        void rejectsDanglingRelated() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), List.of("does-not-exist"), c.dialect());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("does-not-exist"));
        }

        @Test
        @DisplayName("a dangling prerequisite is an error")
        void rejectsDanglingPrerequisite() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(),
                    List.of("missing-lesson"),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), c.related(), c.dialect());
            assertThat(errorsFor(l)).anyMatch(e -> e.contains("missing-lesson"));
        }

        @Test
        @DisplayName("a related id may point at a problem, not just a lesson")
        void relatedMayBeAProblem() {
            Lesson c = completeLesson();
            Lesson l = new Lesson(c.id(), c.moduleId(), c.position(), c.title(), c.slug(),
                    c.difficulty(), c.estimatedMinutes(), c.summary(), c.tags(), c.prerequisites(),
                    c.objectives(), c.concept(), c.syntax(), c.sampleTables(), c.examples(),
                    c.edgeCases(), c.pitfalls(), c.performance(), c.interviewQuestions(),
                    c.exercises(), c.checklist(), List.of("p1"), c.dialect());
            assertThatCode(() -> validator.validate(
                    List.of(module()), List.of(l), List.of(completeProblem()), List.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsDuplicateIds() {
            Lesson l = completeLesson();
            assertThatThrownBy(() -> validator.validate(
                    List.of(module()), List.of(l, l), List.of(), List.of()))
                    .isInstanceOf(CurriculumValidator.InvalidCurriculumException.class)
                    .hasMessageContaining("duplicate");
        }
    }

    @Nested
    @DisplayName("quiz questions")
    class Quiz {

        @Test
        @DisplayName("an answerIndex outside the options is an error")
        void rejectsOutOfRangeAnswer() {
            QuizQuestion q = new QuizQuestion("q1", "Topic", "Easy", "Q?",
                    List.of("a", "b"), 5, "Because.");
            // An off-by-one here marks correct answers wrong and is invisible
            // until somebody complains, which is exactly why it is checked at
            // startup.
            assertThatThrownBy(() -> validator.validate(
                    List.of(module()), List.of(), List.of(), List.of(q)))
                    .isInstanceOf(CurriculumValidator.InvalidCurriculumException.class)
                    .hasMessageContaining("answerIndex");
        }

        @Test
        void rejectsASingleOption() {
            QuizQuestion q = new QuizQuestion("q1", "Topic", "Easy", "Q?",
                    List.of("only"), 0, "Because.");
            assertThatThrownBy(() -> validator.validate(
                    List.of(module()), List.of(), List.of(), List.of(q)))
                    .isInstanceOf(CurriculumValidator.InvalidCurriculumException.class)
                    .hasMessageContaining("two options");
        }
    }

    @Nested
    @DisplayName("problems")
    class Problems {

        @Test
        void requiresHints() {
            Problem c = completeProblem();
            Problem p = new Problem(c.id(), c.position(), c.title(), c.slug(), c.difficulty(),
                    c.topic(), c.tags(), c.source(), c.orderSensitive(), c.statement(),
                    c.tables(), c.expectedColumns(), c.edgeCases(), List.of(),
                    c.solutionSql(), c.expected(), c.explanation());
            assertThatThrownBy(() -> validator.validate(
                    List.of(module()), List.of(), List.of(p), List.of()))
                    .isInstanceOf(CurriculumValidator.InvalidCurriculumException.class)
                    .hasMessageContaining("hints");
        }

        @Test
        @DisplayName("a half-filled source is worse than none, so it is rejected")
        void rejectsPartialSource() {
            Problem c = completeProblem();
            Problem p = new Problem(c.id(), c.position(), c.title(), c.slug(), c.difficulty(),
                    c.topic(), c.tags(), new Problem.Source("LeetCode", null),
                    c.orderSensitive(), c.statement(), c.tables(), c.expectedColumns(),
                    c.edgeCases(), c.hints(), c.solutionSql(), c.expected(), c.explanation());
            assertThatThrownBy(() -> validator.validate(
                    List.of(module()), List.of(), List.of(p), List.of()))
                    .isInstanceOf(CurriculumValidator.InvalidCurriculumException.class)
                    .hasMessageContaining("source.url");
        }

        @Test
        @DisplayName("no source at all is fine — an unattributed problem beats a fabricated citation")
        void allowsNoSource() {
            assertThatCode(() -> validator.validate(
                    List.of(module()), List.of(), List.of(completeProblem()), List.of()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("reports every problem at once, not one per restart")
    void collectsAllErrors() {
        Lesson c = completeLesson();
        Lesson broken = new Lesson(c.id(), "no-such-module", c.position(), c.title(), c.slug(),
                "Fiendish", 0, c.summary(), c.tags(), c.prerequisites(),
                List.of(), "", List.of(), List.of(), List.of(),
                List.of(), List.of(), c.performance(), List.of(),
                List.of(), List.of(), List.of(), null);
        // Fixing content one restart per error is miserable; an author who
        // sees everything at once fixes it in one pass.
        assertThat(errorsFor(broken)).hasSizeGreaterThan(8);
    }
}
