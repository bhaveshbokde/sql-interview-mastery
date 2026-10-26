package com.bhavesh.sqlmastery.error;

import com.bhavesh.sqlmastery.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the API says when the request is wrong.
 *
 * <p>These exist because of a real defect: the catch-all handler in
 * {@link GlobalExceptionHandler} sat on Spring's own exceptions, so an unknown
 * URL and a truncated JSON body both answered 500 INTERNAL_ERROR and both were
 * logged with a stack trace. A 500 means "this server is broken"; a caller who
 * sees one for a mistyped path will go looking in the wrong place, and an
 * operator watching the error log will see noise instead of outages.
 *
 * <p>The second thing each test checks is what is <em>not</em> in the body. A
 * handler that fixes the status but starts echoing Jackson's parse message, a
 * stack frame or a package name has replaced one bug with a worse one.
 */
@IntegrationTest
class ErrorResponseTest {

    @Autowired
    private MockMvc mvc;

    @Nested
    @DisplayName("a request that cannot be routed")
    class Routing {

        @Test
        @DisplayName("an unknown path is 404, not 500")
        void unknownPath() throws Exception {
            mvc.perform(get("/api/does-not-exist"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("an unknown path under a real prefix is also 404")
        void unknownPathUnderRealPrefix() throws Exception {
            mvc.perform(post("/api/playground/nope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("the wrong method on a real path is 405")
        void wrongMethod() throws Exception {
            mvc.perform(delete("/api/playground/limits"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }

    @Nested
    @DisplayName("a request that cannot be read")
    class Body {

        @Test
        @DisplayName("truncated JSON is 400, not 500")
        void malformedJson() throws Exception {
            mvc.perform(post("/api/playground/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        @DisplayName("the parse error does not echo the body back")
        void malformedJsonDoesNotEchoInput() throws Exception {
            // If Jackson's message were passed through, this marker would come
            // back in the response — and so would anything else a caller sent.
            mvc.perform(post("/api/playground/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sql\": \"ECHO-MARKER-7f3a\" ,,,"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("ECHO-MARKER-7f3a"))));
        }

        @Test
        @DisplayName("a non-JSON content type is 415")
        void wrongContentType() throws Exception {
            mvc.perform(post("/api/playground/query")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("select 1"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
    }

    @Nested
    @DisplayName("no error response leaks internals")
    class NoLeaks {

        @Test
        @DisplayName("not a stack trace, a package name or a class name")
        void noInternals() throws Exception {
            String[] bodies = {
                mvc.perform(get("/api/does-not-exist"))
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(post("/api/playground/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sql\":"))
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(delete("/api/playground/limits"))
                        .andReturn().getResponse().getContentAsString(),
            };
            for (String body : bodies) {
                org.assertj.core.api.Assertions.assertThat(body)
                        .doesNotContain("com.bhavesh")
                        .doesNotContain("org.springframework")
                        .doesNotContain("com.fasterxml")
                        .doesNotContain("Exception")
                        .doesNotContain("\tat ")
                        .doesNotContain("jdbc:postgresql");
            }
        }
    }
}
