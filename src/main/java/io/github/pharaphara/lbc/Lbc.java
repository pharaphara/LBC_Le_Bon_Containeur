package io.github.pharaphara.lbc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.pharaphara.lbc.config.LbcProperties;
import io.github.pharaphara.lbc.query.Query;
import io.github.pharaphara.lbc.render.View;
import io.github.pharaphara.lbc.site.Leboncoin;
import io.github.pharaphara.lbc.stats.Market;
import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Selection;
import io.github.pharaphara.lbc.store.Store;
import io.github.pharaphara.lbc.store.Store.AdRecord;
import org.springframework.stereotype.Service;

/**
 * What the tools actually do, in one place.
 *
 * <p>Each method hands back a state, a few bounded lines meant to be read, and
 * the same thing as data for a caller that would rather compute. The lines are
 * the point: an assistant reading a ready made table spends a fraction of what it
 * would spend parsing a page of json, and the table never grows past its ceiling.
 */
@Service
public class Lbc {

    /** Twelve hours. Under that, a repeated search is served from disk, and that
     * means no call and not even a page render. */
    private static final long FRESH_FOR_MS = 12 * 3600 * 1000L;
    private static final int DEFAULT_ROWS = 25;
    private static final double GAP_TOLERANCE = 0.10;

    public record Result(String state, List<String> lines, Map<String, Object> data) {

        public String text() {
            return String.join("\n", lines);
        }
    }

    private final Store store;
    private final Query query;
    private final Leboncoin site;
    private final LbcProperties props;

    public Lbc(Store store, Query query, Leboncoin site, LbcProperties props) {
        this.store = store;
        this.query = query;
        this.site = site;
        this.props = props;
    }

    // ------------------------------------------------------------------ search

    public Result search(String url, String text, String category, String place, String near,
                         Map<String, Object> criteria, String sort, int pages, int ceiling,
                         boolean refresh, String name, int rows) {
        List<String> ignored = new ArrayList<>();
        String target;
        if (url != null && !url.isBlank()) {
            target = url.trim();
        } else {
            Query.Built built = query.buildUrl(text, category, criteria, place, sort, null);
            target = built.url();
            ignored.addAll(built.ignored());
        }
        String demand = text != null && !text.isBlank() ? text
                : category != null && !category.isBlank() ? category : target;
        String slug = name != null && !name.isBlank() ? name : Store.slugify(demand, target);
        String categoryId = category == null || category.isBlank() ? null : query.categoryId(category);
        double[] ref = reference(near);

        boolean known = store.exists(slug);
        Map<String, Object> meta = known ? store.meta(slug) : new LinkedHashMap<>();
        long age = known ? System.currentTimeMillis() - lastTouched(slug) : Long.MAX_VALUE;
        boolean fromDisk = known && !refresh && target.equals(meta.get("url"))
                && age < FRESH_FOR_MS && !store.load(slug).isEmpty();

        if (!fromDisk) {
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("demand", demand);
            seed.put("url", target);
            seed.put("category", category);
            seed.put("categoryId", categoryId);
            seed.put("criteria", criteria);
            seed.put("near", near);
            seed.put("ignored", ignored);
            meta = store.create(slug, seed);

            int offset = refresh ? 0 : intOf(Json.map(meta.get("pagination")).get("offset"));
            Leboncoin.Collected got = site.collect(slug, target, categoryId, pages, ceiling,
                    offset, Json.map(meta.get("payload")));

            Query.Reflection reflection = Query.reflect(target, got.payload());
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("offset", got.offset());
            pagination.put("pages", got.pages());
            pagination.put("calls", got.calls());
            pagination.put("read", got.read());
            pagination.put("duplicates", got.duplicates());
            pagination.put("outOfCategory", got.outOfCategory());
            pagination.put("stop", got.stop());
            meta.put("pagination", pagination);
            meta.put("payload", got.payload());
            meta.put("totals", got.totals());
            meta.put("condition", got.totals().get("human_readable_applied_condition"));
            meta.put("dropped", reflection.dropped());
            meta.put("kept", reflection.kept());
            if (got.note() != null) {
                meta.put("note", got.note());
            }
            store.writeMeta(slug, meta);
        }
        store.setCurrent(slug);

        Result view = view(slug, meta, null, sort, 0, rows, ref);
        List<String> warnings = new ArrayList<>(strings(view.data().get("warnings")));
        if (!ignored.isEmpty()) {
            warnings.add("criteria the site has no filter for: " + String.join(", ", ignored)
                    + " (reapplied here when possible)");
        }
        for (String p : strings(meta.get("dropped"))) {
            warnings.add("parameter the site threw away: " + p
                    + ", so the search came back wider than asked for");
        }
        if (meta.get("condition") != null) {
            warnings.add("condition the site applied: " + meta.get("condition"));
        }
        Map<String, Object> pagination = Json.map(meta.get("pagination"));
        int outOfCategory = intOf(pagination.get("outOfCategory"));
        if (outOfCategory > 0) {
            warnings.add(outOfCategory + " ads set aside because they come from another section"
                    + " (kept in skipped.jsonl)");
        }
        Double announced = Ads.num(Json.map(meta.get("totals")).get("total_all"));
        int unique = intOf(view.data().get("ads"));
        if (announced != null && announced > 0 && unique > 0
                && Math.abs(announced - unique) > GAP_TOLERANCE * announced) {
            warnings.add("collected " + unique + " of " + announced.intValue() + " announced"
                    + " (stopped on " + pagination.get("stop") + "): call search again with"
                    + " more pages to continue");
        }
        if (fromDisk) {
            warnings.add("served from disk, collected " + Math.round(age / 3600000.0)
                    + " h ago. Pass refresh=true to collect again.");
        }
        String state = "ok";
        if (meta.get("note") != null) {
            state = "partial_structure";
            warnings.add(String.valueOf(meta.get("note")));
        } else if (announced != null && announced == 0 && unique == 0
                && (text == null || text.isBlank()) && (criteria == null || criteria.isEmpty())) {
            state = "suspiciously_empty";
            warnings.add("the site announces zero ads and no filter explains it: the session may"
                    + " have expired. Open the browser and check.");
        }

        Map<String, Object> data = new LinkedHashMap<>(view.data());
        data.put("search", slug);
        data.put("url", target);
        data.put("network", !fromDisk);
        data.put("totals", meta.get("totals"));
        data.put("collection", pagination);
        data.put("warnings", warnings);
        return new Result(state, lines(slug, target, data, warnings, "results"), data);
    }

