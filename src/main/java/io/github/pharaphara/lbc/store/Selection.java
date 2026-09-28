package io.github.pharaphara.lbc.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.pharaphara.lbc.store.Store.AdRecord;

/**
 * Filtering and sorting, offline and always.
 *
 * <p>{@link #filter} always reports what it removed and why. A silent filter is
 * a lie: a table that dropped a third of its rows without saying so reads exactly
 * like a thin market.
 */
public final class Selection {

    private Selection() {
    }

    public record Kept(List<AdRecord> ads, Map<String, Integer> dropped) {
    }

    public record Window(List<AdRecord> ads, int offset, int limit, Integer next, int rest) {
    }

    public static Kept filter(List<AdRecord> ads, Map<String, Object> raw) {
        Map<String, Object> f = raw == null ? Map.of() : raw;
        Map<String, Integer> dropped = new LinkedHashMap<>();
        boolean showSold = truthy(f.get("sold"));
        List<AdRecord> kept = new ArrayList<>();

        for (AdRecord rec : ads) {
            Map<String, Object> ad = rec.ad();
            if (!showSold && Ads.isSold(ad)) {
                bump(dropped, "sold");
                continue;
            }
            Double price = Ads.price(ad);
            if (below(price, f.get("priceMax")) || above(price, f.get("priceMin"))) {
                bump(dropped, price == null ? "no_price" : "price");
                continue;
            }
            Double km = Ads.num(Ads.attr(ad, "mileage"));
            if (below(km, f.get("mileageMax")) || above(km, f.get("mileageMin"))) {
                bump(dropped, km == null ? "no_mileage" : "mileage");
                continue;
            }
            Double year = Ads.num(Ads.attr(ad, "regdate"));
            if (below(year, f.get("yearMax")) || above(year, f.get("yearMin"))) {
                bump(dropped, year == null ? "no_year" : "year");
                continue;
            }
            Double seats = Ads.num(Ads.attr(ad, "seats"));
            if (above(seats, f.get("seatsMin"))) {
                bump(dropped, "seats");
                continue;
            }
            String seller = str(f.get("seller"));
            if (seller != null) {
                boolean pro = Ads.isPro(ad);
                if ((seller.startsWith("pro") && !pro) || (seller.startsWith("priv") && pro)) {
                    bump(dropped, "seller");
                    continue;
                }
            }
            String needle = str(f.get("contains"));
            if (needle != null && !needle.isBlank()) {
                String hay = (Ads.title(ad) + " " + Ads.body(ad)).toLowerCase(Locale.ROOT);
                if (!hay.contains(needle.toLowerCase(Locale.ROOT))) {
                    bump(dropped, "contains");
                    continue;
                }
            }
            Object maxKm = f.get("distanceMax");
            if (maxKm != null) {
                Double d = rec.kmApprox();
                if (d == null || above(null, null) || d > Ads.num(maxKm)) {
                    bump(dropped, "distance");
                    continue;
                }
            }
            kept.add(rec);
        }
        return new Kept(kept, dropped);
    }

    /** Missing values sort last, so a table never opens on a row of dashes. */
    public static List<AdRecord> sort(List<AdRecord> ads, String key) {
        String k = key == null ? "number" : key.trim().toLowerCase(Locale.ROOT);
        Comparator<AdRecord> by = switch (k) {
            case "price" -> nullsLast(r -> Ads.price(r.ad()));
            case "mileage", "km" -> nullsLast(r -> Ads.num(Ads.attr(r.ad(), "mileage")));
            case "year" -> nullsLast(r -> negate(Ads.num(Ads.attr(r.ad(), "regdate"))));
            case "distance" -> nullsLast(AdRecord::kmApprox);
            case "date" -> Comparator.comparing(
                    (AdRecord r) -> String.valueOf(Ads.published(r.ad())),
                    Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
            default -> Comparator.comparingInt(AdRecord::number);
        };
        List<AdRecord> out = new ArrayList<>(ads);
        out.sort(by);
        return out;
    }

    public static Window window(List<AdRecord> ads, int offset, int limit) {
        int from = Math.max(0, offset);
        int max = Math.max(1, Math.min(limit, 200));
        if (from > ads.size()) {
            from = ads.size();
        }
        List<AdRecord> slice = ads.subList(from, Math.min(ads.size(), from + max));
        int end = from + slice.size();
        return new Window(new ArrayList<>(slice), from, max,
                end < ads.size() ? end : null, Math.max(0, ads.size() - end));
    }

    // ------------------------------------------------------------------ plumbing

    private static Comparator<AdRecord> nullsLast(java.util.function.Function<AdRecord, Double> f) {
        return Comparator.comparing(f, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Double negate(Double v) {
        return v == null ? null : -v;
    }

    private static boolean below(Double value, Object limit) {
        Double max = Ads.num(limit);
        if (max == null) {
            return false;
        }
        return value == null || value > max;
    }

    private static boolean above(Double value, Object limit) {
        Double min = Ads.num(limit);
        if (min == null) {
            return false;
        }
        return value == null || value < min;
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    private static boolean truthy(Object v) {
        return v != null && !"false".equalsIgnoreCase(String.valueOf(v)) && !"0".equals(String.valueOf(v));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
