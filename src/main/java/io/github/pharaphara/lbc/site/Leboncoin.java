package io.github.pharaphara.lbc.site;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import io.github.pharaphara.lbc.Json;
import io.github.pharaphara.lbc.LbcException;
import io.github.pharaphara.lbc.browser.Browser;
import io.github.pharaphara.lbc.config.LbcProperties;
import io.github.pharaphara.lbc.query.Query;
import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Store;
import org.springframework.stereotype.Component;

/**
 * Collect a lot of ads with a single page render.
 *
 * <p>The expensive part is rendering a page, not asking for data. And a results
 * page embeds the very payload the site derived from our URL. So we render once,
 * read that payload back, and page through the rest by making the same call the
 * page makes for itself, from inside the page, where the real cookies and the
 * real origin are.
 *
 * <p>Four things were measured, and they are why this loop looks the way it does.
 * Paging happens on {@code offset} alone. The {@code pivot} the site returns is
 * not a cursor but a list of ids already shown, and replaying it from offset zero
 * hands back the same ads. {@code max_pages} bounds nothing useful, coming out at
 * 2 for 122 ads, so it is reported and never trusted. And the api repeats ads,
 * including inside a single response, so the count that matters is the count of
 * unique ids.
 */
@Component
public class Leboncoin {

    public static final String API = "https://api.leboncoin.fr/finder/search";

    /**
     * The public web key the site's own pages send in the clear. Not a secret,
     * but it does rotate, so {@link Browser#webKey()} overrides it as soon as the
     * page makes a real call.
     */
    static final String WEB_KEY = "ba0c2dad52b3ec";

    public static final int PAGE_SIZE = 100;
    private static final long BACKOFF_MS = 25_000;

    private final Browser browser;
    private final Store store;
    private final LbcProperties props;
    private final Deque<Long> calls = new ArrayDeque<>();
    private String parkedUrl;

    public Leboncoin(Browser browser, Store store, LbcProperties props) {
        this.browser = browser;
        this.store = store;
        this.props = props;
    }

    public record Payload(List<String> keys, Map<String, Object> search,
                          Map<String, Object> totals, int rendered) {

        public boolean usable() {
            return search != null && !search.isEmpty();
        }
    }

    public record Collected(int pages, int calls, int read, int fresh, int duplicates,
                            int outOfCategory, int offset, String stop, String note,
                            Map<String, Object> totals, Map<String, Object> payload,
                            boolean rendered) {
    }

    public record Probed(String id, String confirmed, Double total, List<String> labels,
                         int ads, String title) {
    }

    // ------------------------------------------------------------------ browsing

