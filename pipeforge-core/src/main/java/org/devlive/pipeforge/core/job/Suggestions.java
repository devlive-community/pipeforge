/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.core.job;

import java.util.Collection;
import java.util.Optional;

/** "Did you mean" suggestions for misspelt keys and ids. */
public final class Suggestions {

    private Suggestions() {}

    /** The candidate closest to {@code text} within an edit distance of 2, ignoring case. */
    public static Optional<String> closest(String text, Collection<String> candidates) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : candidates) {
            int distance =
                    distance(text.toLowerCase(java.util.Locale.ROOT), candidate.toLowerCase(java.util.Locale.ROOT));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code reason} followed by "; did you mean 'x'?" when a candidate is close. */
    public static String withSuggestion(String reason, String text, Collection<String> candidates) {
        return closest(text, candidates)
                .map(s -> reason + "; did you mean '" + s + "'?")
                .orElse(reason);
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
