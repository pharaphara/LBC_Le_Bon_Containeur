package io.github.pharaphara.lbc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.github.pharaphara.lbc.render.View;
import io.github.pharaphara.lbc.stats.Market;
import io.github.pharaphara.lbc.store.Ads;
import io.github.pharaphara.lbc.store.Store.AdRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded output. This is what keeps a conversation from being buried. */
class ViewTest {

    private static final Pattern ENGLISH = Pattern.compile("[0-9],[0-9]{3}");

    private List<AdRecord> ads() {
        return Fixtures.records("finder-offset0.json");
    }

    @Test
    void anAdFitsOnOneLine() {
        for (List<String> cols : List.of(List.<String>of(), List.of("km", "year"))) {
            for (String line : View.table(ads(), cols)) {
                assertFalse(line.contains("\n"));
                assertTrue(line.length() <= View.WIDTH,
                        line.length() + " > " + View.WIDTH + ": " + line);
            }
        }
    }

    @Test
    void aDescriptionNeverAppearsInATable() {
        // Ad number 8 carries 6588 characters of description.
        List<String> lines = View.table(ads(), List.of("km", "year"));
        assertTrue(lines.stream().noneMatch(l -> l.contains("state of health")));
    }

    @Test
    void columnsFollowWhatIsBeingLookedAt() {
        assertEquals(List.of("km", "year"), View.columns("cars", List.of()));
        assertEquals(List.of(), View.columns("furniture", List.of()));
        // With no known category, look at what the ads actually carry.
        assertEquals(List.of("km", "year"), View.columns(null, ads()));
    }

    @Test
    void aSoldAdSaysSoEvenWithALongTitle() {
        AdRecord sold = ads().stream()
                .filter(r -> Ads.isSold(r.ad())).findFirst().orElseThrow();
        assertTrue(View.row(sold, List.of("km", "year")).contains("[sold]"));
    }

    @Test
    void theDetailViewPagesTheDescription() {
        AdRecord big = ads().stream()
                .filter(r -> Ads.body(r.ad()).length() > 6000).findFirst().orElseThrow();
        String view = View.ad(big, 0, 2000);
        assertTrue(view.contains("of 6588 characters"), view.substring(view.length() - 200));
        assertTrue(view.contains("offset=2000"));
        String rest = View.ad(big, 2000, 2000);
        assertFalse(rest.equals(view));
        // The list already carries what the ad page carries. Nothing to fetch.
        assertTrue(view.contains("mileage:"));
        assertTrue(view.contains("photos"));
    }

    @Test
    void noOutputCarriesAnEnglishSeparator() {
        // "19,994 EUR" has no business in a French listing. One formatter is the
        // only reason this holds, and this sweep is what keeps it true.
        List<String> rendered = new ArrayList<>(View.table(ads(), List.of("km", "year")));
        rendered.addAll(View.table(ads(), List.of()));
        rendered.addAll(View.summary(Market.of(
                Market.withDistances(ads(), new double[]{44.35, 2.57}), new double[]{44.35, 2.57})));
        rendered.add(View.ad(ads().get(7), 0, 500));
        for (String line : rendered) {
            assertFalse(ENGLISH.matcher(line).find(), "english separator in: " + line);
        }
    }

    @Test
    void bigNumbersGetASpaceNotAComma() {
        assertEquals("19 994", View.number(19994));
        assertEquals("1 234 567", View.number(1234567));
        assertEquals("999", View.number(999));
        assertEquals("--", View.number(null));
        assertEquals("19 995", View.number(19994.6));
        assertEquals("119 990 €", View.euros(119990));
    }

    @Test
    void aYearKeepsItsFourDigitsTogether() {
        // "median year: 2 024" means nothing at all.
        assertEquals("2024", View.year(2024));
        List<String> summary = View.summary(Market.of(ads(), null));
        String year = summary.stream().filter(l -> l.startsWith("median year")).findFirst().orElseThrow();
        assertFalse(year.contains("2 0"), year);
    }

    @Test
    void medianMileageIsReadable() {
        List<String> summary = View.summary(Market.of(ads(), null));
        String km = summary.stream().filter(l -> l.startsWith("median mileage"))
                .findFirst().orElseThrow();
        assertTrue(km.contains(" km"), km);
        assertFalse(ENGLISH.matcher(km).find(), km);
    }
}
