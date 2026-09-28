package io.github.pharaphara.lbc;

/**
 * Everything that can go wrong, with a name and a way out.
 *
 * <p>One exception type on purpose. A tool needs to answer an assistant with a
 * state it can act on, not with a stack trace: a wall means "a human must click
 * once", a stale key means "the tool is broken, do not retry", an unverified
 * category means "measure it first". Each kind carries the advice that goes
 * with it.
 */
public class LbcException extends RuntimeException {

    public enum Kind {
        /** An anti robot wall. A human passes it once in the browser. */
        WALL,
        /** The site's public web key changed. Nothing to work around. */
        STALE_KEY,
        /** The page no longer exposes what we read. Never silently empty. */
        UNKNOWN_STRUCTURE,
        /** Too many calls. Back off once, then stop. */
        RATE_LIMIT,
        /** A category id nobody measured. A wrong one drops every ad in silence. */
        UNVERIFIED_CATEGORY,
        /** No such search, or no such ad number. */
        NOT_FOUND,
        /** The data directory is not writable. Do not write somewhere else. */
        DISK,
        /** The browser is unreachable. */
        BROWSER
    }

    private final Kind kind;
    private final String advice;

    public LbcException(Kind kind, String message) {
        this(kind, message, null);
    }

    public LbcException(Kind kind, String message, String advice) {
        super(message);
        this.kind = kind;
        this.advice = advice;
    }

    public Kind kind() {
        return kind;
    }

    /** What the caller should do about it, or null when the message says it all. */
    public String advice() {
        return advice;
    }

    /** The lowercase name an assistant sees, for example "unverified_category". */
    public String state() {
        return kind.name().toLowerCase();
    }
}
