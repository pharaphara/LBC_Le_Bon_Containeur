package io.github.pharaphara.lbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This project never disguises itself, and this test is how that stays true.
 *
 * <p>The wall on a site like this one is a JavaScript challenge, not a
 * fingerprint. Six sessions across four spoofed fingerprints were all refused,
 * while one real browser with a real session walked through. So a better
 * disguise is the wrong dimension entirely: the answer is a real browser, a real
 * session, and a human who clicks once when asked.
 *
 * <p>Comments are allowed to explain why all this is absent. Code is not.
 */
class NoStealthTest {

    /** What must not appear anywhere in this project's code. */
    private static final List<String> FORBIDDEN = List.of(
            "navigator\\s*,\\s*['\"]webdriver",
            "defineProperty\\s*\\(\\s*navigator\\s*,\\s*['\"]plugins",
            "defineProperty\\s*\\(\\s*navigator\\s*,\\s*['\"]languages",
            "window\\.chrome\\s*=\\s*window\\.chrome",
            "AutomationControlled",
            "puppeteer[-_]extra",
            "playwright[-_]stealth",
            "(?i)\\bstealth\\b",
            "(?i)\\bundetected\\b",
            // Paid solving services. We ask a human instead.
            "(?i)2captcha", "(?i)anti-?captcha", "(?i)capsolver",
            "(?i)capmonster", "(?i)deathbycaptcha", "(?i)nopecha");

    private static final Pattern BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile("(?m)//.*$");

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> all = Files.walk(root)) {
                out.addAll(all.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".java") || n.endsWith(".js") || n.endsWith(".json");
                        })
                        .toList());
            }
        }
        return out;
    }

    /** Comments have the right to explain why something is gone. */
    private static String code(Path p) throws IOException {
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (p.getFileName().toString().endsWith(".json")) {
            return text;
        }
        return LINE.matcher(BLOCK.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    @Test
    void noDisguiseAnywhereInTheCode() throws IOException {
        List<Path> files = sources();
        assertTrue(files.size() > 5, "the scan found almost nothing, which means it is broken");
        for (Path p : files) {
            String code = code(p);
            for (String pattern : FORBIDDEN) {
                assertFalse(Pattern.compile(pattern).matcher(code).find(),
                        p + " contains the forbidden pattern " + pattern);
            }
        }
    }

    @Test
    void theScannerActuallyCatchesSomething() throws IOException {
        // A test that can never fail protects nothing.
        String sample = "var x = 1; Object.defineProperty(navigator, 'plugins', {});";
        boolean caught = FORBIDDEN.stream().anyMatch(p -> Pattern.compile(p).matcher(sample).find());
        assertTrue(caught, "the pattern list no longer catches a known disguise");
    }
}
