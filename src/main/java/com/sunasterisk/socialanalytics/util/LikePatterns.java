package com.sunasterisk.socialanalytics.util;

/**
 * Builds LIKE patterns from user input. {@code %} and {@code _} in the input are escaped so they match literally
 * ({@code q=%} must not match every row). The escape character is {@code !}, not {@code \}, because a backslash
 * inside a MySQL string literal is itself an escape. Queries must declare {@code escape '!'}.
 */
public final class LikePatterns {

    public static final char ESCAPE_CHAR = '!';

    private LikePatterns() {
    }

    /** Pattern matching values that contain {@code term} literally. */
    public static String contains(String term) {
        String escaped = term
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
