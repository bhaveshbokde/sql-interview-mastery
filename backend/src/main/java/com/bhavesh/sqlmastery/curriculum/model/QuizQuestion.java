package com.bhavesh.sqlmastery.curriculum.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A multiple-choice question for quiz and mock-interview mode.
 *
 * <p>These are the theory questions that have a right answer and no query to
 * run: isolation levels, what MVCC does, when an index is not used. Anything
 * that can be settled by executing SQL belongs in a lesson example or a
 * problem instead, where the learner can see it settled.
 *
 * @param answerIndex zero-based index into {@code options}. The loader checks
 *                    it is in range, because an off-by-one here marks correct
 *                    answers wrong and is invisible until someone complains
 * @param explanation why the answer is right — and, where the distractors are
 *                    the interesting part, why the others are wrong
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record QuizQuestion(
        String id,
        String topic,
        String difficulty,
        String question,
        List<String> options,
        int answerIndex,
        String explanation) {
}
