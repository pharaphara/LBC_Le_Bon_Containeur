package io.github.pharaphara.lbc.site;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.github.pharaphara.lbc.browser.Browser.PageView;

/**
 * Anti robot checks: wait, tick one box, otherwise hand back to a human.
 *
 * <p>Nothing here solves a puzzle, and nothing calls a solving service. A grid of
 * images or a slider is where this tool stops and asks for a pair of eyes.
 */
public final class Walls {

    private Walls() {
    }

    /** Words that only ever show up on a wall, not on a page of results. */
    private static final List<String> MARKERS = List.of(
            "geo.captcha-delivery.com", "datadome", "captcha-delivery", "px-captcha",
            "cf-challenge", "are you a human", "access denied", "request blocked",
            "just a moment", "un instant", "checking your browser",
            "verification de votre navigateur", "verify you are human",
            "verifying you are human", "enable javascript and cookies to continue",
            "attention required", "pardon our interruption",
            "confirmez que vous etes un humain", "verifiez que vous etes");

    private static final List<String> CHALLENGE_FRAMES = List.of(
            "captcha-delivery", "recaptcha", "hcaptcha", "turnstile",
            "challenges.cloudflare.com", "perimeterx", "px-captcha");

    private static final List<Integer> REFUSALS = List.of(403, 429);

    /** Below this, a page is too thin to be a page of anything. */
    private static final int SHORT = 2500;

    private static final long WAIT_MS = 20_000;
    private static final long AFTER_TICK_MS = 15_000;
    private static final long STEP_MS = 1_500;

    public record Outcome(boolean ok, String reason, String attempt) {
    }

    /**
     * The reason a page is a wall, or null when it is a page.
     *
     * <p>This reads the RENDERED text, never the html. The word datadome appears
     * in the scripts of any page on the site; it only appears on screen when you
     * have actually been stopped.
     */
    public static String detect(PageView view, Integer status) {
        String head = norm(view.title()) + " " + norm(view.url());
        for (String m : MARKERS) {
            if (head.contains(m)) {
                return "anti robot wall (" + m + ")";
            }
        }
        String text = norm(view.text());
        if (text.length() < SHORT) {
            for (String m : MARKERS) {
                if (text.contains(m)) {
                    return "anti robot wall (" + m + ")";
                }
            }
            for (String f : view.frames()) {
                for (String d : CHALLENGE_FRAMES) {
                    if (norm(f).contains(d)) {
                        return "anti robot challenge (" + d + ")";
                    }
                }
            }
        }
        if (status != null && REFUSALS.contains(status)) {
            return "http refusal " + status;
        }
        return null;
    }

    /** Refuse a consent banner rather than accept it, then get out of the way. */
    public static String acceptCookies(Page page) {
        List<String> selectors = List.of(
                "#didomi-notice-disagree-button",
                "button#onetrust-reject-all-handler",
                "button[aria-label='Continuer sans accepter']",
                "[data-testid='gdpr-refuse-button']",
                "#didomi-notice-agree-button",
                "button#onetrust-accept-btn-handler",
                "button[mode='primary'][title='Accepter']");
        for (String s : selectors) {
            try {
                Locator l = page.locator(s).first();
                if (l.count() > 0 && l.isVisible()) {
                    click(l);
                    return s;
                }
            } catch (RuntimeException ignored) {
                // A banner that is not there is the normal case.
            }
        }
        return null;
    }

    /**
     * Wait, tick one box at most, then give up honestly.
     *
     * <p>A silent check in a real browser usually clears on its own, which is why
     * waiting comes first. One checkbox is the only gesture allowed, because that
     * is the only one a human would call trivial.
     */
    public static Outcome pass(Page page, Supplier<PageView> render, Integer status) {
        String reason = detect(render.get(), status);
        if (reason == null) {
            return new Outcome(true, null, null);
        }
        long started = System.currentTimeMillis();
        reason = waitItOut(render, WAIT_MS);
        if (reason == null) {
            return new Outcome(true, null,
                    "cleared on its own in " + secondsSince(started) + " s");
        }
        String gesture = tickOneBox(page);
        if (gesture == null) {
            return new Outcome(false, reason,
                    "waited " + WAIT_MS / 1000 + " s and found no box to tick");
        }
        String left = waitItOut(render, AFTER_TICK_MS);
        if (left == null) {
            return new Outcome(true, null,
                    gesture + ", cleared in " + secondsSince(started) + " s");
        }
        return new Outcome(false, left, gesture + ", with no effect");
    }

    private static String waitItOut(Supplier<PageView> render, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        String reason = detect(render.get(), null);
        while (reason != null && System.currentTimeMillis() < deadline) {
            sleep(STEP_MS);
            reason = detect(render.get(), null);
        }
        return reason;
    }

    /** One box, once. Never a puzzle, never a service. */
    private static String tickOneBox(Page page) {
        List<String> boxes = List.of("#recaptcha-anchor", "#checkbox",
                "input[type=checkbox]", "label.cb-lb input");
        for (Frame frame : page.frames()) {
            for (String s : boxes) {
                try {
                    Locator l = frame.locator(s).first();
                    if (l.count() > 0 && l.isVisible()) {
                        click(l);
                        return "ticked one box (" + s + ")";
                    }
                } catch (RuntimeException ignored) {
                    // Frames come and go while a challenge settles.
                }
            }
        }
        return null;
    }

    /**
     * Click for real.
     *
     * <p>A browser in a container with nobody watching only runs its animation
     * frames when something looks at it. An ordinary click waits for the element
     * to be "stable", meaning two identical frames, and that never comes here: it
     * just times out. So scroll it into view, check it ourselves, and force it.
     */
    static void click(Locator l) {
        try {
            l.scrollIntoViewIfNeeded();
        } catch (RuntimeException ignored) {
            // Not every element can scroll, and that is fine.
        }
        l.click(new Locator.ClickOptions().setForce(true).setTimeout(4000));
    }

    /** What to tell a human, in the words of what actually happened. */
    public static String advice(String vncUrl, String reason, String attempt) {
        return "Anti robot check (" + reason + "). The tool " + attempt + ". "
                + "Open " + vncUrl + ", pass the check by hand in the browser, then run the"
                + " same tool again. Do not retry in a loop, and do not try to work around it:"
                + " a real browser with a real session is the only answer here.";
    }

    private static long secondsSince(long start) {
        return (System.currentTimeMillis() - start) / 1000;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
