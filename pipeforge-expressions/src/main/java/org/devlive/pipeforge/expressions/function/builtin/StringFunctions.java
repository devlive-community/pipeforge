/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.expressions.function.builtin;

import com.google.re2j.Matcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import org.devlive.pipeforge.api.function.Returns;
import org.devlive.pipeforge.api.function.ScalarFunction;
import org.devlive.pipeforge.expressions.runtime.ExprError;
import org.devlive.pipeforge.expressions.runtime.R;

/**
 * String functions. Positions and lengths count characters (Unicode code points) and positions
 * start at 1.
 */
public final class StringFunctions {

    /** Upper bound of strings built by REPEAT and the padding functions. */
    static final int MAX_LENGTH = 1 << 20;

    private StringFunctions() {}

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Index into {@code s} of the code point at 0-based position {@code points}, clamped to the end. */
    private static int offset(String s, long points) {
        int total = length(s);
        return s.offsetByCodePoints(0, (int) Math.min(points, total));
    }

    // ------------------------------------------------------------------ length and case

    @ScalarFunction(name = "LENGTH")
    public static int length(byte[] value) {
        return value.length;
    }

    /** Number of characters. */
    @ScalarFunction(name = "LENGTH")
    public static int lengthOf(String value) {
        return length(value);
    }

    @ScalarFunction(name = "CHAR_LENGTH")
    public static int charLength(String value) {
        return length(value);
    }

    /** Number of bytes in UTF-8. */
    @ScalarFunction(name = "OCTET_LENGTH")
    public static int octetLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @ScalarFunction(name = "LOWER")
    public static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    @ScalarFunction(name = "UPPER")
    public static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    /** Upper-cases the first letter of every word and lower-cases the rest. */
    @ScalarFunction(name = "INITCAP")
    public static String initcap(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean start = true;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            out.appendCodePoint(start ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
            start = !Character.isLetterOrDigit(cp);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ trimming and padding

    @ScalarFunction(name = "TRIM")
    public static String trim(String value) {
        return value.strip();
    }

    /** Removes the characters in {@code characters} from both ends. */
    @ScalarFunction(name = "TRIM")
    public static String trim(String value, String characters) {
        return rtrim(ltrim(value, characters), characters);
    }

    @ScalarFunction(name = "LTRIM")
    public static String ltrim(String value) {
        return value.stripLeading();
    }

    @ScalarFunction(name = "LTRIM")
    public static String ltrim(String value, String characters) {
        int i = 0;
        while (i < value.length()) {
            int cp = value.codePointAt(i);
            if (characters.indexOf(cp) < 0) {
                break;
            }
            i += Character.charCount(cp);
        }
        return value.substring(i);
    }

    @ScalarFunction(name = "RTRIM")
    public static String rtrim(String value) {
        return value.stripTrailing();
    }

    @ScalarFunction(name = "RTRIM")
    public static String rtrim(String value, String characters) {
        int end = value.length();
        while (end > 0) {
            int cp = value.codePointBefore(end);
            if (characters.indexOf(cp) < 0) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return value.substring(0, end);
    }

    @ScalarFunction(name = "LPAD")
    public static String lpad(String value, int length) {
        return lpad(value, length, " ");
    }

    /** Pads on the left with {@code pad} to {@code length} characters; longer values are cut. */
    @ScalarFunction(name = "LPAD")
    public static String lpad(String value, int length, String pad) {
        return pad(value, length, pad, true);
    }

    @ScalarFunction(name = "RPAD")
    public static String rpad(String value, int length) {
        return rpad(value, length, " ");
    }

    @ScalarFunction(name = "RPAD")
    public static String rpad(String value, int length, String pad) {
        return pad(value, length, pad, false);
    }

    private static String pad(String value, int length, String pad, boolean left) {
        if (length < 0 || length > MAX_LENGTH) {
            throw new ExprError("the padded length must be between 0 and " + MAX_LENGTH + ", got " + length);
        }
        int current = length(value);
        if (current >= length) {
            return value.substring(0, offset(value, length));
        }
        if (pad.isEmpty()) {
            return value;
        }
        StringBuilder filler = new StringBuilder();
        while (length(filler.toString()) < length - current) {
            filler.append(pad);
        }
        String fill = filler.substring(0, offset(filler.toString(), length - current));
        return left ? fill + value : value + fill;
    }

    // ------------------------------------------------------------------ substrings

    @ScalarFunction(name = "SUBSTRING")
    public static String substring(String value, long start) {
        return substring(value, start, Integer.MAX_VALUE);
    }

    /** {@code length} characters from position {@code start} (1-based). */
    @ScalarFunction(name = "SUBSTRING")
    public static String substring(String value, long start, long length) {
        if (start < 1) {
            throw new ExprError("SUBSTRING start must be at least 1, got " + start);
        }
        if (length < 0) {
            throw new ExprError("SUBSTRING length must not be negative, got " + length);
        }
        int from = offset(value, start - 1);
        int to = offset(value, Math.min(Integer.MAX_VALUE, start - 1 + length));
        return value.substring(from, to);
    }

    @ScalarFunction(name = "LEFT")
    public static String left(String value, int count) {
        if (count < 0) {
            throw new ExprError("LEFT needs a non-negative length, got " + count);
        }
        return value.substring(0, offset(value, count));
    }

    @ScalarFunction(name = "RIGHT")
    public static String right(String value, int count) {
        if (count < 0) {
            throw new ExprError("RIGHT needs a non-negative length, got " + count);
        }
        int total = length(value);
        return value.substring(offset(value, Math.max(0, total - count)));
    }

    /** Position (1-based) of the first occurrence of {@code part} in {@code value}, 0 if absent. */
    @ScalarFunction(name = "POSITION")
    public static int position(String part, String value) {
        int index = value.indexOf(part);
        return index < 0 ? 0 : value.codePointCount(0, index) + 1;
    }

    @ScalarFunction(name = "INSTR")
    public static int instr(String value, String part) {
        return position(part, value);
    }

    // ------------------------------------------------------------------ replacing

    @ScalarFunction(name = "REPLACE")
    public static String replace(String value, String search, String replacement) {
        return search.isEmpty() ? value : value.replace(search, replacement);
    }

    /** Replaces each character of {@code from} with the character at the same position in {@code to}; extra characters of {@code from} are removed. */
    @ScalarFunction(name = "TRANSLATE")
    public static String translate(String value, String from, String to) {
        int[] source = from.codePoints().toArray();
        int[] target = to.codePoints().toArray();
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            int index = -1;
            for (int i = 0; i < source.length; i++) {
                if (source[i] == cp) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                out.appendCodePoint(cp);
            } else if (index < target.length) {
                out.appendCodePoint(target[index]);
            }
        });
        return out.toString();
    }

    @ScalarFunction(name = "REVERSE")
    public static String reverse(String value) {
        int[] points = value.codePoints().toArray();
        StringBuilder out = new StringBuilder(value.length());
        for (int i = points.length - 1; i >= 0; i--) {
            out.appendCodePoint(points[i]);
        }
        return out.toString();
    }

    @ScalarFunction(name = "REPEAT")
    public static String repeat(String value, int count) {
        if (count < 0) {
            throw new ExprError("REPEAT needs a non-negative count, got " + count);
        }
        if ((long) value.length() * count > MAX_LENGTH) {
            throw new ExprError("REPEAT would build more than " + MAX_LENGTH + " characters");
        }
        return value.repeat(count);
    }

    // ------------------------------------------------------------------ concatenation and splitting

    @ScalarFunction(name = "CONCAT")
    public static String concat(String... values) {
        return String.join("", values);
    }

    /** Joins the non-NULL values with {@code separator}; NULL values are skipped. */
    @ScalarFunction(name = "CONCAT_WS", nullOnNull = false)
    public static String concatWs(String separator, String... values) {
        if (separator == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                parts.add(value);
            }
        }
        return String.join(separator, parts);
    }

