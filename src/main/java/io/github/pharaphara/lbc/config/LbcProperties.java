package io.github.pharaphara.lbc.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything an operator can change, and nothing else.
 *
 * @param profile    browser profile directory. The session lives here, so a manual
 *                   sign in only ever happens once.
 * @param data       where searches are stored, one folder per search.
 * @param headless   false by default: a human must be able to see the page to pass
 *                   a check, and that needs a display.
 * @param cdpUrl     attach to a browser that is already running instead of starting
 *                   one. Bring your own browser, and its session.
 * @param vncUrl     what to tell a human when a wall needs a click.
 * @param chromeArgs extra browser arguments, for the rare host that needs one.
 * @param maxAds     hard stop on ads per search.
 * @param autonomy   read, reply or full. Only read does anything today, and the rest
 *                   is the messaging work still to come.
 * @param pace       how gently to ask. See {@link Pace}.
 */
@ConfigurationProperties("lbc")
public record LbcProperties(
        Path profile,
        Path data,
        boolean headless,
        String cdpUrl,
        String vncUrl,
        List<String> chromeArgs,
        int maxAds,
        String autonomy,
        Pace pace) {

    /**
     * How gently to ask.
     *
     * <p>These are the numbers that decide whether the site treats you as a visitor
     * or as a nuisance, and they were not guessed: a browser that has been reading
     * this kind of site daily for months settles around one to three seconds between
     * requests, and slower still between full page renders. Going faster does not
     * collect more, it collects for less time, because a restricted address collects
     * nothing at all.
     *
     * @param callMinMs        shortest pause between two data calls
     * @param callMaxMs        longest pause between two data calls
     * @param renderMinMs      shortest pause between two page renders, which cost the
     *                         site far more than a data call
     * @param renderMaxMs      longest pause between two page renders
     * @param callsPerMinute   ceiling, whatever the pauses say
     * @param pagesMax         hard stop on pages per collection
     * @param renderedPagesMax pages per call when reading rendered pages, which is
     *                         the slow way in and should stay modest
     */
    public record Pace(int callMinMs, int callMaxMs, int renderMinMs, int renderMaxMs,
                       int callsPerMinute, int pagesMax, int renderedPagesMax) {

        public Pace {
            callMinMs = callMinMs > 0 ? callMinMs : 1500;
            callMaxMs = callMaxMs > callMinMs ? callMaxMs : callMinMs + 2000;
            renderMinMs = renderMinMs > 0 ? renderMinMs : 6000;
            renderMaxMs = renderMaxMs > renderMinMs ? renderMaxMs : renderMinMs + 4000;
            callsPerMinute = callsPerMinute > 0 ? callsPerMinute : 20;
            pagesMax = pagesMax > 0 ? pagesMax : 20;
            renderedPagesMax = renderedPagesMax > 0 ? renderedPagesMax : 3;
        }

        public static Pace defaults() {
            return new Pace(0, 0, 0, 0, 0, 0, 0);
        }

        /** A pause between two data calls, never the same twice. */
        public long callPause() {
            return callMinMs + (long) (Math.random() * (callMaxMs - callMinMs));
        }

        /** A pause between two page renders. */
        public long renderPause() {
            return renderMinMs + (long) (Math.random() * (renderMaxMs - renderMinMs));
        }
    }

    public LbcProperties {
        profile = profile != null ? profile : Path.of("/profile");
        data = data != null ? data : Path.of("/data");
        vncUrl = vncUrl != null ? vncUrl : "http://localhost:7900";
        chromeArgs = chromeArgs != null ? chromeArgs : List.of();
        maxAds = maxAds > 0 ? maxAds : 2000;
        autonomy = autonomy != null ? autonomy : "read";
        pace = pace != null ? pace : Pace.defaults();
    }

    public static LbcProperties defaults() {
        return new LbcProperties(null, null, false, null, null, null, 0, null, null);
    }

    /** Kept for readability at the call sites. */
    public int maxPages() {
        return pace.pagesMax();
    }
}
