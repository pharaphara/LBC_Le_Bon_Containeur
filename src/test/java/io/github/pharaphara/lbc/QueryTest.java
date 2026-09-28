package io.github.pharaphara.lbc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.pharaphara.lbc.query.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building the URL, and reading back what the site made of it.
 *
 * <p>What is locked here cost something elsewhere: span bounds that exclude ads
 * without a price, coordinates in locations that return nothing at all, and a
 * category id copied from one place to another without ever being measured.
 */
class QueryTest {

    @TempDir
    Path tmp;

    /** A test must not depend on probing already done on a real disk. */
    private Query query() {
        return new Query(tmp.resolve("empty"));
    }

    @Test
    void spanKeepsLiteralBounds() {
        // "0-15000" would drop every ad that carries no price. The site takes "min".
        assertEquals("min-15000", Query.span(null, 15000));
        assertEquals("2020-max", Query.span(2020, null));
        assertNull(Query.span(null, null));
    }

    @Test
    void boundsAcceptSeveralSpellings() {
        Object[] fromMap = Query.bounds(Map.of("min", 1, "max", 2));
        assertEquals("1", String.valueOf(fromMap[0]));
        assertEquals("2", String.valueOf(fromMap[1]));
        assertEquals("2020", String.valueOf(Query.bounds("2020-")[0]));
        assertNull(Query.bounds("2020-")[1]);
        assertNull(Query.bounds("-15000")[0]);
        assertEquals("15000", String.valueOf(Query.bounds("-15000")[1]));
        assertEquals("2024", String.valueOf(Query.bounds("2020-2024")[1]));
    }

    @Test
    void placeBecomesADepartment() {
        assertEquals("d_12", Query.placeParam("12000"));
        assertEquals("d_12", Query.placeParam("d_12"));
        assertEquals("d_12", Query.placeParam("12"));
    }

    @Test
    void placeRefusesCoordinates() {
        // A model once composed "Severac_12150__44.3_2.9" and the site answered
        // zero ads, in silence, with no error at all.
        assertThrows(LbcException.class, () -> Query.placeParam("44.35,2.57"));
        assertThrows(LbcException.class, () -> Query.placeParam("Severac_12150__44.3_2.9"));
    }

    @Test
    void theShippedNamesAreAllMeasured() {
        assertEquals("2", query().categoryId("cars"));
        assertEquals("2", query().categoryId("voitures"), "the French name is an alias");
        assertEquals("55", query().categoryId("bikes"));
        assertEquals("41", query().categoryId("toys"), "41 is Jeux & Jouets, 40 is Collection");
        assertEquals("52", query().categoryId("garden"), "52 is Jardin & Plantes");
    }

