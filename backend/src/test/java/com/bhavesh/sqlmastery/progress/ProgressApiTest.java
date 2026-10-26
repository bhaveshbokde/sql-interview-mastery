package com.bhavesh.sqlmastery.progress;

import com.bhavesh.sqlmastery.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Progress tracking and the revision schedule, over HTTP and against the real
 * tables.
 *
 * <p>Each test runs under its own learner id and resets it first, so the
 * tests are independent without needing a transactional rollback — which
 * would not work here anyway, since the controller's writes happen in their
 * own transactions.
 */
@IntegrationTest
class ProgressApiTest {

    private static final String LEARNER = "junit";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetLearner() throws Exception {
        mvc.perform(post("/api/progress/reset").header(LearnerId.HEADER, LEARNER))
                .andExpect(status().isOk());
    }

    private ResultActions postAs(String path, String body) throws Exception {
        return mvc.perform(post(path)
                .header(LearnerId.HEADER, LEARNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode getAs(String path) throws Exception {
        return objectMapper.readTree(mvc.perform(get(path).header(LearnerId.HEADER, LEARNER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("lessons")
    class Lessons {

        @Test
        @DisplayName("every lesson appears, including the ones never opened")
        void listsEveryLesson() throws Exception {
            JsonNode lessons = getAs("/api/progress/lessons");
            assertThat(lessons).hasSizeGreaterThan(30);
            assertThat(lessons).allSatisfy(l ->
                    assertThat(l.get("status").asText()).isEqualTo("not_started"));
        }

        @Test
        @DisplayName("viewing a lesson records it and increments the count")
        void recordsViews() throws Exception {
            postAs("/api/progress/lessons/nulls/view", "").andExpect(status().isOk());
            postAs("/api/progress/lessons/nulls/view", "").andExpect(status().isOk());

            JsonNode lesson = findLesson("f-nulls");
            assertThat(lesson.get("status").asText()).isEqualTo("in_progress");
            assertThat(lesson.get("viewCount").asInt()).isEqualTo(2);
            assertThat(lesson.get("lastViewedAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("completing a lesson records the time and schedules a revision")
        void completing() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 4}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("completed"));

            JsonNode lesson = findLesson("f-nulls");
            assertThat(lesson.get("status").asText()).isEqualTo("completed");
            assertThat(lesson.get("confidence").asInt()).isEqualTo(4);
            assertThat(lesson.get("completedAt").isNull()).isFalse();
            // Completing puts it in the queue; otherwise the revision list
            // only ever holds what somebody remembered to add.
            assertThat(lesson.get("revisionDueOn").isNull()).isFalse();
        }

        @Test
        @DisplayName("reopening clears the completion timestamp but keeps the confidence")
        void reopening() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 5}");
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": false}");

            JsonNode lesson = findLesson("f-nulls");
            assertThat(lesson.get("status").asText()).isEqualTo("in_progress");
            assertThat(lesson.get("completedAt").isNull()).isTrue();
            // A null confidence in the request means "not supplied", not
            // "clear it".
            assertThat(lesson.get("confidence").asInt()).isEqualTo(5);
        }

        @Test
        @DisplayName("a lower confidence lowers the item's starting ease")
        void confidenceAffectsTheSchedule() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 1}");
            postAs("/api/progress/lessons/filtering/status", "{\"completed\": true, \"confidence\": 5}");

            // Grade both the same and the one you were unsure of should come
            // back sooner, because its ease started lower.
            JsonNode shaky = objectMapper.readTree(postAs(
                    "/api/progress/revision/lesson/nulls/review", "{\"quality\": 5}")
                    .andReturn().getResponse().getContentAsString());
            JsonNode sure = objectMapper.readTree(postAs(
                    "/api/progress/revision/lesson/filtering/review", "{\"quality\": 5}")
                    .andReturn().getResponse().getContentAsString());

            assertThat(shaky.get("ease").asDouble()).isLessThan(sure.get("ease").asDouble());
        }

        @Test
        void unknownLessonIs404() throws Exception {
            postAs("/api/progress/lessons/not-a-lesson/status", "{\"completed\": true}")
                    .andExpect(status().isNotFound());
        }

        @Test
        void confidenceOutsideOneToFiveIsRejected() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 9}")
                    .andExpect(status().isBadRequest());
        }

        private JsonNode findLesson(String id) throws Exception {
            for (JsonNode l : getAs("/api/progress/lessons")) {
                if (l.get("id").asText().equals(id)) {
                    return l;
                }
            }
            throw new AssertionError("No lesson " + id + " in the progress list");
        }
    }

    @Nested
    @DisplayName("problem attempts")
    class Attempts {

