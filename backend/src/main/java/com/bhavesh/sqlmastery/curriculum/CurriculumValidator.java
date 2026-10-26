package com.bhavesh.sqlmastery.curriculum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.bhavesh.sqlmastery.curriculum.model.Lesson;
import com.bhavesh.sqlmastery.curriculum.model.Module;
import com.bhavesh.sqlmastery.curriculum.model.Problem;
import com.bhavesh.sqlmastery.curriculum.model.QuizQuestion;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with incomplete content.
 *
 * <p>The requirement this enforces is that every lesson has all fourteen of its
 * parts and every problem all of its required ones. That is the kind of rule
 * that is kept in a document, honoured for the first dozen items and quietly
 * abandoned for the rest, and the result is a course where some pages have
 * interview questions and some do not, with no way to tell which without
 * opening all of them.
 *
 * <p>So it is a startup check instead. Shipping a lesson with no exercises is
 * not a gap somebody might notice later; it is a failed boot with the lesson id
 * and the missing field in the message.
 *
 * <p>Every complaint is collected before any is thrown. Fixing content one
 * restart per error is miserable, and an author who sees all nine problems at
 * once fixes them in one pass.
 */
@Component
public class CurriculumValidator {

    private static final Set<String> LESSON_DIFFICULTIES = Set.of("Beginner", "Intermediate", "Advanced");
    private static final Set<String> PROBLEM_DIFFICULTIES = Set.of("Easy", "Medium", "Hard");