    /** Bring the tab to the url, and settle any check before reading anything. */
    private boolean park(Page page, String url, boolean force) {
        if (!force && url.equals(parkedUrl)) {
            return false;
        }
        var response = page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
        Integer status = response == null ? null : response.status();
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(8_000));
        } catch (RuntimeException ignored) {
            // A page that keeps chattering is still a page.
        }
        Walls.acceptCookies(page);
        Walls.Outcome outcome = Walls.pass(page, browser::view, status);
        if (!outcome.ok()) {
            parkedUrl = null;
            throw new LbcException(LbcException.Kind.WALL, outcome.reason(),
                    Walls.advice(props.vncUrl(), outcome.reason(), outcome.attempt()));
        }
        parkedUrl = url;
        return true;
    }

    private Payload readPayload(Page page) {
        Map<String, Object> raw = Json.map(page.evaluate(Js.PAYLOAD));
        List<String> keys = new ArrayList<>();
        for (Object k : Json.list(raw.get("keys"))) {
            keys.add(String.valueOf(k));
        }
        Double rendered = Ads.num(raw.get("rendered"));
        return new Payload(keys, raw.get("search") == null ? null : Json.map(raw.get("search")),
                Json.map(raw.get("totals")), rendered == null ? 0 : rendered.intValue());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> adsByShape(Page page) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.list(page.evaluate(Js.ADS_BY_SHAPE))) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** Our own pace. Being fast was never the point. */
    private void pace() {
        long now = System.currentTimeMillis();
        while (!calls.isEmpty() && now - calls.peekFirst() > 60_000) {
            calls.pollFirst();
        }
        if (calls.size() >= props.pace().callsPerMinute()) {
            sleep(60_000 - (now - calls.peekFirst()) + 500);
        }
        calls.addLast(System.currentTimeMillis());
    }

    private Map<String, Object> call(Page page, Map<String, Object> payload) {
        pace();
        String key = browser.webKey() != null ? browser.webKey() : WEB_KEY;
        return Json.map(page.evaluate(Js.CALL, List.of(API, key, payload)));
    }

    /**
     * A refusal from the api. Two very different things hide behind one code.
     *
     * <p>No anti robot marker in the body means the public web key rotated, and
     * there is nothing to work around. A marker means the data calls want a
     * session the browser does not have yet, which is survivable: the rendered
     * pages are still readable, so the caller falls back to those.
     */
    private boolean keyIsStale(Map<String, Object> answer) {
        String body = String.valueOf(answer.getOrDefault("body", "")).toLowerCase();
        return !(body.contains("datadome") || body.contains("captcha")
                || body.contains("blocked") || body.contains("bloqu"));
    }

    private LbcException staleKey(Map<String, Object> answer) {
        return new LbcException(LbcException.Kind.STALE_KEY,
                "the api answered " + fmt(Ads.num(answer.get("status")))
                        + " with no anti robot marker",
                "the site's public web key has probably rotated. Nothing to work around:"
                        + " load a results page in the browser so the tool can learn the"
                        + " new one from a real request.");
    }

    /** A wall on the page itself is where this stops and asks for a human. */
    private LbcException walled(Page page, String fallbackReason) {
        Walls.Outcome outcome = Walls.pass(page, browser::view, null);
        String reason = outcome.reason() != null ? outcome.reason() : fallbackReason;
        return new LbcException(LbcException.Kind.WALL, reason,
                Walls.advice(props.vncUrl(), reason,
                        outcome.attempt() == null ? "tried nothing, on purpose" : outcome.attempt()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> renderedAds(Page page) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.list(page.evaluate(Js.RENDERED_ADS))) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static String withPage(String url, int page) {
        String clean = url.replaceAll("[?&]page=\\d+", "");
        return clean + (clean.contains("?") ? "&" : "?") + "page=" + page;
    }

    /**
     * Read the rendered pages instead of asking for data.
     *
     * <p>The slow way in, and the only one available until somebody passes the
     * check once. One render per page, about thirty five ads each, and the answer
     * says so plainly rather than pretending it collected everything.
     */
    private Collected byRendering(Page page, String name, String url, String categoryId,
                                  int pages, int cap, int already, Payload first,
                                  boolean renderedFirst) {
        int fresh = 0;
        int duplicates = 0;
        int outOfCategory = 0;
        int read = 0;
        int done = 0;
        String stop = "end";

        // And fewer pages per call than the fast path would take, for the same reason.
        int ceilingPages = Math.max(1, Math.min(pages, Math.min(props.maxPages(), props.pace().renderedPagesMax())));
        for (int n = 1; n <= ceilingPages; n++) {
            if (already + fresh >= cap) {
                stop = "ceiling";
                break;
            }
            if (n > 1) {
                park(page, withPage(url, n), true);
            }
            List<Map<String, Object>> ads = renderedAds(page);
            read += ads.size();
            done++;
            if (ads.isEmpty()) {
                stop = "empty";
                break;
            }
            Store.Added added = store.add(name, ads, categoryId);
            fresh += added.fresh();
            duplicates += added.duplicates();
            outOfCategory += added.outOfCategory();
            if (added.fresh() == 0 && added.outOfCategory() == 0) {
                stop = "nothing_new";
                break;
            }
            if (ads.size() < 30) {
                stop = "end";
                break;
            }
            stop = "pages";
            // Far slower than the data calls on purpose. Rendering a whole page costs
            // the site more than answering a query, and a profile with no clearance is
            // precisely the one being watched. Going gently is not politeness here, it
            // is what keeps the address out of trouble.
            sleep(props.pace().renderPause());
        }
        String note = "the data calls were refused, which is what happens until a session exists,"
                + " so the rendered pages were read instead: about 35 ads per page rather than 100,"
                + " one page render each, and deliberately slow, at most three pages per call."
                + " Keep leaning on this and the site will restrict the address for a while."
                + " Pass the check once at " + props.vncUrl() + " and the fast path opens by itself.";
        return new Collected(done, 0, read, fresh, duplicates, outOfCategory, 0,
                "rendered_pages:" + stop, note, first.totals(), first.search(), renderedFirst);
    }

    // ------------------------------------------------------------------ collect

    /**
     * Render at most one page, then page through on offset. One lock throughout.
     *
     * <p>Stops, in this order: the ceiling, a refusal, an empty answer, nothing
     * new, a short page which means the last one, and the number of pages asked
     * for. Each one has a name, because a collection that stopped early and did
     * not say so is worse than a short one.
     */
    public Collected collect(String name, String url, String categoryId, int pages,
                             int ceiling, int startOffset, Map<String, Object> spare) {
        return browser.with(page -> {
            boolean rendered = park(page, url, false);
            Payload payload = readPayload(page);

            if (!payload.usable()) {
                List<Map<String, Object>> found = adsByShape(page);
                Store.Added added = store.add(name, found, categoryId);
                String note = "the page no longer exposes its search payload, so the ads were"
                        + " read by their shape instead. Keys seen: "
                        + (payload.keys().isEmpty() ? "none" : String.join(", ", payload.keys()));
                return new Collected(1, 0, found.size(), added.fresh(), added.duplicates(),
                        added.outOfCategory(), startOffset, "unknown_structure", note,
                        payload.totals(), spare, rendered);
            }

            Map<String, Object> query = payload.search();
            int offset = Math.max(0, startOffset);
            int already = (int) store.index(name).values().stream().filter(n -> n > 0).count();
            int wanted = Math.max(1, Math.min(pages, props.maxPages()));
            int cap = Math.min(ceiling <= 0 ? props.maxAds() : ceiling, props.maxAds());

            int pageCount = 0;
            int callCount = 0;
            int read = 0;
            int fresh = 0;
            int duplicates = 0;
            int outOfCategory = 0;
            String stop = "end";
            boolean backedOff = false;

            for (int i = 0; i < wanted; i++) {
                if (already + fresh >= cap) {
                    stop = "ceiling";
                    break;
                }
                Map<String, Object> body = new LinkedHashMap<>(query);
                body.put("limit", PAGE_SIZE);
                body.put("limit_alu", 0);
                body.put("offset", offset);

                Map<String, Object> answer = call(page, body);
                callCount++;
                Double status = Ads.num(answer.get("status"));
                int code = status == null ? 0 : status.intValue();

                if (code == 429) {
                    if (backedOff) {
                        stop = "pace";
                        break;
                    }
                    backedOff = true;
                    sleep(BACKOFF_MS);
                    continue;
                }
                if (code == 401 || code == 403) {
                    if (keyIsStale(answer)) {
                        throw staleKey(answer);
                    }
                    if (pageCount == 0) {
                        // Nothing collected yet, and the page itself is fine: read
                        // what it renders rather than hand back an empty result.
                        if (renderedAds(page).isEmpty()) {
                            throw walled(page, "the api refused the call (http " + code
                                    + ") and the page renders no ads either");
                        }
                        return byRendering(page, name, url, categoryId, wanted, cap, already,
                                payload, rendered);
                    }
                    stop = "api_refused";
                    break;
                }
                Map<String, Object> data = Json.map(answer.get("data"));
                if (code != 200 || data.isEmpty()) {
                    stop = "refused_" + code;
                    break;
                }
                List<Map<String, Object>> ads = new ArrayList<>();
                for (Object o : Json.list(data.get("ads"))) {
                    ads.add(Json.map(o));
                }
                read += ads.size();
                if (ads.isEmpty()) {
                    stop = "empty";
                    break;
                }
                Store.Added added = store.add(name, ads, categoryId);
                fresh += added.fresh();
                duplicates += added.duplicates();
                outOfCategory += added.outOfCategory();
                pageCount++;
                offset += ads.size();

                if (added.fresh() == 0 && added.outOfCategory() == 0) {
                    stop = "nothing_new";
                    break;
                }
                if (ads.size() < PAGE_SIZE) {
                    stop = "end";
                    break;
                }
                stop = "pages";
                sleep(props.pace().callPause());
            }
            return new Collected(pageCount, callCount, read, fresh, duplicates, outOfCategory,
                    offset, stop, null, payload.totals(), query, rendered);
        });
    }

    /** The payload and totals of a url, collecting nothing. */
    public Payload inspect(String url) {
        return browser.with(page -> {
            park(page, url, false);
            return readPayload(page);
        });
    }

    /** One ad from its own page, to check how fresh what we stored still is. */
    public Map<String, Object> adPage(String id) {
        String url = "https://www.leboncoin.fr/vi/" + id + ".htm";
        return browser.with(page -> {
            park(page, url, true);
            Object raw = page.evaluate(Js.ONE_AD);
            Map<String, Object> ad = Json.map(raw);
            if (ad.isEmpty()) {
                for (Map<String, Object> candidate : adsByShape(page)) {
                    if (Ads.id(candidate).equals(id)) {
                        return candidate;
                    }
                }
                throw new LbcException(LbcException.Kind.UNKNOWN_STRUCTURE,
                        "ad " + id + " is not readable on its page",
                        "the page may have been taken down, or its structure changed");
            }
            return ad;
        });
    }

    /**
     * What the site answers for one category id.
     *
     * <p>The oracle is free: ads carry their category name. Comparing that label
     * to the one expected turns a copied belief into a measurement.
     */
    public Probed probe(String id) {
        String url = Query.SEARCH_URL + "?category=" + id;
        return browser.with(page -> {
            park(page, url, true);
            Map<String, Object> raw = Json.map(page.evaluate(Js.PROBE));
            if (raw.isEmpty() || Boolean.TRUE.equals(raw.get("missing"))) {
                throw new LbcException(LbcException.Kind.UNKNOWN_STRUCTURE,
                        "the page for category " + id + " exposes nothing to read");
            }
            List<String> labels = new ArrayList<>();
            for (Object o : Json.list(raw.get("labels"))) {
                labels.add(String.valueOf(o));
            }
            Double ads = Ads.num(raw.get("ads"));
            return new Probed(id, str(raw.get("confirmed")), Ads.num(raw.get("total")), labels,
                    ads == null ? 0 : ads.intValue(), str(raw.get("title")));
        });
    }

    /** Forget where the tab is parked, so the next call renders again. */
    public void forgetParking() {
        parkedUrl = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmt(Double v) {
        return v == null ? "?" : String.valueOf(v.intValue());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
