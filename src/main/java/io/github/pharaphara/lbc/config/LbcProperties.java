package io.github.pharaphara.lbc.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything an operator can change, and nothing else.
 *
 * @param profile        browser profile directory. The session lives here, so a
 *                       manual sign in only ever happens once.
 * @param data           where searches are stored, one folder per search.
 * @param headless       false by default: a human must be able to see the page to
 *                       pass a check, and that needs a display.
 * @param cdpUrl         attach to a browser that is already running instead of
 *                       starting one. Bring your own browser, and its session.
 * @param vncUrl         what to tell a human when a wall needs a click.
 * @param chromeArgs     extra browser arguments, for the rare host that needs one.
 * @param maxPages       hard stop on pages per collection.
 * @param maxAds         hard stop on ads per search.
 * @param callsPerMinute our own pace limit. Being fast is not the point.
 * @param autonomy       read, reply or full. Only read does anything today, and
 *                       the rest is the messaging work still to come.
 */
@ConfigurationProperties("lbc")
public record LbcProperties(
        Path profile,
        Path data,
        boolean headless,
        String cdpUrl,
        String vncUrl,
        List<String> chromeArgs,
        int maxPages,
        int maxAds,
        int callsPerMinute,
        String autonomy) {

    public LbcProperties {
        profile = profile != null ? profile : Path.of("/profile");
        data = data != null ? data : Path.of("/data");
        vncUrl = vncUrl != null ? vncUrl : "http://localhost:7900";
        chromeArgs = chromeArgs != null ? chromeArgs : List.of();
        maxPages = maxPages > 0 ? maxPages : 20;
        maxAds = maxAds > 0 ? maxAds : 2000;
        callsPerMinute = callsPerMinute > 0 ? callsPerMinute : 20;
        autonomy = autonomy != null ? autonomy : "read";
    }

    public static LbcProperties defaults() {
        return new LbcProperties(null, null, false, null, null, null, 0, 0, 0, null);
    }
}