    /** Part {@code index} (1-based) of {@code value} split at {@code delimiter}; '' when there is no such part. */
    @ScalarFunction(name = "SPLIT_PART")
    public static String splitPart(String value, String delimiter, int index) {
        if (index < 1) {
            throw new ExprError("SPLIT_PART index must be at least 1, got " + index);
        }
        List<String> parts = split(value, delimiter);
        return index <= parts.size() ? parts.get(index - 1) : "";
    }

    /** Splits at every occurrence of {@code delimiter} (taken literally). */
    @Returns("ARRAY<STRING>")
    @ScalarFunction(name = "SPLIT")
    public static List<String> split(String value, String delimiter) {
        List<String> parts = new ArrayList<>();
        if (delimiter.isEmpty()) {
            value.codePoints().forEach(cp -> parts.add(new String(Character.toChars(cp))));
            return Collections.unmodifiableList(parts);
        }
        int start = 0;
        int index;
        while ((index = value.indexOf(delimiter, start)) >= 0) {
            parts.add(value.substring(start, index));
            start = index + delimiter.length();
        }
        parts.add(value.substring(start));
        return Collections.unmodifiableList(parts);
    }

    // ------------------------------------------------------------------ regular expressions (RE2)

    @ScalarFunction(name = "REGEXP_LIKE")
    public static boolean regexpLike(String value, String regex) {
        return R.regex(regex).matcher(value).find();
    }

    @ScalarFunction(name = "REGEXP_EXTRACT")
    public static String regexpExtract(String value, String regex) {
        return regexpExtract(value, regex, 0);
    }

    /** Group {@code group} of the first match (0 = the whole match), NULL when nothing matches. */
    @ScalarFunction(name = "REGEXP_EXTRACT")
    public static String regexpExtract(String value, String regex, int group) {
        Matcher matcher = R.regex(regex).matcher(value);
        if (group < 0 || group > matcher.groupCount()) {
            throw new ExprError("the regular expression has no group " + group);
        }
        return matcher.find() ? matcher.group(group) : null;
    }

    /** Replaces every match; {@code $1} in the replacement refers to group 1. */
    @ScalarFunction(name = "REGEXP_REPLACE")
    public static String regexpReplace(String value, String regex, String replacement) {
        try {
            return R.regex(regex).matcher(value).replaceAll(replacement);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new ExprError("invalid replacement '" + replacement + "': " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ characters and formatting

    /** Code point of the first character; 0 for ''. */
    @ScalarFunction(name = "ASCII")
    public static int ascii(String value) {
        return value.isEmpty() ? 0 : value.codePointAt(0);
    }

    @ScalarFunction(name = "CHR")
    public static String chr(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            throw new ExprError("CHR needs a valid code point, got " + codePoint);
        }
        return new String(Character.toChars(codePoint));
    }

    /** {@code printf}-style formatting: FORMAT('%s has %d items', name, n). */
    public static String format(String pattern, Object... values) {
        if (pattern == null) {
            return null;
        }
        try {
            return String.format(Locale.ROOT, pattern, values);
        } catch (IllegalFormatException e) {
            throw new ExprError("invalid FORMAT pattern '" + pattern + "': " + e.getMessage());
        }
    }
}
