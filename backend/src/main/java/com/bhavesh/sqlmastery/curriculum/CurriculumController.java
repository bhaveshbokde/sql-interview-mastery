package com.bhavesh.sqlmastery.curriculum;

import java.util.List;
import java.util.Map;

import com.bhavesh.sqlmastery.error.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The curriculum: modules, lessons, search and the dashboard counts.
 *
 * <p>Problems are served by {@link ProblemController} instead, because a
 * problem's hints and solution need endpoints of their own and keeping them in
 * one class with the lesson endpoints would blur the line this design depends
 * on.
 */
@RestController
@RequestMapping("/api/curriculum")
@Tag(name = "Curriculum", description = "Modules, lessons and search.")
public class CurriculumController {

    private final CurriculumRepository repository;

    public CurriculumController(CurriculumRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/modules")
    @Operation(summary = "Modules in learning order, each with its lessons")
    public JsonNode modules() {
        return repository.findModulesWithLessons();
    }

    @GetMapping("/lessons")
    @Operation(summary = "Every lesson, summary fields only")
    public JsonNode lessons() {
        return repository.findAllLessonSummaries();
    }

    @GetMapping("/lessons/{idOrSlug}")
    @Operation(summary = "One lesson in full",
               description = """
                       Includes every example with the output it actually produced when the
                       content was last verified against the database, rather than output
                       transcribed by hand.""")
    public JsonNode lesson(@PathVariable String idOrSlug) {
        return repository.findLesson(idOrSlug)
                .orElseThrow(() -> new ResourceNotFoundException("lesson", idOrSlug));
    }

    @GetMapping("/search")
    @Operation(summary = "Full-text search across lessons and problems",
               description = "Backed by a PostgreSQL tsvector column and a GIN index.")
    public JsonNode search(
            @RequestParam String q,
            @RequestParam(defaultValue = "25") int limit) {
        return repository.search(q, Math.clamp(limit, 1, 100));
    }

    @GetMapping("/statistics")
    @Operation(summary = "Counts for the dashboard, plus the content checksum")
    public JsonNode statistics() {
        return repository.statistics();
    }

    @GetMapping("/topics")
    @Operation(summary = "Distinct problem topics, for the filter control")
    public Map<String, List<String>> topics() {
        return Map.of(
                "problemTopics", repository.findProblemTopics(),
                "quizTopics", repository.findQuizTopics());
    }
}
