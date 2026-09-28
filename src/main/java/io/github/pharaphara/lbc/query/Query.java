package io.github.pharaphara.lbc.query;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.pharaphara.lbc.Json;
import io.github.pharaphara.lbc.LbcException;

/**
 * Turn a request into a leboncoin search URL, then read back what the site made
 * of it.
 *
 * <p>No filter name is ever invented. Every parameter in {@code filters.json}
 * was measured against the total the site announces. What the site does not
 * understand it drops in silence, which widens a search without telling anyone,
 * so {@link #reflect} compares our URL against the payload the site derived from
 * it and names what was thrown away. That is a validator, not a discoverer: it
 * confirms a filter we already know, it does not reveal the name of one we do
 * not. Finding a new one stays a human loop, through the browser.
 */
public class Query {

    public static final String SEARCH_URL = "https://www.leboncoin.fr/recherche";

    /** Sorts the site accepts in the URL. Price is missing on purpose: it was
     * never measured, and it is useless anyway since we collect everything and
     * sort offline for free. */
    private static final Map<String, String> SORTS = Map.of("relevance", "", "date", "time");

    /** URL parameter to the name the site gives it inside its own payload. */
    private static final Map<String, String> ALIAS =
            Map.of("text", "text", "category", "category", "locations", "locations", "sort", "sort_by");

    public record Category(String name, Integer id, String verified, String label,
                           List<Integer> candidates, List<String> aliases, String notes) {
    }

    public record Rule(String urlName, String form, Map<String, String> values) {
    }

    /** The URL, and the criteria that could not be translated. Never swallowed. */
    public record Built(String url, List<String> ignored) {
    }

    /** What the site kept of our URL, and what it threw away. */
    public record Reflection(List<String> kept, List<String> dropped, List<String> leaves) {
    }

    private final Path overrideDir;
    private final Map<String, Object> defaultCategories;
    private final Map<String, Object> filterTable;

    public Query(Path overrideDir) {
        this.overrideDir = overrideDir;
        this.defaultCategories = resource("categories.json");
        this.filterTable = resource("filters.json");
    }

    private static Map<String, Object> resource(String name) {
        try (InputStream in = Query.class.getResourceAsStream("/lbc/" + name)) {
            Objects.requireNonNull(in, "missing resource /lbc/" + name);
            return Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read /lbc/" + name, e);
        }
    }

    // ------------------------------------------------------------------ categories

    /** Shipped table, corrected by whatever probing has written to disk. */
    public Map<String, Category> categories() {
        Map<String, Object> merged = new LinkedHashMap<>(defaultCategories);
        Path file = overrideDir.resolve("categories.json");
        if (Files.isRegularFile(file)) {
            try {
                merged.putAll(Json.read(file));
            } catch (RuntimeException ignored) {
                // A corrupt override must not hide the shipped table.
            }
        }
        Map<String, Category> out = new LinkedHashMap<>();
        merged.forEach((name, raw) -> {
            if (name.startsWith("_") || !(raw instanceof Map<?, ?>)) {
                return;
            }
            Map<String, Object> c = Json.map(raw);
            List<Integer> candidates = new ArrayList<>();
            for (Object o : Json.list(c.get("candidates"))) {
                Double d = io.github.pharaphara.lbc.store.Ads.num(o);
                if (d != null) {
                    candidates.add(d.intValue());
                }
            }
            List<String> aliases = new ArrayList<>();
            for (Object o : Json.list(c.get("aliases"))) {
                aliases.add(String.valueOf(o));
            }
            Double id = io.github.pharaphara.lbc.store.Ads.num(c.get("id"));
            // The key is the site's own label, so there is nothing else to carry.
            out.put(name, new Category(name, id == null ? null : id.intValue(),
                    str(c.get("verified")), name, candidates, aliases,
                    str(c.get("notes"))));
        });
        return out;
    }

