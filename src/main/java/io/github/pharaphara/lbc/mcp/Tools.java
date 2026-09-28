package io.github.pharaphara.lbc.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.github.pharaphara.lbc.Json;
import io.github.pharaphara.lbc.Lbc;
import io.github.pharaphara.lbc.LbcException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The tools an assistant sees. Their descriptions are the manual it reads, so
 * they say what was measured rather than what would sound good.
 */
@Component
public class Tools {

    private final Lbc lbc;

    public Tools(Lbc lbc) {
        this.lbc = lbc;
    }

    @McpTool(name = "search", description = """
            Search leboncoin and collect the results. Renders the results page once, then pages
            through the rest by asking the site for data the way the page itself does, so a few
            hundred ads cost one render and a handful of calls.

            Returns market markers first (median price, middle half, age, share of professionals)
            then a compact table, at most `rows` lines. Every ad keeps the number it is given
            here for good: `ad(number=7)` means the same ad later in the conversation, after more
            pages and after any sort.

            Filters: `category` and `place` and `priceMin`/`priceMax` are the common ones. Anything
            else goes in `extra`, for example {"mileage": "-80000", "year": "2020-", "fuel":
            "electrique", "seller": "particulier", "brand": "RENAULT", "seats": 5}. Values take
            the site's own words or their English equivalent. A span is written "min-max",
            "2020-" or "-15000". Whatever the site has no filter for comes back in the
            warnings and is applied here instead, never dropped in silence.

            The most reliable entry is `url`: build the search by hand in the browser, copy the
            address, pass it here, and nothing needs translating.

            Read the lines starting with !! . They say when the site widened the search, ignored a
            filter, or when the collection stopped before the end.""")
    public String search(
            @McpToolParam(description = "Free text, as you would type it in the search box",
                    required = false) String query,
            @McpToolParam(description = "The category, named the way leboncoin names it:"
                    + " \"Voitures\", \"Vélos\", \"Jeux & Jouets\", \"Ameublement\"..."
                    + " Case, accents and punctuation do not matter, a few English words work"
                    + " too, and a numeric id always works. Call the categories tool for the"
                    + " list. A name nobody measured is refused on purpose.",
                    required = false) String category,
            @McpToolParam(description = "Postcode, or \"d_12\" for a whole department. Never"
                    + " coordinates: the site silently returns nothing for those.",
                    required = false) String place,
            @McpToolParam(description = "Lowest price", required = false) Integer priceMin,
            @McpToolParam(description = "Highest price", required = false) Integer priceMax,
            @McpToolParam(description = "Any other filter, see the description",
                    required = false) Map<String, Object> extra,
            @McpToolParam(description = "A ready made search url, which bypasses all translation",
                    required = false) String url,
            @McpToolParam(description = "\"latitude,longitude\" to get a distance per ad. It is an"
                    + " approximation: the site blurs a private seller to the middle of their town.",
                    required = false) String near,
            @McpToolParam(description = "\"relevance\" or \"date\". Sorting by price is done"
                    + " offline with the results tool.", required = false) String sort,
            @McpToolParam(description = "Pages of 100 ads to collect, 3 by default",
                    required = false) Integer pages,
            @McpToolParam(description = "Collect again even if this search is recent. Without it, a"
                    + " search repeated within twelve hours is served from disk with no network at all.",
                    required = false) Boolean refresh,
            @McpToolParam(description = "Name this search, to keep several apart",
                    required = false) String name,
            @McpToolParam(description = "Lines to show, 25 by default", required = false) Integer rows,
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        Map<String, Object> criteria = new LinkedHashMap<>(extra == null ? Map.of() : extra);
        if (priceMin != null || priceMax != null) {
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("min", priceMin);
            span.put("max", priceMax);
            criteria.put("price", span);
        }
        return run(() -> lbc.search(url, query, category, place, near, criteria, sort,
                or(pages, 3), 0, Boolean.TRUE.equals(refresh), name, or(rows, 25)), format);
    }

    @McpTool(name = "results", description = """
            Read the ads already collected, with no network at all. Filter and sort them as many
            times as you like: this only touches the file on disk.

            Numbers do not change, so the table can be sorted by price without `ad(number=7)`
            meaning something else afterwards. Sold ads are hidden by default and counted in the
            line about what was hidden, because what something actually sold for is worth knowing.""")
    public String results(
            @McpToolParam(description = "number, price, mileage, year, date or distance",
                    required = false) String sort,
            @McpToolParam(description = "Highest price", required = false) Integer priceMax,
            @McpToolParam(description = "Highest mileage", required = false) Integer mileageMax,
            @McpToolParam(description = "Earliest year", required = false) Integer yearMin,
            @McpToolParam(description = "\"private\" or \"pro\"", required = false) String seller,
            @McpToolParam(description = "A word that must appear in the title or the description",
                    required = false) String contains,
            @McpToolParam(description = "Show sold ads too", required = false) Boolean sold,
            @McpToolParam(description = "Highest approximate distance in km, needs `near` on the"
                    + " search", required = false) Integer distanceMax,
            @McpToolParam(description = "Where to start, for reading past the first page",
                    required = false) Integer offset,
            @McpToolParam(description = "Lines to show, 25 by default", required = false) Integer rows,
            @McpToolParam(description = "Which search, the current one by default",
                    required = false) String name,
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        Map<String, Object> filter = new LinkedHashMap<>();
        put(filter, "priceMax", priceMax);
        put(filter, "mileageMax", mileageMax);
        put(filter, "yearMin", yearMin);
        put(filter, "seller", seller);
        put(filter, "contains", contains);
        put(filter, "sold", sold);
        put(filter, "distanceMax", distanceMax);
        return run(() -> lbc.results(name, sort, filter, or(offset, 0), or(rows, 25)), format);
    }