        @Test
        @DisplayName("evaluating a problem records the attempt server-side")
        void evaluationRecordsAnAttempt() throws Exception {
            // Recorded by the evaluate endpoint rather than by the client,
            // so the history is what the server judged rather than what a
            // browser chose to report.
            postAs("/api/problems/second-highest-salary/evaluate",
                    "{\"sql\": \"select 1\"}").andExpect(status().isOk());

            JsonNode attempts = getAs("/api/progress/problems/second-highest-salary/attempts");
            assertThat(attempts).hasSize(1);
            assertThat(attempts.get(0).get("sql").asText()).isEqualTo("select 1");
            assertThat(attempts.get(0).get("correct").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("history is kept, not overwritten")
        void keepsHistory() throws Exception {
            postAs("/api/problems/second-highest-salary/evaluate", "{\"sql\": \"select 1\"}");
            postAs("/api/problems/second-highest-salary/evaluate", "{\"sql\": \"select 2\"}");
            postAs("/api/problems/second-highest-salary/evaluate", "{\"sql\": \"select 3\"}");

            JsonNode attempts = getAs("/api/progress/problems/second-highest-salary/attempts");
            assertThat(attempts).hasSize(3);
            // Newest first.
            assertThat(attempts.get(0).get("sql").asText()).isEqualTo("select 3");
        }

        @Test
        @DisplayName("solved is sticky — a later wrong attempt does not unsolve it")
        void solvedIsSticky() throws Exception {
            String solution = objectMapper.readTree(mvc.perform(
                    get("/api/problems/second-highest-salary/solution"))
                    .andReturn().getResponse().getContentAsString()).get("sql").asText();

            postAs("/api/problems/second-highest-salary/evaluate",
                    objectMapper.writeValueAsString(java.util.Map.of("sql", solution)))
                    .andExpect(jsonPath("$.verdict").value("CORRECT"));
            postAs("/api/problems/second-highest-salary/evaluate", "{\"sql\": \"select 1\"}");

            JsonNode problem = findProblem("p01-second-highest-salary");
            assertThat(problem.get("solved").asBoolean()).isTrue();
            assertThat(problem.get("attempts").asInt()).isEqualTo(2);
            assertThat(problem.get("lastVerdict").asText()).isNotEqualTo("CORRECT");
        }

        @Test
        @DisplayName("solving schedules the problem, and hints lower its starting ease")
        void solvingSchedulesRevision() throws Exception {
            String solution = objectMapper.readTree(mvc.perform(
                    get("/api/problems/customers-who-never-ordered/solution"))
                    .andReturn().getResponse().getContentAsString()).get("sql").asText();

            postAs("/api/problems/customers-who-never-ordered/evaluate",
                    objectMapper.writeValueAsString(
                            java.util.Map.of("sql", solution, "hintsUsed", 2)))
                    .andExpect(jsonPath("$.verdict").value("CORRECT"));

            assertThat(findProblem("p06-customers-who-never-ordered")
                    .get("revisionDueOn").isNull()).isFalse();
        }

        @Test
        @DisplayName("every problem appears, including the untried ones")
        void listsEveryProblem() throws Exception {
            JsonNode problems = getAs("/api/progress/problems");
            assertThat(problems).hasSizeGreaterThan(10);
            assertThat(problems).allSatisfy(p -> {
                assertThat(p.get("attempts").asInt()).isZero();
                assertThat(p.get("solved").asBoolean()).isFalse();
            });
        }

        private JsonNode findProblem(String id) throws Exception {
            for (JsonNode p : getAs("/api/progress/problems")) {
                if (p.get("id").asText().equals(id)) {
                    return p;
                }
            }
            throw new AssertionError("No problem " + id + " in the progress list");
        }
    }

    @Nested
    @DisplayName("revision")
    class Revision {

        @Test
        @DisplayName("a lapse resets the interval and a pass grows it")
        void schedulingBehaviour() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 5}");

            JsonNode first = review("lesson", "nulls", 4);
            assertThat(first.get("nextIntervalDays").asInt()).isEqualTo(1);

            JsonNode second = review("lesson", "nulls", 4);
            assertThat(second.get("nextIntervalDays").asInt()).isEqualTo(6);

            JsonNode third = review("lesson", "nulls", 4);
            assertThat(third.get("nextIntervalDays").asInt()).isGreaterThan(6);

            JsonNode lapsed = review("lesson", "nulls", 1);
            assertThat(lapsed.get("nextIntervalDays").asInt()).isEqualTo(1);
            assertThat(lapsed.get("lapses").asInt()).isEqualTo(1);
            assertThat(lapsed.get("reviews").asInt()).isZero();
        }

