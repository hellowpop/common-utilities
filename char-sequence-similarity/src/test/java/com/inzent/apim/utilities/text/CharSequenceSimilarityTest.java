package com.inzent.apim.utilities.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.CharBuffer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CharSequenceSimilarityTest {
    @ParameterizedTest(name = "{index}: [{0}] / [{1}] => {2}")
    @MethodSource("comparisons")
    void comparesInRuleOrderAndSymmetrically(String origin, String ref, float expected) {
        assertEquals(expected, CharSequenceSimilarity.compare(origin, ref), 0.0001f);
        assertEquals(expected, CharSequenceSimilarity.compare(ref, origin), 0.0001f);
    }

    static Stream<Arguments> comparisons() {
        return Stream.of(
                Arguments.of("hello", "hello", 100f),
                Arguments.of("", "", 100f),
                Arguments.of(" \t\r\n", " \t\r\n", 100f),
                Arguments.of("a b", "a b", 100f),
                Arguments.of("HELLO", "hello", 99f),
                Arguments.of("HELLO_WORLD", "hello_world", 99f),
                Arguments.of("A B", "a b", 99f),
                Arguments.of("hello_world", "hello-world", 98f),
                Arguments.of("helloWorld", "hello_world", 98f),
                Arguments.of("hello-world", "HELLOWORLD", 98f),
                Arguments.of("api_v2_name", "apiV2Name", 98f),
                Arguments.of(" \t[hello!]\r\n", "hello", 97f),
                Arguments.of("___HELLO---", "hello", 97f),
                Arguments.of("😀hello😀", "hello", 97f),
                Arguments.of(" a b ", "a b", 97f),
                Arguments.of("a bc", "abc", 75f),
                Arguments.of("a\tb\rc\nd", "abcd", 100f - 300f / 7f),
                Arguments.of("a\fb", "ab", 100f - 100f / 3f),
                Arguments.of("a\u00a0b", "ab", 100f - 100f / 3f),
                Arguments.of("a\u2003b", "ab", 100f - 100f / 3f),
                Arguments.of("a bc", "ab c", 75f),
                Arguments.of("a  b", "a b", 50f),
                Arguments.of("[A\tB!]", "ab", 100f - 100f / 3f),
                Arguments.of("a_b c", "ab c", 0f),
                Arguments.of("a.b", "ab", 0f),
                Arguments.of("abc", "acb", 0f),
                Arguments.of("abc", "abcd", 0f),
                Arguments.of("", "abc", 0f),
                Arguments.of(" \t", "", 0f),
                Arguments.of("!!!", "???", 0f),
                Arguments.of("a\u0000b", "ab", 0f),
                Arguments.of("한글_이름", "한글-이름", 98f));
    }

    @Test
    void acceptsDifferentCharSequenceImplementations() {
        assertEquals(100f, CharSequenceSimilarity.compare(
                new StringBuilder("hello"), CharBuffer.wrap("hello")));
        assertEquals(99f, CharSequenceSimilarity.compare(
                new StringBuffer("HELLO"), new StringBuilder("hello")));
    }

    @Test
    void handlesLongSnakeAndKebabNamesWithoutStackOverflow() {
        assertEquals(98f, CharSequenceSimilarity.compare(
                "a_".repeat(10_000) + "b", "a-".repeat(10_000) + "b"));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> CharSequenceSimilarity.compare(null, "a"));
        assertThrows(NullPointerException.class, () -> CharSequenceSimilarity.compare("a", null));
        assertThrows(NullPointerException.class, () -> CharSequenceSimilarity.compare(null, null));
    }
}
