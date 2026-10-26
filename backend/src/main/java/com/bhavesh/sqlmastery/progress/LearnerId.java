package com.bhavesh.sqlmastery.progress;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves which learner a request belongs to.
 *
 * <p>There is no authentication in this application, so the answer comes from
 * an {@code X-Learner-Id} header and defaults to {@code local}. That is a
 * bounded decision rather than a gap: this is a single-person study tool bound
 * to localhost, and the header exists so one person can keep two separate runs
 * through the course rather than to separate two people.
 *
 * <p>It is explicitly <b>not</b> a credential. Anyone who can reach the API can
 * supply any learner id and read or write that learner's progress. Saying so
 * here, in the class that produces the value, is more useful than saying it in
 * a document nobody opens — and it is the thing that would have to change
 * first if this were ever exposed beyond a laptop.
 *
 * <p>The value is normalised and bounded before it reaches SQL. It is always
 * passed as a bind parameter, so the sanitising is about keeping the data tidy
 * rather than about injection, but an unbounded client-supplied string
 * becoming a primary-key component is worth constraining on its own merits.
 */
public final class LearnerId {

    /** The id used when the header is absent, which is almost always. */
    public static final String DEFAULT = "local";

    private static final int MAX_LENGTH = 64;
    public static final String HEADER = "X-Learner-Id";

    private LearnerId() {
    }

    /** @return the header's value, normalised, or {@code local} */
    public static String from(HttpServletRequest request) {
        return normalise(request == null ? null : request.getHeader(HEADER));
    }

    /**
     * Lower-cases, trims, truncates and strips anything outside
     * {@code [a-z0-9_-]}.
     *
     * <p>An id that normalises to nothing — all punctuation, say — becomes the
     * default rather than an error. Refusing a request because of a malformed
     * optional header would be a worse outcome than attributing it to the
     * default profile.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        String cleaned = trimmed.replaceAll("[^a-z0-9_-]", "");
        return cleaned.isEmpty() ? DEFAULT : cleaned;
    }
}