    @Test
    void anUnmeasuredNameIsRefusedWithSomethingToDo() throws java.io.IOException {
        // Built here rather than taken from the shipped table, so the test does not
        // start failing the day that category gets measured.
        java.nio.file.Path dir = tmp.resolve("own");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("categories.json"),
                "{\"tractors\": {\"candidates\": [70], \"verified\": null,"
                        + " \"label\": \"Tracteurs\"}}");
        Query own = new Query(dir);
        LbcException e = assertThrows(LbcException.class, () -> own.categoryId("tractors"));
        assertEquals(LbcException.Kind.UNVERIFIED_CATEGORY, e.kind());
        assertTrue(e.getMessage().contains("70"), e.getMessage());
        // The refusal must say what to do, not only that it refuses.
        assertTrue(e.advice().contains("categories"), e.advice());
    }

    @Test
    void theSitesOwnNameIsTheName() {
        // Keyed by what leboncoin displays, because inventing an English key would
        // be inventing something. Matching ignores case, accents and punctuation.
        assertEquals("41", query().categoryId("Jeux & Jouets"));
        assertEquals("41", query().categoryId("jeux jouets"));
        assertEquals("55", query().categoryId("V\u00e9los"));
        assertEquals("55", query().categoryId("velos"));
        assertEquals("16", query().categoryId("Photo, audio & vid\u00e9o"));
        // And a few English words are kept as a convenience, not as the truth.
        assertEquals("2", query().categoryId("cars"));
    }

    @Test
    void aNumericIdIsTrusted() {
        assertEquals("999", query().categoryId("999"));
    }

    @Test
    void unknownCategoryDiffersFromUnverifiedOne() {
        LbcException e = assertThrows(LbcException.class, () -> query().categoryId("tractors"));
        assertTrue(e.getMessage().contains("unknown"), e.getMessage());
    }

    @Test
    void buildUrlTranslatesTheMeasuredFilters() {
        Query.Built b = query().buildUrl(null, "cars",
                Map.of("fuel", "electric", "price", Map.of("max", 15000),
                        "mileage", Map.of("max", 50000), "year", Map.of("min", 2020),
                        "seats", 5, "seller", "private", "brand", "RENAULT"),
                "12150", null, null);
        assertEquals(List.of(), b.ignored());
        for (String expected : List.of("category=2", "locations=d_12", "fuel=4",
                "price=min-15000", "mileage=min-50000", "regdate=2020-max", "seats=5",
                "owner_type=private", "u_car_brand=RENAULT")) {
            assertTrue(b.url().contains(expected), expected + " missing from " + b.url());
        }
    }

    @Test
    void anUntranslatedCriterionIsReturnedNeverSwallowed() {
        Query.Built b = query().buildUrl(null, "cars", Map.of("colour", "red"), null, null, null);
        assertEquals(List.of("colour"), b.ignored());
    }

    @Test
    void priceWorksOutsideVehiclesToo() {
        // Measured on category 55: price=min-1500 returned 3977 ads.
        Query.Built b = query().buildUrl(null, "bikes", Map.of("price", Map.of("max", 1500)),
                null, null, null);
        assertTrue(b.url().contains("price=min-1500"));
        assertEquals(List.of(), b.ignored());
    }

    @Test
    void sortByPricePointsAtTheOfflineSort() {
        Query.Built b = query().buildUrl(null, "cars", Map.of(), null, "price", null);
        assertEquals(1, b.ignored().size());
        assertTrue(b.ignored().get(0).contains("offline"), b.ignored().get(0));
    }

    @Test
    void reflectSeesWhatTheSiteThrewAway() {
        Map<String, Object> payload = Fixtures.object("search-payload.json");
        String url = "https://www.leboncoin.fr/recherche?category=2&locations=d_12&fuel=4&seats=5";
        Query.Reflection r = Query.reflect(url, payload);
        assertEquals(List.of("category", "fuel", "locations"), r.kept());
        assertEquals(List.of("seats"), r.dropped());
    }

    @Test
    void reflectSeesASpanThatWasKept() {
        Map<String, Object> payload = Fixtures.object("search-payload.json");
        Json.map(payload.get("filters")).put("ranges", Map.of("seats", Map.of("min", 5)));
        String url = "https://www.leboncoin.fr/recherche?category=2&seats=5&vehicle_seats=5";
        Query.Reflection r = Query.reflect(url, payload);
        assertTrue(r.kept().contains("seats"));
        // vehicle_seats is the name the site ignores in silence.
        assertEquals(List.of("vehicle_seats"), r.dropped());
    }

    @Test
    void whatTheSiteDroppedIsReappliedHere() {
        Map<String, Object> local = query().localFilter(
                Map.of("mileage", Map.of("max", 60000)), "cars", List.of("mileage"), List.of());
        assertEquals(60000.0, local.get("mileageMax"));
        Map<String, Object> fromIgnored = query().localFilter(
                Map.of("price", Map.of("max", 800)), "cars", List.of(), List.of("price"));
        assertEquals(800.0, fromIgnored.get("priceMax"));
        assertTrue(query().localFilter(Map.of("price", Map.of("max", 10)), "cars",
                List.of(), List.of()).isEmpty());
    }
}
