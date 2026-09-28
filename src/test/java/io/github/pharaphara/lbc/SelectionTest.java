package io.github.pharaphara.lbc;

import java.util.List;
import java.util.Map;

import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Selection;
import io.github.pharaphara.lbc.store.Store.AdRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filtering and sorting, offline, and always saying what was removed. */
class SelectionTest {

    private List<AdRecord> ads() {
        return Fixtures.records("finder-offset0.json");
    }

    @Test
    void soldAdsAreHiddenButCountedNotErased() {
        Selection.Kept kept = Selection.filter(ads(), Map.of());
        assertEquals(1, kept.dropped().get("sold"));
        assertEquals(ads().size() - 1, kept.ads().size());
        // A sold ad says what something really goes for, so it stays available.
        assertEquals(ads().size(), Selection.filter(ads(), Map.of("sold", true)).ads().size());
    }

    @Test
    void filtersReportEveryRemoval() {
        Selection.Kept kept = Selection.filter(ads(), Map.of("priceMax", 10000));
        assertTrue(kept.ads().stream().allMatch(r -> Ads.price(r.ad()) <= 10000));
        assertTrue(kept.dropped().getOrDefault("price", 0) > 0);
    }

    @Test
    void sellerFilterWorksBothWays() {
        assertTrue(Selection.filter(ads(), Map.of("seller", "private")).ads().stream()
                .noneMatch(r -> Ads.isPro(r.ad())));
        assertTrue(Selection.filter(ads(), Map.of("seller", "pro")).ads().stream()
                .allMatch(r -> Ads.isPro(r.ad())));
    }

    @Test
    void aWordIsLookedForInTitleAndBody() {
        Selection.Kept kept = Selection.filter(ads(), Map.of("contains", "AUDI"));
        assertEquals(1, kept.ads().size());
        assertTrue(kept.dropped().get("contains") > 0);
    }

    @Test
    void sortingByPriceLeavesMissingValuesLast() {
        List<AdRecord> sorted = Selection.sort(ads(), "price");
        Double first = Ads.price(sorted.get(0).ad());
        Double last = Ads.price(sorted.get(sorted.size() - 1).ad());
        assertTrue(first <= last);
        // Sorting must not renumber anything.
        assertTrue(sorted.stream().anyMatch(r -> r.number() == 7));
    }

    @Test
    void theWindowSaysWhereToContinue() {
        Selection.Window w = Selection.window(ads(), 0, 25);
        assertEquals(25, w.ads().size());
        assertEquals(25, w.next());
        Selection.Window end = Selection.window(ads(), 90, 25);
        assertNull(end.next());
        assertEquals(0, end.rest());
    }
}