    /** @throws InvalidCurriculumException listing every problem found */
    public void validate(
            List<Module> modules,
            List<Lesson> lessons,
            List<Problem> problems,
            List<QuizQuestion> quiz) {

        List<String> errors = new ArrayList<>();
        Set<String> moduleIds = ids(modules, Module::id, "module", errors);
        Set<String> lessonIds = ids(lessons, Lesson::id, "lesson", errors);
        Set<String> problemIds = ids(problems, Problem::id, "problem", errors);
        ids(quiz, QuizQuestion::id, "quiz question", errors);

        for (Module module : modules) {
            String at = "module " + module.id();
            requireText(module.title(), at, "title", errors);
            requireText(module.slug(), at, "slug", errors);
            requireText(module.summary(), at, "summary", errors);
        }

        for (Lesson lesson : lessons) {
            String at = "lesson " + lesson.id();
            requireText(lesson.title(), at, "title", errors);
            requireText(lesson.slug(), at, "slug", errors);
            requireText(lesson.summary(), at, "summary", errors);
            requireText(lesson.concept(), at, "concept (element 2)", errors);
            if (!moduleIds.contains(lesson.moduleId())) {
                errors.add(at + ": moduleId \"" + lesson.moduleId() + "\" does not exist");
            }
            if (!LESSON_DIFFICULTIES.contains(lesson.difficulty())) {
                errors.add(at + ": difficulty must be one of " + LESSON_DIFFICULTIES);
            }
            if (lesson.estimatedMinutes() <= 0) {
                errors.add(at + ": estimatedMinutes must be positive");
            }
            requireItems(lesson.objectives(), at, "objectives (element 1)", errors);
            requireItems(lesson.syntax(), at, "syntax (element 3)", errors);
            requireItems(lesson.sampleTables(), at, "sampleTables (element 4)", errors);
            requireItems(lesson.examples(), at, "examples (elements 5-7)", errors);
            requireItems(lesson.edgeCases(), at, "edgeCases (element 8)", errors);
            requireItems(lesson.pitfalls(), at, "pitfalls (element 9)", errors);
            requireItems(lesson.interviewQuestions(), at, "interviewQuestions (element 11)", errors);
            requireItems(lesson.exercises(), at, "exercises (element 12)", errors);
            requireItems(lesson.checklist(), at, "checklist (element 13)", errors);
            requireItems(lesson.related(), at, "related (element 14)", errors);
            // performance (element 10) is allowed to be null. Not every lesson
            // has something true to say about performance, and a paragraph
            // written to fill a slot is worse than an absent one.
            if (lesson.dialect() == null) {
                errors.add(at + ": dialect notes are required");
            }
            for (Lesson.Example example : nullSafe(lesson.examples())) {
                requireText(example.sql(), at, "example \"" + example.title() + "\" sql", errors);
                requireText(example.walkthrough(), at,
                        "example \"" + example.title() + "\" walkthrough (element 7)", errors);
            }
            for (Lesson.Exercise exercise : nullSafe(lesson.exercises())) {
                requireText(exercise.solutionSql(), at, "exercise solutionSql", errors);
                if (!PROBLEM_DIFFICULTIES.contains(exercise.difficulty())) {
                    errors.add(at + ": exercise difficulty must be one of " + PROBLEM_DIFFICULTIES);
                }
            }
            for (String related : nullSafe(lesson.related())) {
                if (!lessonIds.contains(related) && !problemIds.contains(related)) {
                    errors.add(at + ": related id \"" + related + "\" matches no lesson or problem");
                }
            }
            for (String prerequisite : nullSafe(lesson.prerequisites())) {
                if (!lessonIds.contains(prerequisite)) {
                    errors.add(at + ": prerequisite \"" + prerequisite + "\" matches no lesson");
                }
            }
        }

        for (Problem problem : problems) {
            String at = "problem " + problem.id();
            requireText(problem.title(), at, "title", errors);
            requireText(problem.slug(), at, "slug", errors);
            requireText(problem.statement(), at, "statement", errors);
            requireText(problem.topic(), at, "topic", errors);
            requireText(problem.solutionSql(), at, "solutionSql", errors);
            if (!PROBLEM_DIFFICULTIES.contains(problem.difficulty())) {
                errors.add(at + ": difficulty must be one of " + PROBLEM_DIFFICULTIES);
            }
            requireItems(problem.tables(), at, "tables", errors);
            requireItems(problem.expectedColumns(), at, "expectedColumns", errors);
            requireItems(problem.edgeCases(), at, "edgeCases", errors);
            requireItems(problem.hints(), at, "hints", errors);
            if (problem.source() != null) {
                // A source is optional, but a half-filled one is a citation
                // that cannot be checked, which is worse than none.
                requireText(problem.source().name(), at, "source.name", errors);
                requireText(problem.source().url(), at, "source.url", errors);
            }
            Problem.Explanation explanation = problem.explanation();
            if (explanation == null) {
                errors.add(at + ": explanation is required");
                continue;
            }
            requireItems(explanation.lineByLine(), at, "explanation.lineByLine", errors);
            requireText(explanation.walkthrough(), at, "explanation.walkthrough", errors);
            requireText(explanation.performance(), at, "explanation.performance", errors);
            requireItems(explanation.wrongApproaches(), at, "explanation.wrongApproaches", errors);
            requireItems(explanation.extraTests(), at, "explanation.extraTests", errors);
            requireItems(explanation.followUps(), at, "explanation.followUps", errors);
            requireItems(explanation.related(), at, "explanation.related", errors);
            for (String related : nullSafe(explanation.related())) {
                if (!lessonIds.contains(related) && !problemIds.contains(related)) {
                    errors.add(at + ": related id \"" + related + "\" matches no lesson or problem");
                }
            }
        }

        for (QuizQuestion question : quiz) {
            String at = "quiz question " + question.id();
            requireText(question.question(), at, "question", errors);
            requireText(question.explanation(), at, "explanation", errors);
            requireItems(question.options(), at, "options", errors);
            List<String> options = nullSafe(question.options());
            if (options.size() < 2) {
                errors.add(at + ": needs at least two options");
            }
            if (question.answerIndex() < 0 || question.answerIndex() >= options.size()) {
                errors.add(at + ": answerIndex " + question.answerIndex()
                           + " is outside the " + options.size() + " options");
            }
            if (!PROBLEM_DIFFICULTIES.contains(question.difficulty())) {
                errors.add(at + ": difficulty must be one of " + PROBLEM_DIFFICULTIES);
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidCurriculumException(errors);
        }
    }

    private <T> Set<String> ids(
            Collection<T> items,
            java.util.function.Function<T, String> idOf,
            String kind,
            List<String> errors) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (T item : items) {
            String id = idOf.apply(item);
            if (id == null || id.isBlank()) {
                errors.add("a " + kind + " has no id");
            } else if (!seen.add(id)) {
                errors.add("duplicate " + kind + " id \"" + id + "\"");
            }
        }
        return seen;
    }

    private static void requireText(String value, String at, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(at + ": " + field + " is required");
        }
    }

    private static void requireItems(Collection<?> value, String at, String field, List<String> errors) {
        if (value == null || value.isEmpty()) {
            errors.add(at + ": " + field + " must have at least one entry");
        }
    }

    private static <T> List<T> nullSafe(List<T> value) {
        return value == null ? List.of() : value;
    }

    /** Thrown with every complaint, so one restart reports all of them. */
    public static class InvalidCurriculumException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> errors;

        public InvalidCurriculumException(List<String> errors) {
            super("The curriculum is incomplete (" + errors.size() + " problems):"
                  + System.lineSeparator() + "  - " + String.join(System.lineSeparator() + "  - ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
