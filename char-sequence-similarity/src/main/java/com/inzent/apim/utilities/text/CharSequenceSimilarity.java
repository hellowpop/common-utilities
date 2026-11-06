package com.inzent.apim.utilities.text;

import java.util.Objects;

/** Rule-based, symmetric similarity scores for character sequences. */
public final class CharSequenceSimilarity {
    private CharSequenceSimilarity() {
    }

    /**
     * Applies exact (100), case-insensitive (99), snake/kebab (98), edge
     * decoration (97), then internal whitespace-ratio matching, in that order.
     * Other differences score zero. Case-insensitive matching follows
     * {@link String#equalsIgnoreCase(String)}.
     *
     * <p>The whitespace score is {@code 100 - 100 * max(whitespace counts)
     * / max(lengths)}, after trimming edge whitespace, punctuation and symbols.
     * Counts use UTF-16 chars. Whitespace includes {@link Character#isWhitespace(char)}
     * and {@link Character#isSpaceChar(char)}. Mutable inputs must not change
     * during this call.
     *
     * @param origin first input
     * @param ref second input
     * @return similarity from 0 to 100, inclusive
     * @throws NullPointerException if either input is null
     */
    public static float compare(CharSequence origin, CharSequence ref) {
        String left = Objects.requireNonNull(origin, "origin").toString();
        String right = Objects.requireNonNull(ref, "ref").toString();
        if (left.equals(right)) {
            return 100.0f;
        }
        if (left.equalsIgnoreCase(right)) {
            return 99.0f;
        }
        String leftIdentifier = normalizeIdentifier(left);
        String rightIdentifier = normalizeIdentifier(right);
        if (leftIdentifier != null && rightIdentifier != null
                && leftIdentifier.equalsIgnoreCase(rightIdentifier)) {
            return 98.0f;
        }

        left = trimDecoration(left);
        right = trimDecoration(right);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0f;
        }
        if (left.equalsIgnoreCase(right)) {
            return 97.0f;
        }

        String leftContent = withoutWhitespace(left);
        String rightContent = withoutWhitespace(right);
        if (!leftContent.isEmpty() && leftContent.equalsIgnoreCase(rightContent)) {
            int whitespace = Math.max(left.length() - leftContent.length(),
                    right.length() - rightContent.length());
            return 100.0f - 100.0f * whitespace / Math.max(left.length(), right.length());
        }
        return 0.0f;
    }

    private static String normalizeIdentifier(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean needsWord = true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '_' || codePoint == '-') {
                if (needsWord) {
                    return null;
                }
                needsWord = true;
            } else if (Character.isLetterOrDigit(codePoint)
                    || Character.getType(codePoint) == Character.LETTER_NUMBER
                    || Character.getType(codePoint) == Character.OTHER_NUMBER) {
                result.appendCodePoint(codePoint);
                needsWord = false;
            } else {
                return null;
            }
        }
        return needsWord ? null : result.toString();
    }

    private static String trimDecoration(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isDecoration(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (start < end && isDecoration(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean isDecoration(int codePoint) {
        if (isWhitespace(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private static String withoutWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!isWhitespace(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
