package io.github.pharaphara.lbc.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.github.pharaphara.lbc.LbcException;
import io.github.pharaphara.lbc.config.LbcProperties;
import org.springframework.stereotype.Component;

/**
 * One real browser, one tab, one lock.
 *
 * <p>Two ways to get one. Either this process starts Chromium on a profile
 * directory, which is the turnkey case, or it attaches over the devtools
 * protocol to a browser that is already running, which lets you bring your own
 * and keep the session you already signed into.
 *
 * <p>Nothing here disguises anything. No user agent is forged, no navigator
 * property is patched, and the flag that hides automation is deliberately not
 * passed. A real browser with a real session is the answer to a wall, and when
 * that is not enough a human clicks once.
 */
@Component
public class Browser implements AutoCloseable {

    /** What a page looks like from the outside: enough to spot a wall. */
    public record PageView(String title, String url, String text, List<String> frames) {
    }

    private final LbcProperties props;
    private final ReentrantLock lock = new ReentrantLock();

    private Playwright playwright;
    private BrowserContext context;
    private com.microsoft.playwright.Browser attached;
    private Page page;
    private volatile String webKey;

    public Browser(LbcProperties props) {
        this.props = props;
    }

    /**
     * Run some work with the tab, one caller at a time.
     *
     * <p>Every browser step goes through here. Without it a second request could
     * navigate the tab midway through a collection, and we would page through one
     * search's payload against another search's results.
     */
    public <T> T with(Function<Page, T> work) {
        lock.lock();
        try {
            return work.apply(page());
        } finally {
            lock.unlock();
        }
    }

    public synchronized Page page() {
        if (page != null && !page.isClosed()) {
            return page;
        }
        if (context == null) {
            start();
        }
        List<Page> open = context.pages();
        page = open.isEmpty() ? context.newPage() : open.get(0);
        watchForTheWebKey(page);
        return page;
    }

    private void start() {
        try {
            playwright = Playwright.create();
            if (props.cdpUrl() != null && !props.cdpUrl().isBlank()) {
                attached = playwright.chromium().connectOverCDP(props.cdpUrl());
                // The first context is THE profile, with the consents already
                // accepted and the session signed in by hand. A fresh one would
                // start from nothing.
                context = attached.contexts().isEmpty()
                        ? attached.newContext() : attached.contexts().get(0);
            } else {
                List<String> args = new ArrayList<>(List.of(
                        // A container has no sandbox helper, and a small /dev/shm.
                        "--no-sandbox", "--disable-dev-shm-usage"));
                args.addAll(props.chromeArgs());
                context = playwright.chromium().launchPersistentContext(props.profile(),
                        new BrowserType.LaunchPersistentContextOptions()
                                .setHeadless(props.headless())
                                .setLocale("fr-FR")
                                .setTimezoneId("Europe/Paris")
                                .setViewportSize(1440, 900)
                                .setArgs(args));
            }
        } catch (RuntimeException e) {
            close();
            throw new LbcException(LbcException.Kind.BROWSER,
                    "cannot start the browser: " + e,
                    props.cdpUrl() == null
                            ? "check that the image has its browser and a display"
                            : "check that " + props.cdpUrl() + " is reachable");
        }
    }

    /**
     * Learn the site's public web key from a request the page makes itself.
     *
     * <p>It is not a secret: every visitor's browser sends it in the clear. But it
     * does change now and then, so listening beats hardcoding, and the constant
     * stays only as a starting point.
     */
    private void watchForTheWebKey(Page p) {
        p.onRequest(request -> {
            if (request.url().contains("api.leboncoin.fr")) {
                String k = request.headers().get("api_key");
                if (k != null && !k.isBlank()) {
                    webKey = k;
                }
            }
        });
    }

    /** The key seen on the wire, or null if the page has not called home yet. */
    public String webKey() {
        return webKey;
    }

    public boolean running() {
        return context != null && page != null && !page.isClosed();
    }

    public PageView view() {
        Page p = page();
        String text;
        try {
            text = p.innerText("body");
        } catch (RuntimeException e) {
            text = "";
        }
        List<String> frames = new ArrayList<>();
        for (Frame f : p.frames()) {
            if (f.url() != null && !f.url().isBlank()) {
                frames.add(f.url());
            }
        }
        return new PageView(safe(p::title), p.url(), text, frames);
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Let go of the browser.
     *
     * <p>An attached browser is somebody else's: we disconnect and leave its tabs
     * alone. Closing the last tab of a real profile would quit Chromium, and take
     * the signed in session down with it.
     */
    @Override
    public synchronized void close() {
        try {
            if (attached != null) {
                attached = null;
            } else if (context != null) {
                context.close();
            }
        } catch (RuntimeException ignored) {
            // Shutting down is not worth an exception.
        } finally {
            context = null;
            page = null;
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (RuntimeException ignored) {
                    // Same.
                }
                playwright = null;
            }
        }
    }
}
