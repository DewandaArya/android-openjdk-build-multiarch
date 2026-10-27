package com.dewanteam.textcipher;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageFormatter {
    private static final int CODE_BACKGROUND = 0xFF202832;
    private static final int CODE_FOREGROUND = 0xFFE6EDF3;

    private static final Pattern BLOCK = Pattern.compile("(?s)\\x60\\x60\\x60[^\\r\\n\\x60]*\\r?\\n(.*?)\\x60\\x60\\x60");
    private static final Pattern INLINE_CODE = Pattern.compile("(?<!\\x60)\\x60([^\\r\\n\\x60]+)\\x60(?!\\x60)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern UNDERLINE = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern STAR_ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern UNDERSCORE_ITALIC = Pattern.compile("(?<!\\w)_(.+?)_(?!\\w)");

    private MessageFormatter() {
    }

    public static CharSequence format(String message) {
        SpannableStringBuilder text = new SpannableStringBuilder(message == null ? "" : message);
        replaceCodeBlocks(text);
        replaceDelimited(text, INLINE_CODE, new Object[]{new TypefaceSpan("monospace"), new ForegroundColorSpan(CODE_FOREGROUND), new BackgroundColorSpan(CODE_BACKGROUND)}, true);
        replaceDelimited(text, BOLD, new Object[]{new StyleSpan(Typeface.BOLD)}, false);
        replaceDelimited(text, UNDERLINE, new Object[]{new UnderlineSpan()}, false);
        replaceDelimited(text, STRIKE, new Object[]{new StrikethroughSpan()}, false);
        replaceDelimited(text, STAR_ITALIC, new Object[]{new StyleSpan(Typeface.ITALIC)}, false);
        replaceDelimited(text, UNDERSCORE_ITALIC, new Object[]{new StyleSpan(Typeface.ITALIC)}, false);
        return text;
    }

    private static void replaceCodeBlocks(SpannableStringBuilder text) {
        Matcher matcher = BLOCK.matcher(text);
        List<Range> matches = new ArrayList<>();
        while (matcher.find()) matches.add(new Range(matcher.start(), matcher.end(), matcher.group(1)));
        Collections.reverse(matches);

        for (Range match : matches) {
            text.replace(match.start, match.end, match.value);
            int end = match.start + match.value.length();
            text.setSpan(new CodeRange(), match.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new TypefaceSpan("monospace"), match.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(CODE_FOREGROUND), match.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new BackgroundColorSpan(CODE_BACKGROUND), match.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void replaceDelimited(SpannableStringBuilder text, Pattern pattern, Object[] styles, boolean code) {
        Matcher matcher = pattern.matcher(text);
        List<Range> matches = new ArrayList<>();
        while (matcher.find()) {
            if (text.getSpans(matcher.start(), matcher.end(), CodeRange.class).length == 0) {
                matches.add(new Range(matcher.start(), matcher.end(), matcher.group(1)));
            }
        }
        Collections.reverse(matches);

        for (Range match : matches) {
            text.replace(match.start, match.end, match.value);
            int end = match.start + match.value.length();
            for (Object style : styles) {
                text.setSpan(copyStyle(style), match.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (code) text.setSpan(new CodeRange(), match.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static Object copyStyle(Object style) {
        if (style instanceof TypefaceSpan) return new TypefaceSpan("monospace");
        if (style instanceof ForegroundColorSpan) return new ForegroundColorSpan(((ForegroundColorSpan) style).getForegroundColor());
        if (style instanceof BackgroundColorSpan) return new BackgroundColorSpan(((BackgroundColorSpan) style).getBackgroundColor());
        if (style instanceof StyleSpan) return new StyleSpan(((StyleSpan) style).getStyle());
        if (style instanceof UnderlineSpan) return new UnderlineSpan();
        if (style instanceof StrikethroughSpan) return new StrikethroughSpan();
        return style;
    }

    private static final class CodeRange {
    }

    private static final class Range {
        final int start;
        final int end;
        final String value;

        Range(int start, int end, String value) {
            this.start = start;
            this.end = end;
            this.value = value;
        }
    }
}