        @Test
        @DisplayName("the due list is empty when everything is scheduled for the future")
        void dueList() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 3}");
            // Scheduled for tomorrow, so nothing is due today.
            assertThat(getAs("/api/progress/revision/due")).isEmpty();
        }

        @Test
        void unknownKindIs404() throws Exception {
            postAs("/api/progress/revision/nonsense/nulls/review", "{\"quality\": 3}")
                    .andExpect(status().isNotFound());
        }

        @Test
        void qualityOutsideZeroToFiveIsRejected() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true}");
            postAs("/api/progress/revision/lesson/nulls/review", "{\"quality\": 7}")
                    .andExpect(status().isBadRequest());
        }

        private JsonNode review(String kind, String item, int quality) throws Exception {
            return objectMapper.readTree(postAs(
                    "/api/progress/revision/" + kind + "/" + item + "/review",
                    "{\"quality\": " + quality + "}")
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
        }
    }

    @Nested
    @DisplayName("the dashboard")
    class Overview {

        @Test
        @DisplayName("counts lessons, problems, quiz answers and what is due")
        void counts() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true, \"confidence\": 4}");
            postAs("/api/problems/second-highest-salary/evaluate", "{\"sql\": \"select 1\"}");
            postAs("/api/quiz/submit", "{\"answers\": {\"q-null-not-in\": 0}}");

            JsonNode overview = getAs("/api/progress");
            assertThat(overview.get("learnerId").asText()).isEqualTo(LEARNER);
            assertThat(overview.get("lessonsCompleted").asInt()).isEqualTo(1);
            assertThat(overview.get("problemAttempts").asInt()).isEqualTo(1);
            assertThat(overview.get("quizAnswered").asInt()).isEqualTo(1);
            assertThat(overview.get("quizCorrect").asInt()).isEqualTo(1);
            assertThat(overview.get("minutesCompleted").asInt()).isPositive();
        }

        @Test
        @DisplayName("the activity series includes the days with nothing on them")
        void activityIncludesEmptyDays() throws Exception {
            // generate_series supplies the empty days; GROUP BY cannot invent
            // them, and a sparkline missing them misrepresents the week.
            JsonNode activity = getAs("/api/progress").get("recentActivity");
            assertThat(activity).hasSize(14);
            assertThat(activity).allSatisfy(d -> assertThat(d.get("day").asText()).isNotBlank());
        }

        @Test
        @DisplayName("per-module progress covers every module")
        void perModuleProgress() throws Exception {
            JsonNode modules = getAs("/api/progress").get("modules");
            assertThat(modules).hasSize(9);
            assertThat(modules).allSatisfy(m ->
                    assertThat(m.get("lessons").asInt()).isPositive());
        }

        @Test
        @DisplayName("quiz accuracy is reported per topic, weakest first")
        void quizAccuracy() throws Exception {
            postAs("/api/quiz/submit",
                    "{\"answers\": {\"q-null-not-in\": 0, \"q-null-aggregates\": 3}}");
            JsonNode accuracy = getAs("/api/progress/quiz");
            assertThat(accuracy).isNotEmpty();
            assertThat(accuracy.get(0).get("topic").asText()).isEqualTo("NULL handling");
            assertThat(accuracy.get(0).get("answered").asInt()).isEqualTo(2);
            assertThat(accuracy.get(0).get("correct").asInt()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("learner isolation")
    class Isolation {

        @Test
        @DisplayName("two learner ids do not see each other's progress")
        void learnersAreSeparate() throws Exception {
            postAs("/api/progress/lessons/nulls/status", "{\"completed\": true}");

            JsonNode other = objectMapper.readTree(mvc.perform(get("/api/progress")
                    .header(LearnerId.HEADER, "someone-else"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(other.get("lessonsCompleted").asInt()).isZero();

            assertThat(getAs("/api/progress").get("lessonsCompleted").asInt()).isEqualTo(1);

            // Clean up the second learner so the test leaves nothing behind.
            mvc.perform(post("/api/progress/reset").header(LearnerId.HEADER, "someone-else"));
        }

        @Test
        @DisplayName("a missing header means the default profile")
        void defaultsToLocal() throws Exception {
            JsonNode overview = objectMapper.readTree(mvc.perform(get("/api/progress"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(overview.get("learnerId").asText()).isEqualTo(LearnerId.DEFAULT);
        }

        @Test
        @DisplayName("a hostile learner id is normalised rather than rejected")
        void normalisesTheHeader() throws Exception {
            assertThat(LearnerId.normalise("  MiXeD-Case_1  ")).isEqualTo("mixed-case_1");
            // Hyphen and underscore survive because they are legitimate in an
            // id; quotes, semicolons, spaces and dots do not. The value is a
            // bind parameter in every query regardless, so this is about
            // keeping the data tidy rather than about injection.
            assertThat(LearnerId.normalise("'; drop table app.lesson; --"))
                    .isEqualTo("droptableapplesson--");
            assertThat(LearnerId.normalise("!!!")).isEqualTo(LearnerId.DEFAULT);
            assertThat(LearnerId.normalise(null)).isEqualTo(LearnerId.DEFAULT);
            assertThat(LearnerId.normalise("x".repeat(500))).hasSize(64);
        }
    }

    @Test
    @DisplayName("reset removes this learner's progress and nothing else")
    void resetIsScoped() throws Exception {
        postAs("/api/progress/lessons/nulls/status", "{\"completed\": true}");
        postAs("/api/problems/second-highest-salary/evaluate", "{\"sql\": \"select 1\"}");

        mvc.perform(post("/api/progress/reset").header(LearnerId.HEADER, LEARNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learnerId").value(LEARNER));

        JsonNode overview = getAs("/api/progress");
        assertThat(overview.get("lessonsCompleted").asInt()).isZero();
        assertThat(overview.get("problemAttempts").asInt()).isZero();
        // The curriculum itself is untouched — it is rebuilt from files and
        // has nothing to do with progress.
        assertThat(overview.get("totalLessons").asInt()).isGreaterThan(30);
    }
}
