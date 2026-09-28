package io.github.pharaphara.lbc.stats;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Store.AdRecord;

/**
 * The facts a corpus already holds. No weighting, no score, no network.
 *
 * <p>This tool reports, it does not judge. A market marker changes a decision
 * more reliably than any ranking: knowing the median sits at 29000 when you had
 * 15000 in mind settles the question on its own.
 */
public record Market(
        int count,
        Double priceMin, Double priceQ1, Double priceMedian, Double priceQ3, Double priceMax,
        int withoutPrice,
        int pro, int privateSellers, int sellersWithManyAds,
        int sold, int shippable,
        Double medianAgeDays, int under24h, int over30days, int bumped,
        int likelyDuplicates,
        Spread mileage, Spread year, Spread distance) {

    public record Spread(Double min, Double median, Double max) {
    }

    public record Group(String value, int count, Double medianPrice) {
    }

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

    public static Market of(List<AdRecord> ads, double[] ref) {
        List<Double> prices = new ArrayList<>();
        List<Double> ages = new ArrayList<>();
        List<Double> kms = new ArrayList<>();
        List<Double> years = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        Map<String, Integer> sellers = new LinkedHashMap<>();
        Map<String, Integer> prints = new LinkedHashMap<>();
        int pro = 0;
        int sold = 0;
        int shippable = 0;
        int bumped = 0;
        int under24 = 0;
        int over30 = 0;

        for (AdRecord rec : ads) {
            Map<String, Object> ad = rec.ad();
            Double p = Ads.price(ad);
            if (p != null) {
                prices.add(p);
            }
            if (Ads.isPro(ad)) {
                pro++;
            }
            if (Ads.isSold(ad)) {
                sold++;
            }
            if (Ads.shippable(ad)) {
                shippable++;
            }
            Double age = ageDays(Ads.published(ad));
            if (age != null) {
                ages.add(age);
                if (age < 1) {
                    under24++;
                }
                if (age > 30) {
                    over30++;
                }
            }
            LocalDateTime published = parse(Ads.published(ad));
            LocalDateTime indexed = parse(Ads.indexed(ad));
            if (published != null && indexed != null
                    && Duration.between(published, indexed).toDays() > 3) {
                bumped++;
            }
            Double km = Ads.num(Ads.attr(ad, "mileage"));
            if (km != null) {
                kms.add(km);
            }
            Double year = Ads.num(Ads.attr(ad, "regdate"));
            if (year != null) {
                years.add(year);
            }
            if (rec.kmApprox() != null) {
                distances.add(rec.kmApprox());
            }
            String owner = Ads.owner(ad).id();
            if (owner != null) {
                sellers.merge(owner, 1, Integer::sum);
            }
            String print = squash(Ads.title(ad)) + "|" + p + "|" + Ads.place(ad).city();
            prints.merge(print, 1, Integer::sum);
        }

        Double[] q = quartiles(prices);
        int twins = prints.values().stream().mapToInt(v -> v > 1 ? v - 1 : 0).sum();
        int many = (int) sellers.values().stream().filter(v -> v > 3).count();

        return new Market(ads.size(),
                min(prices), q[0], q[1], q[2], max(prices), ads.size() - prices.size(),
                pro, ads.size() - pro, many, sold, shippable,
                median(ages), under24, over30, bumped, twins,
                spread(kms), spread(years), spread(distances));
    }

    /** Median price per group: the beginning of a price guide, with no weighting. */
    public static List<Group> groupBy(List<AdRecord> ads, String key, int max) {
        Map<String, List<Double>> buckets = new LinkedHashMap<>();
        for (AdRecord rec : ads) {
            Map<String, Object> ad = rec.ad();
            String value = switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
                case "brand" -> first(Ads.attr(ad, "u_car_brand"), ad.get("brand"));
                case "year" -> first(Ads.attr(ad, "regdate"));
                case "city" -> first(Ads.place(ad).city());
                case "seller" -> first(Ads.owner(ad).name());
                case "category" -> first(Ads.categoryName(ad));
                default -> throw new IllegalArgumentException(
                        "unknown group \"" + key + "\": brand, year, city, seller, category");
            };
            buckets.computeIfAbsent(value, k -> new ArrayList<>()).add(Ads.price(ad));
        }
        List<Group> out = new ArrayList<>();
        buckets.forEach((value, prices) -> {
            List<Double> known = new ArrayList<>();
            prices.forEach(p -> {
                if (p != null) {
                    known.add(p);
                }
            });
            out.add(new Group(value, prices.size(), median(known)));
        });
        out.sort(Comparator.comparingInt(Group::count).reversed()
                .thenComparing(Group::value));
        return out.subList(0, Math.min(out.size(), Math.max(1, max)));
    }

    // ------------------------------------------------------------------ maths

    public static Double median(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        v.removeIf(java.util.Objects::isNull);
        if (v.isEmpty()) {
            return null;
        }
        v.sort(null);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }

    public static Double[] quartiles(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        v.removeIf(java.util.Objects::isNull);
        if (v.isEmpty()) {
            return new Double[]{null, null, null};
        }
        v.sort(null);
        List<Double> low = v.subList(0, v.size() / 2);
        List<Double> high = v.subList((v.size() + 1) / 2, v.size());
        return new Double[]{
                low.isEmpty() ? v.get(0) : median(low),
                median(v),
                high.isEmpty() ? v.get(v.size() - 1) : median(high)};
    }

    /**
     * Kilometres as the crow flies.
     *
     * <p>An approximation on purpose: the site blurs a private seller's
     * coordinates to the middle of their town. Hence the name kmApprox
     * everywhere else, and never a claim of precision.
     */
    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dp / 2), 2)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
        return Math.round(2 * r * Math.asin(Math.sqrt(a)) * 10) / 10.0;
    }

    /** Fill in kmApprox from a reference point, or leave it null. */
    public static List<AdRecord> withDistances(List<AdRecord> ads, double[] ref) {
        if (ref == null || ref.length < 2) {
            return ads;
        }
        List<AdRecord> out = new ArrayList<>(ads.size());
        for (AdRecord rec : ads) {
            Ads.Place p = Ads.place(rec.ad());
            out.add(p.lat() == null || p.lng() == null ? rec
                    : rec.withKm(haversine(ref[0], ref[1], p.lat(), p.lng())));
        }
        return out;
    }

    public static Double ageDays(String stamp) {
        LocalDateTime d = parse(stamp);
        if (d == null) {
            return null;
        }
        double days = Duration.between(d, LocalDateTime.now()).toMinutes() / 1440.0;
        return Math.round(days * 100) / 100.0;
    }

    static LocalDateTime parse(String stamp) {
        String s = stamp == null ? "" : stamp.trim().replace("Z", "");
        if (s.length() < 10) {
            return null;
        }
        if (s.length() == 10) {
            s = s + " 00:00:00";
        }
        s = s.substring(0, Math.min(19, s.length()));
        for (DateTimeFormatter f : FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (RuntimeException ignored) {
                // Try the next shape; the site has used both.
            }
        }
        return null;
    }

    private static Spread spread(List<Double> v) {
        return v.isEmpty() ? null : new Spread(min(v), median(v), max(v));
    }

    private static Double min(List<Double> v) {
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
    }

    private static Double max(List<Double> v) {
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String first(Object... candidates) {
        for (Object c : candidates) {
            if (c != null && !String.valueOf(c).isBlank()) {
                return String.valueOf(c);
            }
        }
        return "?";
    }
}
