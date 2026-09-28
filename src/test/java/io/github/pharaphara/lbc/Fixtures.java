package io.github.pharaphara.lbc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.pharaphara.lbc.store.Store.AdRecord;

/** Frozen samples, taken from real responses and trimmed. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            Objects.requireNonNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> object(String name) {
        return Json.object(read(name));
    }

    /** The ads of a finder response. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> ads(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.list(object(name).get("ads"))) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    /** The same ads, numbered as the store would number them. */
    public static List<AdRecord> records(String name) {
        List<AdRecord> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> ad : ads(name)) {
            out.add(new AdRecord(++n, io.github.pharaphara.lbc.store.Ads.id(ad),
                    "2026-09-28 10:00:00", null, ad, null));
        }
        return out;
    }
}
