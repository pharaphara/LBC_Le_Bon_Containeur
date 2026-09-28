package io.github.pharaphara.lbc.render;

/** Bounded text. An assistant should never have to swallow a corpus. */
public final class Text {

    public static final String ELLIPSIS = "…";

    private Text() {
    }

    /** A slice, and where the next one starts. Null next means the end. */
    public record Chunk(String text, int offset, Integer next, int rest, int total) {
    }

    public static Chunk chunk(String text, int offset, int limit) {
        String s = text == null ? "" : text;
        int total = s.length();
        int from = Math.max(0, Math.min(offset, total));
        int max = Math.max(1, limit);
        String piece = s.substring(from, Math.min(total, from + max));
        int end = from + piece.length();
        return new Chunk(piece, from, end < total ? end : null, total - end, total);
    }

    /** One line, single spaces. Ad titles arrive with newlines in them. */
    public static String squeeze(Object s) {
        return s == null ? "" : String.valueOf(s).replaceAll("\\s+", " ").trim();
    }

    public static String clip(Object s, int limit) {
        String t = squeeze(s);
        return t.length() <= limit ? t : t.substring(0, Math.max(1, limit - 1)) + ELLIPSIS;
    }

    public static String pad(String s, int width, boolean right) {
        String t = s == null ? "" : s;
        if (t.length() > width) {
            t = clip(t, width);
        }
        int gap = width - t.length();
        return right ? " ".repeat(gap) + t : t + " ".repeat(gap);
    }
}
