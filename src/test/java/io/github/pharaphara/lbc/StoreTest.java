package io.github.pharaphara.lbc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stable numbers, mandatory deduplication, nothing lost on read.
 *
 * <p>Deduplication is not a precaution: the site's api repeats ads, including
 * inside a single response. Measured once, three responses totalling 224 lines
 * carried only 120 distinct ids.
 */
class StoreTest {

    @TempDir
    Path tmp;

    private Store store() {
        return new Store(tmp.resolve("data"));
    }

    @Test
    void addDeduplicatesAndSetsAsideOtherCategories() {
        Store s = store();
        Store.Added a = s.add("trial", Fixtures.ads("finder-offset0.json"), "2");
        // 100 lines, one repeat inside the response, one ad from another section.
        assertEquals(98, a.fresh());
        assertEquals(1, a.duplicates());
        assertEquals(1, a.outOfCategory());
        assertEquals(98, a.lastNumber());
        assertTrue(java.nio.file.Files.isRegularFile(s.dir("trial", false).resolve("skipped.jsonl")));
    }

    @Test
    void numbersNeverMove() {
        Store s = store();
        s.add("trial", Fixtures.ads("finder-offset0.json"), "2");
        String before = s.byNumber("trial", "7").orElseThrow().id();
        Store.Added second = s.add("trial", Fixtures.ads("finder-offset100.json"), "2");
        assertEquals(20, second.fresh());
        assertEquals(4, second.duplicates());
        assertEquals(before, s.byNumber("trial", "7").orElseThrow().id());
        // 118 unique ads out of 124 lines read. The unique count is the real one.
        assertEquals(118, s.load("trial").size());
        assertTrue(s.byNumber("trial", "118").isPresent());
    }

    @Test
    void anAdIsAlsoReachableByItsSiteId() {
        Store s = store();
        s.add("trial", Fixtures.ads("finder-offset0.json"), "2");
        String id = s.byNumber("trial", "3").orElseThrow().id();
        assertEquals(3, s.byNumber("trial", id).orElseThrow().number());
    }

    @Test
    void aUnicodeSeparatorDoesNotCutAnAdInHalf() {
        // U+2028 and friends live inside real ad descriptions. A reader that
        // treats them as line breaks cuts a json line in two and loses the ad in
        // silence, which shows up as a hole in the numbering that nothing explains.
        Store s = store();
        List<Map<String, Object>> trap = List.of(
                Map.of("list_id", 1, "category_id", "2", "subject", "a",
                        "body", "line one line two", "price", List.of(100)),
                Map.of("list_id", 2, "category_id", "2", "subject", "b",
                        "body", "with\u0085another", "price", List.of(200)),
                Map.of("list_id", 3, "category_id", "2", "subject", "c",
                        "body", "and\u000bmore", "price", List.of(300)));
        assertEquals(3, s.add("trap", trap, "2").fresh());
        assertEquals(3, s.load("trap").size(), "an ad disappeared on read");
        assertEquals(0, s.unreadable("trap"));
        assertEquals("with\u0085another", Ads.body(s.byNumber("trap", "2").orElseThrow().ad()));
        assertEquals(3, s.index("trap").size());
    }

    @Test
    void theCurrentSearchIsRememberedNotGuessed() {
        Store s = store();
        assertThrows(LbcException.class, () -> s.resolve(null));
        s.create("trial", Map.of("demand", "trial", "url", "https://x"));
        s.setCurrent("trial");
        assertEquals("trial", s.resolve(null));
    }

    @Test
    void searchesAreListedNewestFirst() {
        Store s = store();
        s.create("one", Map.of("demand", "one"));
        s.add("one", Fixtures.ads("finder-offset0.json"), "2");
        s.setCurrent("one");
        List<Store.SearchInfo> all = s.searches();
        assertEquals(1, all.size());
        assertEquals(98, all.get(0).ads());
        assertTrue(all.get(0).current());
    }

    @Test
    void forgettingFreesTheRoom() {
        Store s = store();
        s.create("trial", Map.of("demand", "trial"));
        s.add("trial", Fixtures.ads("finder-offset0.json"), "2");
        Store.Forgotten f = s.forget("trial");
        assertTrue(f.removed());
        assertTrue(f.bytes() > 0);
        assertTrue(s.load("trial").isEmpty());
    }

    @Test
    void slugsStayApartForTwoCloseSearches() {
        String a = Store.slugify("electric cars", "https://x?category=2&fuel=4");
        String b = Store.slugify("electric cars", "https://x?category=2&fuel=2");
        assertFalse(a.equals(b), "two different urls must not share a folder");
        assertTrue(a.startsWith("electric-cars-"));
    }
}