    @McpTool(name = "ad", description = """
            Everything the site knows about one ad: the whole description, every attribute, the
            seller, the photo count.

            This does NOT touch the network. The search results already carry all of it, which was
            measured: 6588 characters of description in the list, exactly as many as on the ad's
            own page. So detail is free, and worth asking for on two or three ads rather than
            skimming twenty rows.

            Pass refresh=true to load the ad's page again and find out whether the price moved or
            it has been sold since. That is the only thing a visit adds.""")
    public String ad(
            @McpToolParam(description = "The number from the table, or the site's own ad id")
            String number,
            @McpToolParam(description = "Check the ad on the site again and report what changed",
                    required = false) Boolean refresh,
            @McpToolParam(description = "Where to start in the description, for long ones",
                    required = false) Integer offset,
            @McpToolParam(description = "Characters of description to return, 2000 by default",
                    required = false) Integer chars,
            @McpToolParam(description = "Which search, the current one by default",
                    required = false) String name,
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        return run(() -> lbc.ad(name, number, Boolean.TRUE.equals(refresh),
                or(offset, 0), or(chars, 2000)), format);
    }

    @McpTool(name = "stats", description = """
            The market, in numbers, from what is already on disk. Median and quartiles, share of
            professionals, how many are sold, median age, how many were bumped back to the top
            (which says an asking price is not working), sellers with several ads, likely
            duplicates, and medians for mileage and year.

            Group by brand, year, city, seller or category to get a median price per group, which
            is the beginning of a price guide. This tool reports and does not score: no weighting,
            no ranking, no opinion.""")
    public String stats(
            @McpToolParam(description = "brand, year, city, seller or category",
                    required = false) String group,
            @McpToolParam(description = "Same keys as the results tool, to measure a subset",
                    required = false) Map<String, Object> filter,
            @McpToolParam(description = "Which search, the current one by default",
                    required = false) String name,
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        return run(() -> lbc.stats(name, filter, group), format);
    }

    @McpTool(name = "filters", description = """
            What the site keeps of a search url, and what it throws away.

            Useful when a search comes back wider than you asked for. The results page carries the
            query the site derived from the url, so this compares the two and names every parameter
            that was ignored. It confirms a filter, it cannot discover the name of one nobody
            knows: for that, apply the filter by hand in the browser and pass the resulting url
            here.

            Never judge a filter by the number of ads on the first page, which is always around
            35. Judge it by the total the site announces, which this returns.""")
    public String filters(
            @McpToolParam(description = "The url to examine", required = false) String url,
            @McpToolParam(description = "Or a category, to build one", required = false) String category,
            @McpToolParam(description = "Criteria to put in it", required = false) Map<String, Object> extra,
            @McpToolParam(description = "Postcode or d_12", required = false) String place,
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        return run(() -> lbc.filters(url, category, extra, place), format);
    }

    @McpTool(name = "categories", description = """
            List the categories, and measure any id on demand.

            They are named the way leboncoin names them, because inventing an English key would be
            inventing something. Matching ignores case, accents and punctuation.

            Category ids are measured here, never copied from somewhere else. Ads carry their own
            category name, which makes a free oracle: probing an id and reading the label back says
            whether it is the right one. An unverified id is refused rather than used, because a
            wrong one drops every ad in silence and looks exactly like an empty market.

            Some ids are groups of sections rather than a section. The probe spots those too: the
            ads come back under several different labels.""")
    public String categories(
            @McpToolParam(description = "Category names to probe on the site, for example"
                    + " [\"gardening\"]. Each probe renders one page.", required = false)
            List<String> verify,
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        return run(() -> lbc.categories(verify == null ? List.of() : verify), format);
    }

    @McpTool(name = "searches", description = """
            The searches kept on disk, newest first, with how many ads each holds and why its
            collection stopped. The starred one is what the other tools use when no name is given.""")
    public String searches(
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        return run(lbc::searches, format);
    }

    @McpTool(name = "forget", description = """
            Delete a search and its ads. Use it when a search was a dead end, or to free the
            disk.""")
    public String forget(
            @McpToolParam(description = "Which search, the current one by default",
                    required = false) String name) {
        return run(() -> lbc.forget(name), "text");
    }

    @McpTool(name = "status", description = """
            How the container is set up: which browser it drives, where the data lives, and the
            address to open when a wall needs a human click. Worth calling once if a tool reports a
            wall or an empty result you did not expect.""")
    public String status(
            @McpToolParam(description = "\"text\" (default) or \"json\"", required = false) String format) {
        return run(lbc::status, format);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Turn a refusal into something an assistant can act on.
     *
     * <p>A wall is not a crash: it is a state with a way out. Handing back a stack
     * trace would only invite a retry loop, which is exactly what must not happen.
     */
    private String run(Supplier<Lbc.Result> work, String format) {
        try {
            Lbc.Result result = work.get();
            if ("json".equalsIgnoreCase(format)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("state", result.state());
                out.putAll(result.data());
                return Json.pretty(out);
            }
            List<String> lines = new ArrayList<>();
            if (!"ok".equals(result.state())) {
                lines.add("state: " + result.state());
            }
            lines.addAll(result.lines());
            return String.join("\n", lines);
        } catch (LbcException e) {
            List<String> lines = new ArrayList<>();
            lines.add("state: " + e.state());
            lines.add(e.getMessage());
            if (e.advice() != null) {
                lines.add(e.advice());
            }
            return String.join("\n", lines);
        } catch (RuntimeException e) {
            return "state: error\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
