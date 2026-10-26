package com.bhavesh.sqlmastery.curriculum;

import com.bhavesh.sqlmastery.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The curriculum as it is actually served.
 *
 * <p>The most important assertions here are the negative ones. A problem must
 * not carry its own answer, and a quiz question must not carry its own key —
 * those are the properties the whole storage layout exists to guarantee, and
 * the only way to know they hold is to ask the endpoint and look.
 */
@IntegrationTest
class CurriculumApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode getJson(String path) throws Exception {
        String body = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Nested
    @DisplayName("modules and lessons")
    class Lessons {

        @Test
        @DisplayName("every module is returned in learning order with its lessons")
        void modulesInOrder() throws Exception {
            JsonNode modules = getJson("/api/curriculum/modules");
            assertThat(modules).hasSize(9);
            int previous = 0;
            for (JsonNode module : modules) {
                assertThat(module.get("position").asInt()).isGreaterThan(previous);
                previous = module.get("position").asInt();
                assertThat(module.get("lessons").isArray()).isTrue();
                assertThat(module.get("lessonCount").asInt()).isPositive();
            }
        }

        @Test
        @DisplayName("every lesson is reachable by both id and slug")
        void lessonsByIdAndSlug() throws Exception {
            for (JsonNode summary : getJson("/api/curriculum/lessons")) {
                String id = summary.get("id").asText();
                String slug = summary.get("slug").asText();
                assertThat(getJson("/api/curriculum/lessons/" + id).get("id").asText())
                        .isEqualTo(id);
                assertThat(getJson("/api/curriculum/lessons/" + slug).get("id").asText())
                        .isEqualTo(id);
            }
        }

        @Test
        @DisplayName("every lesson has all fourteen of its required parts, populated")
        void lessonsAreComplete() throws Exception {
            // The validator enforces this at startup. Checking it again here
            // confirms the parts survive the round trip through jsonb and
            // back, which the validator says nothing about.
            for (JsonNode summary : getJson("/api/curriculum/lessons")) {
                JsonNode lesson = getJson("/api/curriculum/lessons/" + summary.get("id").asText());
                String where = "lesson " + lesson.get("id").asText();
                assertThat(lesson.get("concept").asText()).describedAs(where).isNotBlank();
                for (String field : new String[] {
                    "objectives", "syntax", "sampleTables", "examples", "edgeCases",
                    "pitfalls", "interviewQuestions", "exercises", "checklist", "related"}) {
                    assertThat(lesson.get(field)).describedAs("%s.%s", where, field).isNotEmpty();
                }
                assertThat(lesson.get("dialect").get("postgres").asText())
                        .describedAs(where).isNotBlank();
            }
        }

        @Test
        @DisplayName("every example carries the output its query actually produced")
        void examplesHaveRecordedOutput() throws Exception {
            // The single property this whole content pipeline exists for: a
            // tutorial cannot show a result table its own query does not
            // produce, because nobody is allowed to type one.
            int examples = 0;
            for (JsonNode summary : getJson("/api/curriculum/lessons")) {
                JsonNode lesson = getJson("/api/curriculum/lessons/" + summary.get("id").asText());
                for (JsonNode example : lesson.get("examples")) {
                    examples++;
                    String where = lesson.get("id").asText() + " / " + example.get("title").asText();
                    assertThat(example.get("sql").asText()).describedAs(where).isNotBlank();
                    assertThat(example.get("walkthrough").asText()).describedAs(where).isNotBlank();
                    assertThat(example.get("result")).describedAs(where).isNotNull();
                    assertThat(example.get("result").get("columns"))
                            .describedAs("%s columns", where).isNotEmpty();
                }
                for (JsonNode exercise : lesson.get("exercises")) {
                    assertThat(exercise.get("result"))
                            .describedAs("%s exercise result", lesson.get("id").asText())
                            .isNotNull();
                }
            }
            assertThat(examples).isGreaterThan(100);
        }

