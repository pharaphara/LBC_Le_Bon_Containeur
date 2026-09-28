package io.github.pharaphara.lbc.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.pharaphara.lbc.stats.Market;
import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Store.AdRecord;

/**
 * Bounded output, because a conversation must never swallow a corpus.
 *
 * <p>Three rules the tests keep: an ad fits on one line and never exceeds the
 * width, a description never appears inside a table, and whatever was cut says
 * so and gives the way to read the rest.
 */
public final class View {

    public static final int WIDTH = 100;

    private View() {
    }

    // ------------------------------------------------------------------ numbers

    /**
     * A number the French way: a space between thousands, never a comma.
     *
     * <p>One formatter for everything on screen, prices included. That is the
     * only reason no output carries an English separator, and a test checks every
     * rendered line for it.
     */
    public static String number(Object value) {
        Double v = Ads.num(value);
        if (v == null) {
            return "--";
        }
        return String.format(Locale.ROOT, "%,d", Math.round(v)).replace(',', ' ');
    }

    public static String euros(Object value) {
        Double v = Ads.num(value);
        return v == null ? "--" : number(v) + " €";
    }

    /** A year is not a quantity: "2 024" means nothing. */
    public static String year(Object value) {
        Double v = Ads.num(value);
        return v == null ? "--" : String.valueOf(Math.round(v));
    }

    public static String age(String stamp) {
        Double days = Market.ageDays(stamp);
        if (days == null) {
            return "--";
        }
        return days < 2 ? Math.round(days * 24) + " h" : Math.round(days) + " j";
    }

    // ------------------------------------------------------------------ table

    /** Vehicle columns only where they mean something. An empty column is noise. */
    public static List<String> columns(String category, List<AdRecord> ads) {
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (c.contains("car") || c.contains("van") || c.contains("motor")
                || c.contains("voiture") || c.contains("utilitaire")) {
            return List.of("km", "year");
        }
        int looked = 0;
        for (AdRecord rec : ads) {
            if (looked++ > 20) {
                break;
            }
            if (Ads.attr(rec.ad(), "mileage") != null) {
                return List.of("km", "year");
            }
        }
        return List.of();
    }

    private record Cell(String head, int width, boolean right) {
    }

    private static List<Cell> cells(List<String> columns) {
        List<Cell> out = new ArrayList<>();
        out.add(new Cell("n", 3, true));
        out.add(new Cell("price", 9, true));
        for (String c : columns) {
            out.add("km".equals(c) ? new Cell("km", 7, true) : new Cell("year", 4, true));
        }
        out.add(new Cell("place", 17, false));
        out.add(new Cell("age", 5, true));
        out.add(new Cell("by", 3, false));
        return out;
    }

    private static int titleRoom(List<Cell> cells) {
        int fixed = cells.stream().mapToInt(Cell::width).sum() + 2 * cells.size();
        return Math.max(20, WIDTH - fixed);
    }

    public static String header(List<String> columns) {
        List<Cell> cs = cells(columns);
        StringBuilder sb = new StringBuilder();
        for (Cell c : cs) {
            sb.append(Text.pad(c.head(), c.width(), c.right())).append("  ");
        }
        return sb.append("title").toString();
    }

    public static String row(AdRecord rec, List<String> columns) {
        Map<String, Object> ad = rec.ad();
        List<Cell> cs = cells(columns);
        StringBuilder sb = new StringBuilder();
        for (Cell c : cs) {
            String v = switch (c.head()) {
                case "n" -> String.valueOf(rec.number());
                case "price" -> euros(Ads.price(ad));
                case "km" -> number(Ads.num(Ads.attr(ad, "mileage")));
                case "year" -> year(Ads.num(Ads.attr(ad, "regdate")));
                case "place" -> place(ad);
                case "age" -> age(Ads.published(ad));
                case "by" -> Ads.isPro(ad) ? "PRO" : "P";
                default -> "--";
            };
            sb.append(Text.pad(v, c.width(), c.right())).append("  ");
        }
        // Marks go on AFTER the cut: a sold ad must stay visibly sold even when
        // its title is long, otherwise the mark disappears exactly when it counts.
        StringBuilder marks = new StringBuilder();
        if (Ads.isSold(ad)) {
            marks.append(" [sold]");
        }
        if (Ads.shippable(ad)) {
            marks.append(" [ship]");
        }
        if (rec.kmApprox() != null) {
            marks.append(" [").append(Math.round(rec.kmApprox())).append(" km]");
        }
        int room = titleRoom(cs);
        String title = Text.clip(Ads.title(ad), Math.max(6, room - marks.length())) + marks;
        sb.append(title.length() > room ? title.substring(0, room) : title);
        return sb.toString();
    }

    public static List<String> table(List<AdRecord> ads, List<String> columns) {
        List<String> out = new ArrayList<>();
        out.add(header(columns));
        for (AdRecord rec : ads) {
            out.add(row(rec, columns));
        }
        return out;
    }

