package io.github.pharaphara.lbc.site;

/**
 * The few pieces of JavaScript this tool runs inside the page.
 *
 * <p>All of them read. None of them pretends to be anything: no property is
 * patched, no fingerprint is forged. The api call below is the very same call the
 * page makes for itself, sent from the page, so it carries the real cookies and
 * the real origin because they are real.
 */
final class Js {

    private Js() {
    }

    /**
     * The payload the site derived from our URL, plus its totals.
     *
     * <p>Ads are stripped out on purpose: the embedded blob runs close to a
     * megabyte, and none of it should cross a process boundary just to read a
     * handful of counters.
     */
    static final String PAYLOAD = """
            () => {
              const n = document.getElementById('__NEXT_DATA__');
              if (!n) return {missing: true, keys: []};
              let p;
              try { p = (JSON.parse(n.textContent).props || {}).pageProps || {}; }
              catch (e) { return {missing: true, keys: []}; }
              const d = p.searchData || {};
              const totals = {};
              for (const k of Object.keys(d)) {
                if (k !== 'ads' && k !== 'ads_widget') totals[k] = d[k];
              }
              return {keys: Object.keys(p), search: p.search || null,
                      totals: totals, rendered: (d.ads || []).length};
            }""";

    /** One page of results, asked for the way the page asks for it. */
    static final String CALL = """
            async ([url, key, payload]) => {
              let r;
              try {
                r = await fetch(url, {method: 'POST',
                  headers: {'Content-Type': 'application/json', 'api_key': key},
                  body: JSON.stringify(payload), credentials: 'include'});
              } catch (e) { return {status: 0, reason: String(e).slice(0, 200)}; }
              if (!r.ok) {
                let body = '';
                try { body = (await r.text()).slice(0, 400); } catch (e) {}
                return {status: r.status, body: body};
              }
              try { return {status: 200, data: await r.json()}; }
              catch (e) { return {status: 200, reason: 'unreadable response'}; }
            }""";

    /**
     * Ads found by their shape rather than by a fixed path of keys.
     *
     * <p>The site reorganises its structure regularly. A walk that looks for any
     * object carrying a list_id and a subject keeps working when a path stops
     * existing, which is what turns a redesign into a warning instead of an
     * empty result.
     */
    static final String ADS_BY_SHAPE = """
            () => {
              const found = [];
              const walk = (x) => {
                if (!x || typeof x !== 'object' || found.length > 500) return;
                if (!Array.isArray(x) && 'list_id' in x && ('subject' in x || 'title' in x)) {
                  found.push(x); return;
                }
                for (const v of (Array.isArray(x) ? x : Object.values(x))) walk(v);
              };
              for (const s of document.querySelectorAll('script')) {
                const t = s.textContent || '';
                if (!t.includes('list_id')) continue;
                const a = t.indexOf('{'), b = t.lastIndexOf('}');
                if (a < 0 || b <= a) continue;
                try { walk(JSON.parse(t.slice(a, b + 1))); } catch (e) {}
              }
              return found;
            }""";

    /**
     * The ads the page has already rendered, all of them, as full objects.
     *
     * <p>This is the way in when the data calls are refused, which is what happens
     * without a session. A rendered page carries about thirty five complete ads,
     * so paging by url still works: slower, one render per page, but it works for
     * anyone who just started the container.
     */
    static final String RENDERED_ADS = """
            () => {
              const n = document.getElementById('__NEXT_DATA__');
              if (!n) return [];
              try {
                const p = (JSON.parse(n.textContent).props || {}).pageProps || {};
                return (p.searchData || {}).ads || [];
              } catch (e) { return []; }
            }""";

    /** One ad, from its own page. */
    static final String ONE_AD = """
            () => {
              const n = document.getElementById('__NEXT_DATA__');
              if (!n) return null;
              let pp;
              try { pp = (JSON.parse(n.textContent).props || {}).pageProps; }
              catch (e) { return null; }
              const found = [];
              (function walk(x) {
                if (!x || typeof x !== 'object' || found.length) return;
                if (!Array.isArray(x) && 'list_id' in x && ('subject' in x || 'title' in x)) {
                  found.push(x); return;
                }
                for (const v of (Array.isArray(x) ? x : Object.values(x))) walk(v);
              })(pp);
              return found[0] || null;
            }""";

    /**
     * What the site answers for one category id.
     *
     * <p>Every label on the page is read, not just the first one: some ids are
     * GROUPS of sections. Measured on one that the site happily confirms
     * understanding, which then returns tens of millions of ads with a different
     * label on every probe.
     */
    static final String PROBE = """
            () => {
              const n = document.getElementById('__NEXT_DATA__');
              if (!n) return {missing: true};
              let p;
              try { p = (JSON.parse(n.textContent).props || {}).pageProps || {}; }
              catch (e) { return {missing: true}; }
              const d = p.searchData || {};
              const labels = {};
              for (const a of (d.ads || [])) {
                if (a.category_name) labels[a.category_name] = 1;
              }
              return {confirmed: (((p.search || {}).filters || {}).category || {}).id || null,
                      total: d.total_all, labels: Object.keys(labels),
                      ads: (d.ads || []).length, title: document.title};
            }""";
}
