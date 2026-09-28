package io.github.pharaphara.lbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.pharaphara.lbc.stats.Market;
import io.github.pharaphara.lbc.store.Store.AdRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The markers, on values we know. No weighting, no network. */
class MarketTest {

    private List<AdRecord> ads() {
        return Fixtures.records("finder-offset0.json");
    }

    @Test
    void medianAndQuartilesSurviveHoles() {
        assertNull(Market.median(List.of()));
        assertEquals(2.0, Market.median(List.of(3.0, 1.0, 2.0)));
        assertEquals(2.5, Market.median(List.of(4.0, 1.0, 2.0, 3.0)));
        Double[] q = Market.quartiles(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0));
        assertEquals(2.5, q[0]);
        assertEquals(4.5, q[1]);
        assertEquals(6.5, q[2]);
        List<Double> withHoles = new ArrayList<>(List.of(10.0, 30.0));
        withHoles.add(null);
        assertEquals(20.0, Market.median(withHoles));
    }

    @Test
    void ageIsCountedInDays() {
        assertNull(Market.ageDays(""));
        assertNull(Market.ageDays("not a date"));
        assertTrue(Market.ageDays("2026-09-01 08:00:00") > 0);
    }

    @Test
    void haversineOnTwoKnownPoints() {
        // Millau to Rodez, about forty five kilometres as the crow flies.
        double d = Market.haversine(44.098, 3.078, 44.349, 2.575);
        assertTrue(d > 35 && d < 60, "got " + d);
    }

    @Test
    void distancesAreFilledInOrLeftUnknown() {
        List<AdRecord> ads = Market.withDistances(ads(), new double[]{44.35, 2.57});
        assertTrue(ads.stream().allMatch(r -> r.kmApprox() != null));
        List<AdRecord> none = Market.withDistances(ads(), null);
        assertTrue(none.stream().allMatch(r -> r.kmApprox() == null));
    }

    @Test
    void theSummaryReportsWhatTheCorpusHolds() {
        Market m = Market.of(ads(), null);
        assertEquals(100, m.count());
        assertTrue(m.priceMin() < m.priceMedian() && m.priceMedian() < m.priceMax());
        assertEquals(100, m.pro() + m.privateSellers());
        assertEquals(1, m.sold());
        assertTrue(m.mileage().median() > 0);
        assertTrue(m.year().median() >= 2015);
        // The repeat inside the response must show up as a likely duplicate.
        assertTrue(m.likelyDuplicates() >= 1);
        // Sellers with more than three ads: the pro hiding as a private seller.
        assertTrue(m.sellersWithManyAds() >= 1);
    }

    @Test
    void bumpedAdsAreCounted() {
        List<AdRecord> two = List.of(
                record(1, "2026-08-01 10:00:00", "2026-09-20 10:00:00"),
                record(2, "2026-09-20 10:00:00", "2026-09-20 11:00:00"));
        assertEquals(1, Market.of(two, null).bumped());
    }

    private static AdRecord record(int n, String published, String indexed) {
        Map<String, Object> ad = Map.of("list_id", n, "subject", "x",
                "first_publication_date", published, "index_date", indexed,
                "price", List.of(100));
        return new AdRecord(n, String.valueOf(n), "now", null, ad, null);
    }

    @Test
    void groupingGivesAMedianPricePerGroup() {
        List<Market.Group> groups = Market.groupBy(ads(), "brand", 8);
        assertTrue(!groups.isEmpty());
        assertTrue(groups.stream().allMatch(g -> g.count() > 0));
        for (int i = 1; i < groups.size(); i++) {
            assertTrue(groups.get(i - 1).count() >= groups.get(i).count(), "biggest group first");
        }
    }
}