    /** The category under that name, an alias, or null. */
    public Category category(String name) {
        String wanted = fold(name);
        if (wanted.isEmpty()) {
            return null;
        }
        Map<String, Category> all = categories();
        for (Category c : all.values()) {
            if (fold(c.name()).equals(wanted)) {
                return c;
            }
        }
        for (Category c : all.values()) {
            for (String a : c.aliases()) {
                if (fold(a).equals(wanted)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * The measured id, or a refusal that says what to do.
     *
     * <p>A plain number is taken as an id and trusted, because the caller then
     * owns that choice. A name is only accepted once it has been probed.
     */
    public String categoryId(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.matches("\\d+")) {
            return raw;
        }
        Category c = category(raw);
        if (c == null) {
            throw new LbcException(LbcException.Kind.UNVERIFIED_CATEGORY,
                    "unknown category \"" + raw + "\"",
                    "the categories tool lists the ones that exist, and you can always pass a"
                            + " numeric category id instead");
        }
        if (c.verified() != null && c.id() != null) {
            return String.valueOf(c.id());
        }
        String candidates = c.candidates().isEmpty() ? "none left"
                : c.candidates().stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
        throw new LbcException(LbcException.Kind.UNVERIFIED_CATEGORY,
                "category \"" + c.name() + "\" is not verified (candidates: " + candidates + ")"
                        + (c.notes() == null ? "" : ". " + c.notes()),
                "run the categories tool with verify=[\"" + c.name() + "\"] first. A wrong id"
                        + " drops every ad in silence, so this refuses rather than lie.");
    }

    /** Write down what a probe found. Goes to disk, never to the shipped table. */
    public Category markCategory(String name, int id, String label, boolean ok) {
        String key = category(name) != null ? category(name).name() : norm(name);
        Map<String, Object> onDisk = new LinkedHashMap<>();
        Path file = overrideDir.resolve("categories.json");
        if (Files.isRegularFile(file)) {
            try {
                onDisk.putAll(Json.read(file));
            } catch (RuntimeException ignored) {
                onDisk.clear();
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        Category known = category(key);
        entry.put("label", label != null && !label.isBlank() ? label
                : known != null ? known.label() : key);
        if (known != null && !known.aliases().isEmpty()) {
            entry.put("aliases", known.aliases());
        }
        if (ok) {
            entry.put("id", id);
            entry.put("verified", LocalDate.now().toString());
        } else {
            entry.put("verified", null);
            entry.put("candidates", List.of());
            entry.put("notes", "id " + id + " answers \"" + label + "\", so it is not this category");
        }
        onDisk.put(key, entry);
        try {
            Files.createDirectories(overrideDir);
            Files.writeString(file, Json.pretty(onDisk));
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK,
                    "cannot write " + file + ": " + e,
                    "the data directory must be writable, otherwise a probe is forgotten");
        }
        return category(key);
    }

    // ------------------------------------------------------------------ url

    /**
     * A span in the site's own notation, with literal {@code min} and {@code max}.
     *
     * <p>{@code price=min-15000} is right where {@code price=0-15000} would drop
     * every ad that carries no price at all.
     */
    public static String span(Object low, Object high) {
        String lo = bound(low, "min");
        String hi = bound(high, "max");
        if (lo == null && hi == null) {
            return null;
        }
        return (lo == null ? "min" : lo) + "-" + (hi == null ? "max" : hi);
    }

    private static String bound(Object v, String ignoredLabel) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        Double d = io.github.pharaphara.lbc.store.Ads.num(v);
        return d == null ? null : String.valueOf((long) Math.rint(d));
    }

    /** {min, max} out of a map, "2020-", "-15000", "2020-2024" or a single value. */
    public static Object[] bounds(Object value) {
        if (value instanceof Map<?, ?> m) {
            return new Object[]{m.get("min"), m.get("max")};
        }
        if (value instanceof List<?> l && l.size() == 2) {
            return new Object[]{l.get(0), l.get(1)};
        }
        String s = String.valueOf(value).trim();
        if (s.startsWith("-") && s.length() > 1) {
            return new Object[]{null, s.substring(1)};
        }
        int dash = s.indexOf('-', 1);
        if (dash > 0) {
            String lo = s.substring(0, dash).trim();
            String hi = s.substring(dash + 1).trim();
            return new Object[]{lo.isEmpty() ? null : lo, hi.isEmpty() ? null : hi};
        }
        return new Object[]{s, s};
    }

    /**
     * A department, never coordinates.
     *
     * <p>A model once composed a {@code locations} value out of latitude and
     * longitude it had invented. The site answered zero ads, in silence, with no
     * error. Collect by department, then apply the exact radius yourself on each
     * ad's own coordinates.
     */
    public static String placeParam(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return "";
        }
        String low = v.toLowerCase(Locale.ROOT);
        if (low.startsWith("d_") || low.startsWith("r_")) {
            return low;
        }
        if (v.contains(",") || v.contains("_")) {
            throw new LbcException(LbcException.Kind.NOT_FOUND,
                    "no coordinates in locations: \"" + v + "\"",
                    "pass a postcode, \"d_12\" for a department, or \"r_<region>\"");
        }
        String digits = v.replaceAll("\\D", "");
        if (digits.length() >= 2) {
            String dep = digits.substring(0, 2);
            if (dep.equals("20")) {
                throw new LbcException(LbcException.Kind.NOT_FOUND,
                        "Corsica is coded 2a and 2b", "pass \"d_2a\" or \"d_2b\"");
            }
            if (dep.equals("97") || dep.equals("98")) {
                return "d_" + digits.substring(0, Math.min(3, digits.length()));
            }
            return "d_" + Integer.parseInt(dep);
        }
        throw new LbcException(LbcException.Kind.NOT_FOUND,
                "cannot read the place \"" + v + "\"",
                "pass a postcode, \"d_12\", or \"r_<region>\"");
    }

    private Map<String, Rule> rules(String category) {
        Map<String, Rule> out = new LinkedHashMap<>();
        out.putAll(rulesOf(Json.map(filterTable.get("_common"))));
        if (category != null && !category.isBlank()) {
            out.putAll(rulesOf(filterBlock(category)));
        }
        return out;
    }

    /** The filter block for a category, matched the same folded way. */
    private Map<String, Object> filterBlock(String category) {
        Category c = category(category);
        String wanted = fold(c != null ? c.name() : category);
        for (Map.Entry<String, Object> e : filterTable.entrySet()) {
            if (!e.getKey().startsWith("_") && fold(e.getKey()).equals(wanted)) {
                return Json.map(e.getValue());
            }
        }
        return Map.of();
    }

    private static Map<String, Rule> rulesOf(Map<String, Object> block) {
        Map<String, Rule> out = new LinkedHashMap<>();
        Json.map(block.get("params")).forEach((name, raw) -> {
            Map<String, Object> r = Json.map(raw);
            Map<String, String> values = new LinkedHashMap<>();
            Json.map(r.get("values")).forEach((k, v) -> values.put(fold(k), String.valueOf(v)));
            out.put(name, new Rule(String.valueOf(r.get("url")),
                    String.valueOf(r.getOrDefault("form", "text")), values));
        });
        return out;
    }

    /** What the site says about a category, traps included. */
    public Map<String, Object> filterHelp(String category) {
        Map<String, Object> out = new LinkedHashMap<>();
        Category c = category(category);
        Map<String, Object> block = filterBlock(category);
        out.put("category", c != null ? c.name() : category);
        out.put("verified", block.get("verified"));
        out.put("filters", new ArrayList<>(rules(category).keySet()));
        out.put("traps", Json.list(block.get("traps")));
        return out;
    }

    /**
     * The search URL, and the criteria left behind.
     *
     * <p>The ignored list is returned, never dropped: a criterion abandoned in
     * silence gives a wider search than asked for and nobody notices.
     */
    public Built buildUrl(String text, String category, Map<String, Object> criteria,
                          String place, String sort, Map<String, String> raw) {
        Map<String, String> params = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            params.put("text", text.trim());
        }
        if (category != null && !category.isBlank()) {
            params.put("category", categoryId(category));
        }
        if (place != null && !place.isBlank()) {
            String p = placeParam(place);
            if (!p.isEmpty()) {
                params.put("locations", p);
            }
        }
        Map<String, Rule> known = rules(category);
        if (criteria != null) {
            criteria.forEach((name, value) -> {
                if (value == null || String.valueOf(value).isBlank()) {
                    return;
                }
                Rule rule = known.get(name);
                if (rule == null) {
                    ignored.add(name);
                    return;
                }
                switch (rule.form()) {
                    case "span" -> {
                        Object[] b = bounds(value);
                        String s = span(b[0], b[1]);
                        if (s == null) {
                            ignored.add(name);
                        } else {
                            params.put(rule.urlName(), s);
                        }
                    }
                    case "enum" -> {
                        String code = rule.values().get(fold(String.valueOf(value)));
                        if (code == null) {
                            ignored.add(name + "=" + value);
                        } else {
                            params.put(rule.urlName(), code);
                        }
                    }
                    default -> params.put(rule.urlName(), String.valueOf(value));
                }
            });
        }
        if (sort != null && !sort.isBlank()) {
            String code = SORTS.get(norm(sort));
            if (code == null) {
                ignored.add("sort=" + sort + " (sort offline instead, with the results tool)");
            } else if (!code.isEmpty()) {
                params.put("sort", code);
            }
        }
        if (raw != null) {
            raw.forEach(params::put);
        }
        StringBuilder url = new StringBuilder(SEARCH_URL).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return new Built(url.toString(), ignored);
    }

    // ------------------------------------------------------------------ reflect

    /** The filter names present in the payload the site derived from our URL. */
    static List<String> leaves(Map<String, Object> payload) {
        Map<String, Object> filters = Json.map(Json.map(payload).get("filters"));
        TreeMap<String, Boolean> out = new TreeMap<>();
        for (String box : List.of("enums", "ranges", "attributes")) {
            Json.map(filters.get(box)).keySet().forEach(k -> out.put(k, true));
        }
        if (Json.map(filters.get("keywords")).get("text") != null) {
            out.put("text", true);
        }
        if (filters.get("category") != null) {
            out.put("category", true);
        }
        Json.map(filters.get("location")).keySet().forEach(k -> out.put(k, true));
        for (String k : List.of("owner_type", "shippable")) {
            if (filters.get(k) != null) {
                out.put(k, true);
            }
        }
        if (Json.map(payload).get("sort_by") != null) {
            out.put("sort_by", true);
        }
        return new ArrayList<>(out.keySet());
    }

    /**
     * What the site kept of our URL, and what it dropped.
     *
     * <p>A parameter missing from its payload was ignored, so the search is wider
     * than asked for. Mechanical, not a careful re read.
     */
    public static Reflection reflect(String url, Map<String, Object> payload) {
        List<String> leaves = leaves(payload);
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, String> e : queryOf(url).entrySet()) {
            String p = e.getKey();
            boolean ok;
            if (p.equals("sort")) {
                ok = String.valueOf(Json.map(payload).get("sort_by")).equals(e.getValue());
            } else {
                ok = leaves.contains(ALIAS.getOrDefault(p, p));
            }
            (ok ? kept : dropped).add(p);
        }
        kept.sort(null);
        dropped.sort(null);
        return new Reflection(kept, dropped, leaves);
    }