    private static String place(Map<String, Object> ad) {
        Ads.Place p = Ads.place(ad);
        String city = p.city() == null ? "?" : p.city();
        String dep = p.zipcode() == null || p.zipcode().length() < 2 ? ""
                : p.zipcode().substring(0, 2);
        return dep.isEmpty() ? city : city + " (" + dep + ")";
    }

    // ------------------------------------------------------------------ summary

    /** Market markers first. That is what changes a decision. */
    public static List<String> summary(Market m) {
        List<String> out = new ArrayList<>();
        if (m.priceMedian() != null) {
            String line = "prices: median " + euros(m.priceMedian())
                    + ", middle half " + euros(m.priceQ1()) + " to " + euros(m.priceQ3())
                    + ", range " + euros(m.priceMin()) + " to " + euros(m.priceMax());
            if (m.withoutPrice() > 0) {
                line += ", " + m.withoutPrice() + " without a price";
            }
            out.add(line);
        }
        List<String> bits = new ArrayList<>();
        bits.add(m.privateSellers() + " private, " + m.pro() + " pro");
        if (m.sold() > 0) {
            bits.add(m.sold() + " sold");
        }
        if (m.medianAgeDays() != null) {
            bits.add("median age " + Math.round(m.medianAgeDays()) + " d");
        }
        if (m.under24h() > 0) {
            bits.add(m.under24h() + " under 24 h");
        }
        if (m.bumped() > 0) {
            bits.add(m.bumped() + " bumped since first posted");
        }
        if (m.sellersWithManyAds() > 0) {
            bits.add(m.sellersWithManyAds() + " sellers with more than 3 ads");
        }
        if (m.likelyDuplicates() > 0) {
            bits.add(m.likelyDuplicates() + " likely duplicate"
                    + (m.likelyDuplicates() > 1 ? "s" : ""));
        }
        out.add("market: " + String.join(", ", bits));
        spread(out, "median mileage", m.mileage(), " km", false);
        spread(out, "median year", m.year(), "", true);
        spread(out, "median distance", m.distance(), " km", false);
        return out;
    }

    private static void spread(List<String> out, String label, Market.Spread s,
                               String unit, boolean asYear) {
        if (s == null || s.median() == null) {
            return;
        }
        java.util.function.Function<Double, String> f = asYear ? View::year : View::number;
        out.add(label + ": " + f.apply(s.median()) + unit
                + " (from " + f.apply(s.min()) + " to " + f.apply(s.max()) + unit + ")");
    }

    // ------------------------------------------------------------------ one ad

    /**
     * Everything the site knows about an ad, bounded and paged.
     *
     * <p>No network: the search results already carry the whole description, the
     * attributes and the photos. Measured on one ad, 6588 characters of
     * description, exactly as many as on its own page.
     */
    public static String ad(AdRecord rec, int offset, int limit) {
        Map<String, Object> ad = rec.ad();
        Ads.Place p = Ads.place(ad);
        Ads.Owner o = Ads.owner(ad);
        List<String> out = new ArrayList<>();
        out.add("[" + rec.number() + "] " + Text.squeeze(Ads.title(ad))
                + "  |  " + euros(Ads.price(ad)));
        out.add(Ads.url(ad));

        List<String> bits = new ArrayList<>();
        bits.add((p.city() == null ? "?" : p.city())
                + (p.zipcode() == null ? "" : " (" + p.zipcode() + ")"));
        bits.add("posted " + age(Ads.published(ad)) + " ago");
        bits.add((o.pro() ? "pro" : "private") + ": " + (o.name() == null ? "?" : o.name()));
        if (rec.kmApprox() != null) {
            bits.add(Math.round(rec.kmApprox()) + " km away (approx)");
        }
        if (Ads.favourites(ad) != null) {
            bits.add(Ads.favourites(ad) + " favourites");
        }
        if (Ads.isSold(ad)) {
            bits.add("SOLD");
        }
        if (Ads.shippable(ad)) {
            bits.add("shipping offered");
        }
        out.add(String.join(" | ", bits));
        out.add("collected " + rec.seen()
                + (rec.fresh() == null ? "" : ", refreshed " + rec.fresh())
                + " | " + Ads.images(ad).size() + " photos");

        Map<String, String> attrs = Ads.attrs(ad);
        if (!attrs.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            attrs.forEach((k, v) -> {
                if (pairs.size() < 24 && v != null && !v.isBlank() && !"false".equals(v)) {
                    pairs.add(k + ": " + v);
                }
            });
            out.add("---");
            out.add(String.join(" ; ", pairs));
        }

        Text.Chunk body = Text.chunk(Ads.body(ad), offset, limit);
        if (!body.text().isEmpty()) {
            out.add("---");
            out.add(body.text());
        }
        if (body.next() != null) {
            out.add("- more: ad(number=" + rec.number() + ", offset=" + body.next()
                    + ") for the remaining " + body.rest() + " of " + body.total() + " characters");
        }
        return String.join("\n", out);
    }
}
