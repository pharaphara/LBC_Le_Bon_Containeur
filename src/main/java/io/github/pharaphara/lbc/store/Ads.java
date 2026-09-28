package io.github.pharaphara.lbc.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a leboncoin ad object holds, and how to read it safely.
 *
 * <p>One class knows the shape of the site's data so the rest of the code never
 * guesses. Two readings here were paid for in wrong results elsewhere.
 *
 * <p>{@code status} describes the PUBLICATION, not the object. It reads "active"
 * on every ad. Taking it for a condition once produced "condition: active" on a
 * whole corpus, which quietly reduced a ranking to price alone.
 *
 * <p>The site publishes the transaction state in plain words under the
 * {@code transaction_status} attribute ("Vendu", "Achat en cours"). That is
 * infinitely safer than guessing from the description.
 */
public final class Ads {

    private Ads() {
    }

    public record Place(String city, String zipcode, Double lat, Double lng) {
    }

    public record Owner(String name, boolean pro, String id, String siren, String store) {
    }

    /** A number out of whatever the site put there, or null. */
    public static Double num(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(value)
                .replace(" ", "")
                .replace(" ", "")
                .replace(',', '.')
                .replaceAll("[^\\d.\\-]", "");
        if (s.isEmpty() || s.equals("-") || s.equals(".")) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributeList(Map<String, Object> ad) {
        Object raw = ad.get("attributes");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    public static Object attr(Map<String, Object> ad, String key) {
        for (Map<String, Object> a : attributeList(ad)) {
            if (key.equals(a.get("key"))) {
                Object label = a.get("value_label");
                return label != null ? label : a.get("value");
            }
        }
        return null;
    }

    /** Every attribute, in the order the site gave them. */
    public static Map<String, String> attrs(Map<String, Object> ad) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> a : attributeList(ad)) {
            Object key = a.get("key");
            if (key == null) {
                continue;
            }
            Object label = a.get("value_label");
            Object value = label != null ? label : a.get("value");
            if (value != null) {
                out.put(String.valueOf(key), String.valueOf(value));
            }
        }
        return out;
    }

    public static Double price(Map<String, Object> ad) {
        Object p = ad.get("price");
        if (p instanceof List<?> list) {
            p = list.isEmpty() ? null : list.get(0);
        }
        return num(p);
    }

    public static String id(Map<String, Object> ad) {
        Object v = ad.get("list_id");
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static String title(Map<String, Object> ad) {
        Object v = ad.get("subject");
        if (v == null) {
            v = ad.get("title");
        }
        return v == null ? "" : String.valueOf(v);
    }

    public static String body(Map<String, Object> ad) {
        Object v = ad.get("body");
        return v == null ? "" : String.valueOf(v);
    }

    public static String categoryId(Map<String, Object> ad) {
        Object v = ad.get("category_id");
        return v == null ? "" : String.valueOf(v);
    }

    public static String categoryName(Map<String, Object> ad) {
        Object v = ad.get("category_name");
        return v == null ? "" : String.valueOf(v);
    }

    public static String url(Map<String, Object> ad) {
        Object v = ad.get("url");
        return v != null ? String.valueOf(v)
                : "https://www.leboncoin.fr/vi/" + id(ad) + ".htm";
    }

    @SuppressWarnings("unchecked")
    public static Place place(Map<String, Object> ad) {
        Object raw = ad.get("location");
        Map<String, Object> loc = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new Place(
                str(loc.get("city")), str(loc.get("zipcode")),
                num(loc.get("lat")), num(loc.get("lng")));
    }

    @SuppressWarnings("unchecked")
    public static Owner owner(Map<String, Object> ad) {
        Object raw = ad.get("owner");
        Map<String, Object> o = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        boolean pro = "pro".equalsIgnoreCase(String.valueOf(o.get("type")));
        return new Owner(str(o.get("name")), pro, str(o.get("user_id")),
                str(o.get("siren")), str(o.get("store_id")));
    }

    public static boolean isPro(Map<String, Object> ad) {
        return owner(ad).pro();
    }

    /** Read the published transaction state, never {@code status}. */
    public static boolean isSold(Map<String, Object> ad) {
        Object state = attr(ad, "transaction_status");
        if (state != null) {
            String s = String.valueOf(state).trim().toLowerCase();
            if (!s.isEmpty() && !s.equals("available") && !s.equals("disponible")) {
                return true;
            }
        }
        Object status = ad.get("status");
        String s = status == null ? "active" : String.valueOf(status).toLowerCase();
        return !(s.isEmpty() || s.equals("active"));
    }

    @SuppressWarnings("unchecked")
    public static List<String> images(Map<String, Object> ad) {
        Object raw = ad.get("images");
        Map<String, Object> box = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object urls = box.get("urls_large");
        if (urls == null) {
            urls = box.get("urls");
        }
        List<String> out = new ArrayList<>();
        if (urls instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public static String published(Map<String, Object> ad) {
        return str(ad.get("first_publication_date"));
    }

    public static String indexed(Map<String, Object> ad) {
        return str(ad.get("index_date"));
    }

    public static boolean shippable(Map<String, Object> ad) {
        return Boolean.TRUE.equals(ad.get("has_option_shipping"));
    }

    @SuppressWarnings("unchecked")
    public static Integer favourites(Map<String, Object> ad) {
        Object raw = ad.get("counters");
        Map<String, Object> c = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Double v = num(c.get("favorites"));
        return v == null ? null : v.intValue();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