    static Map<String, String> queryOf(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = url == null ? -1 : url.indexOf('?');
        if (q < 0) {
            return out;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * What we must apply ourselves, and why.
     *
     * <p>Two sources: what the site threw away from our URL, and what we could
     * not translate into one of its filters. Either way the search came back
     * wider than asked for, and the table has to say so instead of letting it
     * pass.
     */
    public Map<String, Object> localFilter(Map<String, Object> criteria, String category,
                                           List<String> dropped, List<String> ignored) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (criteria == null || criteria.isEmpty()) {
            return out;
        }
        Map<String, Rule> known = rules(category);
        List<String> names = new ArrayList<>();
        if (dropped != null) {
            for (String urlName : dropped) {
                known.forEach((name, rule) -> {
                    if (rule.urlName().equals(urlName)) {
                        names.add(name);
                    }
                });
            }
        }
        if (ignored != null) {
            for (String raw : ignored) {
                names.add(raw.split("=")[0]);
            }
        }
        for (String name : names) {
            Object value = criteria.get(name);
            if (value == null) {
                continue;
            }
            if (name.equals("seller")) {
                out.put("seller", norm(String.valueOf(value)));
                continue;
            }
            Object[] b = bounds(value);
            Double lo = io.github.pharaphara.lbc.store.Ads.num(b[0]);
            Double hi = io.github.pharaphara.lbc.store.Ads.num(b[1]);
            String base = switch (name) {
                case "price" -> "price";
                case "mileage" -> "mileage";
                case "year" -> "year";
                case "seats" -> "seats";
                default -> null;
            };
            if (base == null) {
                continue;
            }
            if (lo != null) {
                out.put(base + "Min", lo);
            }
            if (hi != null) {
                out.put(base + "Max", hi);
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The form two category names are compared in.
     *
     * <p>Categories are keyed by the name the site itself displays, because
     * inventing an English key would be inventing something. That name carries
     * accents, ampersands and capitals, none of which anyone should have to type,
     * so matching happens on this folded form: "velos" finds "Vélos", and
     * "jeux jouets" finds "Jeux &amp; Jouets".
     */
    public static String fold(String s) {
        String t = java.text.Normalizer.normalize(norm(s), java.text.Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "");
        return t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