    // ------------------------------------------------------------------ reading

    public Result results(String name, String sort, Map<String, Object> filter,
                          int offset, int rows) {
        String slug = store.resolve(name);
        Map<String, Object> meta = store.meta(slug);
        Result view = view(slug, meta, filter, sort, offset, rows, reference(str(meta.get("near"))));
        Map<String, Object> data = new LinkedHashMap<>(view.data());
        data.put("search", slug);
        data.put("network", false);
        data.put("totals", meta.get("totals"));
        return new Result("ok", lines(slug, str(meta.get("url")), data,
                strings(data.get("warnings")), "results"), data);
    }

    public Result ad(String name, String number, boolean refresh, int offset, int chars) {
        String slug = store.resolve(name);
        Map<String, Object> meta = store.meta(slug);
        AdRecord found = store.byNumber(slug, number).orElseThrow(() -> new LbcException(
                LbcException.Kind.NOT_FOUND,
                "\"" + number + "\" is not in search \"" + slug + "\"",
                "the results tool lists the numbers that exist"));
        List<AdRecord> one = Market.withDistances(List.of(found), reference(str(meta.get("near"))));
        AdRecord rec = one.get(0);
        List<String> changes = new ArrayList<>();
        if (refresh) {
            Map<String, Object> before = rec.ad();
            Map<String, Object> now = site.adPage(rec.id());
            if (!String.valueOf(Ads.price(before)).equals(String.valueOf(Ads.price(now)))) {
                changes.add("price: " + View.euros(Ads.price(before))
                        + " becomes " + View.euros(Ads.price(now)));
            }
            if (Ads.isSold(before) != Ads.isSold(now)) {
                changes.add("now " + (Ads.isSold(now) ? "sold" : "available again"));
            }
            rec = rec.refreshed(now, Store.now());
            store.replace(slug, rec);
            store.writeAd(slug, rec.id(), now);
        }
        List<String> lines = new ArrayList<>();
        lines.add(View.ad(rec, offset, chars));
        for (String c : changes) {
            lines.add("!! changed since it was collected: " + c);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("search", slug);
        data.put("number", rec.number());
        data.put("id", rec.id());
        data.put("network", refresh);
        data.put("changes", changes);
        data.put("ad", rec.ad());
        return new Result("ok", lines, data);
    }

    public Result stats(String name, Map<String, Object> filter, String group) {
        String slug = store.resolve(name);
        Map<String, Object> meta = store.meta(slug);
        double[] ref = reference(str(meta.get("near")));
        List<AdRecord> all = Market.withDistances(store.load(slug), ref);
        Selection.Kept kept = Selection.filter(all, filter);
        Market market = Market.of(kept.ads(), ref);

        List<String> lines = new ArrayList<>();
        lines.add("search \"" + slug + "\" | " + View.number(all.size()) + " collected"
                + (kept.ads().size() == all.size() ? ""
                : " | " + View.number(kept.ads().size()) + " kept")
                + " | offline");
        lines.addAll(View.summary(market));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("search", slug);
        data.put("ads", all.size());
        data.put("kept", kept.ads().size());
        data.put("dropped", kept.dropped());
        data.put("market", market);
        data.put("network", false);
        if (group != null && !group.isBlank()) {
            List<Market.Group> groups = Market.groupBy(kept.ads(), group, 10);
            lines.add("---");
            lines.add(pad("by " + group, 24) + pad("ads", 6) + "median price");
            for (Market.Group g : groups) {
                lines.add(pad(g.value(), 24) + pad(View.number(g.count()), 6)
                        + View.euros(g.medianPrice()));
            }
            data.put("groups", groups);
        }
        return new Result("ok", lines, data);
    }

    // ------------------------------------------------------------------ the site itself

    public Result filters(String url, String category, Map<String, Object> criteria, String place) {
        List<String> ignored = new ArrayList<>();
        String target;
        if (url != null && !url.isBlank()) {
            target = url.trim();
        } else {
            Query.Built built = query.buildUrl(null, category, criteria, place, null, null);
            target = built.url();
            ignored.addAll(built.ignored());
        }
        Leboncoin.Payload payload = site.inspect(target);
        Query.Reflection reflection = Query.reflect(target, payload.search());
        Map<String, Object> totals = payload.totals();

        List<String> lines = new ArrayList<>();
        lines.add(target);
        lines.add("the site announces " + View.number(totals.get("total_all")) + " ads ("
                + View.number(totals.get("total_private")) + " private, "
                + View.number(totals.get("total_pro")) + " pro, "
                + View.number(totals.get("total_inactive")) + " inactive)");
        lines.add("kept by the site: " + join(reflection.kept()));
        lines.add("THROWN AWAY by the site: " + join(reflection.dropped()));
        if (!ignored.isEmpty()) {
            lines.add("not translated here: " + join(ignored));
        }
        if (totals.get("human_readable_applied_condition") != null) {
            lines.add("condition applied: " + totals.get("human_readable_applied_condition"));
        }
        if (category != null && !category.isBlank()) {
            lines.add("known filters for " + category + ": "
                    + join(strings(query.filterHelp(category).get("filters"))));
            List<String> traps = strings(query.filterHelp(category).get("traps"));
            traps.forEach(t -> lines.add("trap: " + t));
        }
        lines.add("A parameter listed as thrown away widens the search without saying so. To"
                + " teach the tool a filter it does not know, apply it by hand in the browser"
                + " and pass the resulting url here.");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", target);
        data.put("kept", reflection.kept());
        data.put("dropped", reflection.dropped());
        data.put("ignored", ignored);
        data.put("totals", totals);
        data.put("payload", payload.search());
        return new Result(payload.usable() ? "ok" : "partial_structure", lines, data);
    }

    public Result categories(List<String> verify) {
        List<Map<String, Object>> outcomes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String wanted : verify == null ? List.<String>of() : verify) {
            Query.Category known = query.category(wanted);
            if (known == null && wanted.trim().matches("\\d+")) {
                // A bare number is probed as is, so no id is out of reach.
                Leboncoin.Probed probed = site.probe(wanted.trim());
                String seen = probed.labels().isEmpty() ? "" : probed.labels().get(0);
                String verdict = probed.labels().size() > 1 ? "group of sections"
                        : probed.labels().isEmpty() ? "no ads to read" : "reads as \"" + seen + "\"";
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", wanted.trim());
                row.put("seenAs", seen);
                row.put("labels", probed.labels());
                row.put("total", probed.total());
                row.put("verdict", verdict);
                outcomes.add(row);
                lines.add(pad("id " + wanted.trim(), 20) + pad(verdict, 28)
                        + View.number(probed.total()) + " ads");
                continue;
            }
            if (known == null) {
                lines.add(wanted + ": unknown name. A numeric id can be probed directly.");
                continue;
            }
            List<Integer> candidates = known.id() != null
                    ? List.of(known.id()) : known.candidates();
            if (candidates.isEmpty()) {
                lines.add(known.name() + ": no candidate left to try, pass a numeric id instead");
                continue;
            }
            for (int id : candidates) {
                Leboncoin.Probed probed;
                try {
                    probed = site.probe(String.valueOf(id));
                } catch (LbcException e) {
                    lines.add(known.name() + " id " + id + ": probe failed, " + e.getMessage());
                    continue;
                }
                String seen = probed.labels().isEmpty() ? "" : probed.labels().get(0);
                String verdict;
                boolean ok = false;
                if (probed.labels().size() > 1) {
                    verdict = "group of sections";
                } else if (probed.labels().isEmpty()) {
                    verdict = "no ads to read";
                } else if (matches(known.label() == null ? known.name() : known.label(), seen)) {
                    verdict = "verified";
                    ok = true;
                } else {
                    verdict = "mismatch";
                }
                query.markCategory(known.name(), id, seen, ok);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", known.name());
                row.put("id", id);
                row.put("seenAs", seen);
                row.put("labels", probed.labels());
                row.put("total", probed.total());
                row.put("confirmedBySite", probed.confirmed());
                row.put("verdict", verdict);
                outcomes.add(row);
                lines.add(pad(known.name(), 20) + pad("id " + id, 8) + pad(verdict, 20)
                        + "seen as \"" + seen + "\" (" + View.number(probed.total()) + " ads)");
                if (ok) {
                    break;
                }
            }
        }
        if (!outcomes.isEmpty()) {
            lines.add("---");
        }
        Map<String, Object> known = new LinkedHashMap<>();
        query.categories().forEach((name, c) -> {
            known.put(name, Map.of("id", c.id() == null ? "" : c.id(),
                    "verified", c.verified() == null ? false : c.verified(),
                    "label", c.label() == null ? "" : c.label()));
            lines.add(pad(name, 20) + pad(c.id() == null ? "" : "id " + c.id(), 8)
                    + (c.verified() != null ? "verified " + c.verified()
                    : "not verified" + (c.candidates().isEmpty() ? "" : ", candidates "
                    + join(c.candidates().stream().map(String::valueOf).toList()))));
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("probes", outcomes);
        data.put("categories", known);
        return new Result("ok", lines, data);
    }

    public Result searches() {
        List<Store.SearchInfo> all = store.searches();
        List<String> lines = new ArrayList<>();
        if (all.isEmpty()) {
            lines.add("no search yet: start with the search tool");
        }
        for (Store.SearchInfo s : all) {
            lines.add((s.current() ? "* " : "  ") + pad(s.slug(), 40)
                    + pad(View.number(s.ads()) + " ads", 12)
                    + pad(s.ageHours() + " h ago", 14)
                    + (s.stop() == null ? "" : "(" + s.stop() + ")"));
        }
        if (!all.isEmpty()) {
            lines.add("* is the current search, the one the other tools use by default");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("searches", all);
        data.put("current", store.current());
        return new Result("ok", lines, data);
    }

    public Result forget(String name) {
        Store.Forgotten gone = store.forget(store.resolve(name));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", gone.removed());
        data.put("bytes", gone.bytes());
        return new Result("ok", List.of(gone.removed()
                ? "removed, " + View.number(gone.bytes() / 1024) + " kB freed"
                : "nothing to remove"), data);
    }

    public Result status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", "0.1.0");
        data.put("browser", props.cdpUrl() == null || props.cdpUrl().isBlank()
                ? "started here, profile " + props.profile() : "attached to " + props.cdpUrl());
        data.put("headless", props.headless());
        data.put("viewer", props.vncUrl());
        data.put("dataDir", props.data().toString());
        data.put("searches", store.searches().size());
        data.put("autonomy", props.autonomy());
        List<String> lines = new ArrayList<>();
        lines.add("lbc 0.1.0 | " + data.get("browser"));
        lines.add("searches on disk: " + data.get("searches") + " | autonomy: "
                + data.get("autonomy"));
        lines.add("if a tool reports a wall, open " + props.vncUrl()
                + " and pass the check by hand once. The profile remembers the session,"
                + " so signing in is a one time gesture too.");
        return new Result("ok", lines, data);
    }

    // ------------------------------------------------------------------ plumbing

    /** Load, place, filter, sort, window. No network anywhere in here. */
    private Result view(String slug, Map<String, Object> meta, Map<String, Object> filter,
                        String sort, int offset, int rows, double[] ref) {
        List<AdRecord> all = Market.withDistances(store.load(slug), ref);
        Map<String, Object> local = query.localFilter(Json.map(meta.get("criteria")),
                str(meta.get("category")), strings(meta.get("dropped")), strings(meta.get("ignored")));
        Map<String, Object> merged = new LinkedHashMap<>(local);
        if (filter != null) {
            merged.putAll(filter);
        }
        Selection.Kept kept = Selection.filter(all, merged);
        List<AdRecord> sorted = Selection.sort(kept.ads(), sort);
        Selection.Window window = Selection.window(sorted, offset, rows <= 0 ? DEFAULT_ROWS : rows);
        List<String> columns = View.columns(str(meta.get("category")), all);
        Market market = Market.of(kept.ads(), ref);

        List<String> warnings = new ArrayList<>();
        int broken = store.unreadable(slug);
        if (broken > 0) {
            warnings.add(broken + " unreadable line(s) in ads.jsonl: the count is incomplete,"
                    + " and refreshing an ad will refuse to rewrite the file");
        }
        if (!local.isEmpty()) {
            warnings.add("criteria the site does not apply, reapplied here: " + local);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ads", all.size());
        data.put("kept", kept.ads().size());
        data.put("dropped", kept.dropped());
        data.put("offset", window.offset());
        data.put("next", window.next());
        data.put("rest", window.rest());
        data.put("market", market);
        data.put("rows", window.ads().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", r.number());
            row.put("id", r.id());
            row.put("price", Ads.price(r.ad()));
            row.put("title", io.github.pharaphara.lbc.render.Text.clip(Ads.title(r.ad()), 80));
            row.put("city", Ads.place(r.ad()).city());
            row.put("pro", Ads.isPro(r.ad()));
            row.put("sold", Ads.isSold(r.ad()));
            row.put("url", Ads.url(r.ad()));
            return row;
        }).toList());
        data.put("warnings", warnings);
        data.put("table", View.table(window.ads(), columns));
        return new Result("ok", List.of(), data);
    }

    /** The head, the markers, the table, then how to read the rest. */
    private List<String> lines(String slug, String url, Map<String, Object> data,
                               List<String> warnings, String continuation) {
        List<String> out = new ArrayList<>();
        Map<String, Object> totals = Json.map(data.get("totals"));
        Map<String, Object> collection = Json.map(data.get("collection"));
        List<String> head = new ArrayList<>();
        head.add("search \"" + slug + "\"");
        if (totals.get("total_all") != null) {
            head.add(View.number(totals.get("total_all")) + " announced");
        }
        head.add(View.number(data.get("ads")) + " collected");
        if (intOf(collection.get("duplicates")) > 0) {
            head.add(View.number(collection.get("duplicates")) + " duplicates dropped");
        }
        if (!String.valueOf(data.get("ads")).equals(String.valueOf(data.get("kept")))) {
            head.add(View.number(data.get("kept")) + " kept");
        }
        head.add(Boolean.FALSE.equals(data.get("network")) ? "offline"
                : View.number(collection.get("calls")) + " calls");
        out.add(String.join(" | ", head));
        out.addAll(View.summary((Market) data.get("market")));
        out.add("---");
        out.addAll(strings(data.get("table")));
        Map<String, Object> dropped = Json.map(data.get("dropped"));
        if (!dropped.isEmpty()) {
            out.add("hidden from this view: " + dropped);
        }
        warnings.forEach(w -> out.add("!! " + w));
        if (data.get("next") != null) {
            out.add("- more: " + continuation + "(offset=" + data.get("next") + ") for the"
                    + " remaining " + data.get("rest"));
        }
        String stop = str(collection.get("stop"));
        if (stop != null && List.of("pages", "ceiling", "pace").contains(stop)) {
            out.add("- stopped on \"" + stop + "\": call search again to continue where it left off");
        }
        return out;
    }

    private long lastTouched(String slug) {
        try {
            return java.nio.file.Files.getLastModifiedTime(
                    store.dir(slug, false).resolve("search.json")).toMillis();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static double[] reference(String near) {
        if (near == null || near.isBlank()) {
            return null;
        }
        String[] bits = near.split(",");
        if (bits.length < 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(bits[0].trim()),
                    Double.parseDouble(bits[1].trim())};
        } catch (NumberFormatException e) {
            throw new LbcException(LbcException.Kind.NOT_FOUND,
                    "near wants \"latitude,longitude\", got \"" + near + "\"");
        }
    }

    private static String join(List<String> items) {
        return items == null || items.isEmpty() ? "none" : String.join(", ", items);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.list(value)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static int intOf(Object value) {
        Double d = Ads.num(value);
        return d == null ? 0 : d.intValue();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String pad(String s, int width) {
        String t = s == null ? "" : s;
        return t.length() >= width ? t.substring(0, width - 1) + " "
                : t + " ".repeat(width - t.length());
    }

    private static boolean matches(String expected, String seen) {
        String a = fold(expected);
        String b = fold(seen);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    /** One folding rule for the whole tool, in Query. */
    private static String fold(String s) {
        return Query.fold(s);
    }
}
