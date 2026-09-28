package io.github.pharaphara.lbc;

import java.util.List;

import io.github.pharaphara.lbc.browser.Browser.PageView;
import io.github.pharaphara.lbc.site.Walls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling a page from a wall, and a wall from a punishment. */
class WallsTest {

    private static PageView page(String title, String url, String text) {
        return new PageView(title, url, text, List.of());
    }

    @Test
    void anAccentedWallIsStillAWall() {
        // The markers are written without accents and the page has them. Comparing
        // raw meant half the French ones could never match, and a wall went by
        // unnoticed, which is the quiet kind of bug.
        assertNotNull(Walls.detect(
                page("Un instant", "https://www.leboncoin.fr/recherche",
                        "Veuillez vérifier que vous êtes un humain"), null));
    }

    @Test
    void aRestrictionIsNotACheckToPass() {
        String reason = Walls.detect(
                page("leboncoin", "https://www.leboncoin.fr/recherche",
                        "Accès temporairement restreint"), null);
        assertNotNull(reason);
        assertTrue(Walls.isRestriction(reason), reason);
        String advice = Walls.advice("http://localhost:7900", reason, "tried nothing");
        // Clicking will not help here, so the advice must not send anyone clicking.
        assertTrue(advice.contains("Stop"), advice);
        assertFalse(advice.contains("pass the check by hand"), advice);
    }

    @Test
    void aCheckDoesSendYouToTheBrowser() {
        String reason = Walls.detect(
                page("Just a moment", "https://geo.captcha-delivery.com/x", "short"), null);
        assertNotNull(reason);
        assertFalse(Walls.isRestriction(reason), reason);
        assertTrue(Walls.advice("http://localhost:7900", reason, "waited 20 s")
                .contains("http://localhost:7900"));
    }

    @Test
    void aPageOfResultsIsNotAWall() {
        assertNull(Walls.detect(
                page("Voitures d'occasion", "https://www.leboncoin.fr/recherche",
                        "x".repeat(9000)), null));
    }

    @Test
    void anHttpRefusalCountsOnItsOwn() {
        assertNotNull(Walls.detect(
                page("leboncoin", "https://www.leboncoin.fr/recherche", "x".repeat(9000)), 403));
    }
}