        @Test
        @DisplayName("an unknown lesson is a 404 with a usable message")
        void unknownLesson() throws Exception {
            mvc.perform(get("/api/curriculum/lessons/no-such-lesson"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("problems keep their answers to themselves")
    class ProblemSecrecy {

        @Test
        @DisplayName("the problem endpoint returns no solution, no hints and no expected rows")
        void problemHidesTheAnswer() throws Exception {
            for (JsonNode summary : getJson("/api/problems")) {
                JsonNode problem = getJson("/api/problems/" + summary.get("id").asText());
                String where = "problem " + problem.get("id").asText();
                assertThat(problem.has("solutionSql")).describedAs("%s solutionSql", where).isFalse();
                assertThat(problem.has("hints")).describedAs("%s hints", where).isFalse();
                assertThat(problem.has("explanation")).describedAs("%s explanation", where).isFalse();
                // `expected` is the recorded output of the solution — the
                // answer as a table. Easy to forget, and the reason this
                // assertion is here.
                assertThat(problem.has("expected")).describedAs("%s expected", where).isFalse();
                // What it must have.
                assertThat(problem.get("statement").asText()).describedAs(where).isNotBlank();
                assertThat(problem.get("expectedColumns")).describedAs(where).isNotEmpty();
                assertThat(problem.get("tables")).describedAs(where).isNotEmpty();
                assertThat(problem.get("edgeCases")).describedAs(where).isNotEmpty();
            }
        }

        @Test
        @DisplayName("hints are served one at a time, so the network tab is not a shortcut")
        void hintsComeOneAtATime() throws Exception {
            JsonNode count = getJson("/api/problems/second-highest-salary/hints/count");
            int total = count.get("total").asInt();
            assertThat(total).isGreaterThan(1);

            JsonNode first = getJson("/api/problems/second-highest-salary/hints/1");
            assertThat(first.get("hint").asText()).isNotBlank();
            assertThat(first.get("ordinal").asInt()).isEqualTo(1);
            // The response carries exactly one hint and no sign of the others.
            assertThat(first.has("hints")).isFalse();

            mvc.perform(get("/api/problems/second-highest-salary/hints/" + (total + 1)))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/problems/second-highest-salary/hints/0"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the solution endpoint returns the full explanation when asked")
        void solutionIsAvailableOnRequest() throws Exception {
            JsonNode solution = getJson("/api/problems/second-highest-salary/solution");
            assertThat(solution.get("sql").asText()).isNotBlank();
            JsonNode explanation = solution.get("explanation");
            assertThat(explanation.get("lineByLine")).isNotEmpty();
            assertThat(explanation.get("walkthrough").asText()).isNotBlank();
            assertThat(explanation.get("performance").asText()).isNotBlank();
            assertThat(explanation.get("wrongApproaches")).isNotEmpty();
            assertThat(explanation.get("followUps")).isNotEmpty();
            assertThat(explanation.get("expected").get("rows")).isNotEmpty();
        }

        @Test
        @DisplayName("every problem's extra tests ran and recorded a result")
        void extraTestsAreReal() throws Exception {
            for (JsonNode summary : getJson("/api/problems")) {
                JsonNode solution = getJson("/api/problems/" + summary.get("id").asText() + "/solution");
                for (JsonNode test : solution.get("explanation").get("extraTests")) {
                    String where = summary.get("id").asText() + " / " + test.get("name").asText();
                    assertThat(test.get("sql").asText()).describedAs(where).isNotBlank();
                    // An extra test that merely asserted "this also works on
                    // learning_alt" without running it there would be exactly
                    // the unverified claim the recording step exists to stop.
                    assertThat(test.get("result")).describedAs("%s result", where).isNotNull();
                    assertThat(test.get("result").get("schema").asText())
                            .describedAs("%s schema", where)
                            .isEqualTo(test.get("schema").asText());
                }
            }
        }
    }

    @Nested
    @DisplayName("quiz")
    class Quiz {

        @Test
        @DisplayName("questions are served without the answer key")
        void questionsHideTheAnswer() throws Exception {
            JsonNode questions = getJson("/api/quiz/questions?count=10");
            assertThat(questions).hasSize(10);
            for (JsonNode q : questions) {
                assertThat(q.has("answerIndex")).describedAs("question %s", q.get("id")).isFalse();
                assertThat(q.has("explanation")).describedAs("question %s", q.get("id")).isFalse();
                assertThat(q.get("options")).hasSizeGreaterThanOrEqualTo(2);
            }
        }

        @Test
        @DisplayName("the same request returns the same paper")
        void questionsAreStable() throws Exception {
            // Ordered by md5(id) rather than random(), so "I got question 3
            // wrong" still means something after a refresh.
            assertThat(getJson("/api/quiz/questions?count=5").toString())
                    .isEqualTo(getJson("/api/quiz/questions?count=5").toString());
        }

        @Test
        @DisplayName("marking happens server-side and explains the answer")
        void marking() throws Exception {
            mvc.perform(post("/api/quiz/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"answers\": {\"q-null-not-in\": 0, \"q-rank-vs-dense-rank\": 3}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.correct").value(1))
                    .andExpect(jsonPath("$.results[0].explanation", notNullValue()));
        }

        @Test
        @DisplayName("an unknown question id is reported rather than silently scored")
        void unknownQuestion() throws Exception {
            mvc.perform(post("/api/quiz/submit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"answers\": {\"q-does-not-exist\": 0}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results[0].known").value(false));
        }
    }

    @Nested
    @DisplayName("answer evaluation")
    class Evaluation {

        private org.springframework.test.web.servlet.ResultActions evaluate(String slug, String sql)
                throws Exception {
            return mvc.perform(post("/api/problems/" + slug + "/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            java.util.Map.of("sql", sql))));
        }

        @Test
        @DisplayName("the reference solution evaluates as correct on both datasets")
        void referenceSolutionIsCorrect() throws Exception {
            // If a reference solution did not pass its own check, the problem
            // would be unanswerable. Running every one of them is the cheapest
            // possible guard against that.
            for (JsonNode summary : getJson("/api/problems")) {
                String slug = summary.get("slug").asText();
                String sql = getJson("/api/problems/" + slug + "/solution").get("sql").asText();
                evaluate(slug, sql)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.verdict").value("CORRECT"))
                        .andExpect(jsonPath("$.correct").value(true));
            }
        }

        @Test
        @DisplayName("an answer right on the sample and wrong on the alternate is caught")
        void catchesSampleOnlyAnswers() throws Exception {
            // OFFSET 1 is the classic wrong answer to second-highest-salary.
            // It is right on data with no tie at the top and wrong on
            // learning_alt, where two employees share the top salary — which
            // is the entire reason the second dataset exists.
            evaluate("second-highest-salary",
                    "select salary, first_name || ' ' || last_name as full_name "
                    + "from employees order by salary desc, emp_id limit 1 offset 1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verdict").value("CORRECT_ON_SAMPLE_ONLY"))
                    .andExpect(jsonPath("$.correct").value(false));
        }

        @Test
        @DisplayName("wrong columns are reported before wrong rows")
        void reportsColumnProblemsFirst() throws Exception {
            evaluate("duplicate-emails", "select email from contacts group by email")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verdict").value("INCORRECT"))
                    .andExpect(jsonPath("$.datasets[0].comparison.code").value("COLUMNS"));
        }

        @Test
        @DisplayName("a submission that will not run is reported as such, not as incorrect")
        void reportsFailureToRun() throws Exception {
            evaluate("second-highest-salary", "select nosuchcolumn from employees")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verdict").value("DID_NOT_RUN"))
                    .andExpect(jsonPath("$.error.code").value("42703"));
        }

        @Test
        @DisplayName("a write is refused rather than evaluated")
        void refusesAWrite() throws Exception {
            evaluate("second-highest-salary", "delete from employees")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verdict").value("REFUSED"));
        }

        @Test
        @DisplayName("every response states what the check does and does not establish")
        void alwaysCarriesItsLimits() throws Exception {
            evaluate("second-highest-salary", "select 1")
                    .andExpect(jsonPath("$.limits", notNullValue()));
        }
    }

    @Nested
    @DisplayName("search and statistics")
    class SearchAndStats {

        @Test
        void searchRanksLessonsAndProblems() throws Exception {
            JsonNode results = getJson("/api/curriculum/search?q=window+function&limit=10");
            assertThat(results).isNotEmpty();
            double previous = Double.MAX_VALUE;
            for (JsonNode r : results) {
                assertThat(r.get("kind").asText()).isIn("lesson", "problem");
                assertThat(r.get("rank").asDouble()).isLessThanOrEqualTo(previous);
                previous = r.get("rank").asDouble();
            }
        }

        @Test
        @DisplayName("a search phrase with an apostrophe does not blow up")
        void searchHandlesAwkwardInput() throws Exception {
            mvc.perform(get("/api/curriculum/search").param("q", "PostgreSQL's \"planner\" or NULL"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        void statisticsCountEverything() throws Exception {
            mvc.perform(get("/api/curriculum/statistics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modules").value(9))
                    .andExpect(jsonPath("$.lessons", greaterThanOrEqualTo(30)))
                    .andExpect(jsonPath("$.problems", greaterThan(10)))
                    .andExpect(jsonPath("$.quizQuestions", greaterThan(30)))
                    .andExpect(jsonPath("$.contentChecksum", notNullValue()));
        }
    }

    @Nested
    @DisplayName("dataset")
    class Dataset {

        @Test
        void describesEveryTableWithItsRowCount() throws Exception {
            JsonNode tables = getJson("/api/dataset/schema?schema=learning");
            assertThat(tables).hasSizeGreaterThan(10);
            for (JsonNode table : tables) {
                assertThat(table.get("columns")).isNotEmpty();
                assertThat(table.get("rowCount").asInt()).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("a schema outside the allow-list is refused")
        void refusesOtherSchemas() throws Exception {
            mvc.perform(get("/api/dataset/schema?schema=app"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SCHEMA_NOT_ALLOWED"));
        }
    }
}
