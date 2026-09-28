<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/brand/banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="assets/brand/banner-light.png">
  <img alt="le bon container. A browser with a head. A container with a purpose. MCP server, headed Chromium, Docker." src="assets/brand/banner-light.png" width="100%">
</picture>

[Français](README.md) · **English**

# le bon container

**A browser with a head. A container with a purpose.**

Browse [leboncoin.fr](https://www.leboncoin.fr) with an AI, through a real
browser, over [MCP](https://modelcontextprotocol.io).

![build](https://github.com/pharaphara/le-bon-container/actions/workflows/build.yml/badge.svg)

One container, nine tools, and results an assistant can actually work with: market
markers first, a compact table next, the full detail on disk, and ad numbers that
never move.

```
search "vtt electrique", place 12, price max 1500

search "vtt-electrique-8778" | 47 announced | 46 collected | 1 calls
prices: median 915 EUR, middle half 500 EUR to 1 250 EUR, range 30 EUR to 1 500 EUR
market: 45 private, 1 pro, median age 118 d, 6 bumped since first posted
---
  n      price  place              age   by   title
  1    1 050 €  Auriac-Lagast (1…  14 j  P    VTT electrique
  2      800 €  Rodez (12)         50 j  P    Vtt electrique giant
  3    1 400 €  Nauviale (12)      30 j  P    Vtt electrique
  …
!! condition the site applied: global_BDC
- more: results(offset=25) for the remaining 21
```

## Quick start

```bash
docker compose up -d
```

Then point your client at it: see [connecting a
client](#connecting-a-client) just below, for Claude Code, Claude, ChatGPT and
Codex.

Nothing to compile and no JDK to install: the image is built and published by CI.

The first time, run `docker compose up` without `-d`: the container prints a
short banner with both addresses and the state of the profile. Afterwards
`docker compose logs lbc` shows it again at any time. Docker prints nothing from
inside a detached container, which is a Docker limit rather than an oversight.

**Once, at the beginning**, open <http://localhost:7900>. You are looking at the
container's own browser, and this is where a human does the one thing no tool
should: pass the anti robot check, and sign in if you want to.

This is not optional, and it is worth knowing why. Measured on a brand new
container: the site renders its pages happily, but refuses the data calls with a
403 carrying an anti robot marker, and after a few requests it challenges the page
itself. A fresh profile simply has no clearance yet. Pass the check by hand once,
and the profile remembers it. Whether an account adds anything on top has not been
measured, so this readme does not claim it does.

## Connecting a client

The server speaks MCP over HTTP at `http://localhost:8788/mcp`. Here are the four
common cases.

**Claude Code**

```bash
claude mcp add --transport http lbc http://localhost:8788/mcp
```

**Claude desktop app**

In the MCP servers configuration file:

```json
{ "mcpServers": { "lbc": { "type": "http", "url": "http://localhost:8788/mcp" } } }
```

Recent versions also let you add a custom connector from the settings, with the
same address.

**ChatGPT**

This one needs a **public HTTPS address**: ChatGPT will never reach your
`localhost`. Put a reverse proxy with TLS in front of port 8788, say
`lbc.example.org`, then add it as a custom connector in your account settings.
Whether that feature is available depends on your plan and settings, which this
document cannot know for you. And do not expose it without authentication in
front: these tools drive a browser carrying your session.

**Codex**

In `~/.codex/config.toml`:

```toml
[mcp_servers.lbc]
command = "npx"
args = ["-y", "mcp-remote", "http://localhost:8788/mcp"]
```

That form goes through a bridge, so it works whatever the version. If yours takes
an http address directly, declaring it that way skips the bridge.

**Any other client that only speaks stdio** connects the same way, through a
bridge such as `mcp-remote` pointed at the same address.

## The tools

| Tool | What it does |
|---|---|
| `search` | Collect a search. One page render, then paging by data calls. Returns markers, then a table. |
| `results` | Re-read what was collected: filter and sort, offline, as often as you like. |
| `ad` | Everything about one ad, with no network. `refresh=true` checks the site again. |
| `stats` | The market in numbers, optionally grouped by brand, year, city or seller. |
| `filters` | What the site kept of a search url, and what it quietly threw away. |
| `categories` | List the 45 measured categories, or probe any id on the spot. |
| `searches` | What is on disk, and which search the other tools default to. |
| `forget` | Delete a search and its ads. |
| `status` | How the container is wired, and where to click if a wall appears. |

## How it works

Four things were measured rather than assumed, and they are the whole design.

**The page hands over its own search payload.** A results page embeds the exact
query the site derived from the URL. So LBC never invents a filter name: it builds
a URL out of parameters that were each measured against the total the site
announces, renders it once, then replays the site's own payload to page through the
rest. Rendering is the expensive part, and it happens once.

**Paging is on `offset` alone.** The `pivot` the site returns looks like a cursor
but is a list of ids already shown, and replaying it from offset zero hands back
the same ads. `max_pages` comes out at 2 for 122 ads, so it is reported and never
trusted.

**The list already carries everything.** Measured on one ad: 6588 characters of
description in the search results, exactly as many as on the ad's own page, plus
every attribute and every photo. So `ad` costs nothing and touches no network.
Visiting an ad only tells you one thing the list cannot: whether the price moved
or it has been sold since.

**The api repeats itself.** Three responses totalling 224 lines once carried only
120 distinct ids. Deduplication is a requirement here, not a precaution, and the
count you read is always the count of unique ads.

**And when the data calls are refused, the rendered pages still work.** Until a
profile has clearance, LBC reads what the page itself renders, about thirty five
ads at a time, and pages by url instead. It is slower, one render per page, and the
answer says so in as many words rather than pretending otherwise. Pass the check
once and the fast path opens by itself.

And one thing that is not about the site at all: **numbers never move.** An ad
keeps the number it was given the first time it was seen. `ad(number=7)` means the
same ad ten messages later, after two more pages and a sort by price. That single
property is what makes a long conversation with a marketplace bearable.

## Categories are measured, not copied

Forty five of them, and every id came from the site's own data rather than from
somebody's notes. Each ad carries both its `category_id` and its `category_name`,
so browsing the whole site sorted by date hands back canonical pairs by the dozen:
210 ads over six pages gave 44 of them in one sitting.

That method immediately corrected two ids that looked right and were not. Toys is
41, while 40 is Collection. Garden is 52, and 32 is industrial equipment. Either
one would have returned an empty market with no error at all, which is the failure
that matters here: a wrong id does not fail, it just quietly finds nothing.

So a name that was never measured is refused rather than used. Any numeric id works
directly, and `categories(verify=["70"])` probes one on the spot and tells you what
the site calls it.

## No disguise, ever

The wall on this kind of site is a JavaScript challenge, not a fingerprint. Six
sessions across four spoofed fingerprints were all refused, while one real browser
with a real session walked straight through. A better disguise is the wrong
dimension.

So LBC drives a real browser with your own session. It does not patch `navigator`,
it does not forge a user agent, it does not even pass the flag that hides
automation, and it never calls a captcha solving service. Faced with a check it
waits, because a real browser usually clears one on its own, then ticks at most one
checkbox. A puzzle is where it stops and tells you to look at
<http://localhost:7900>. A test enforces all of this: fifteen patterns are banned
from the source tree, and the scanner is itself tested against a known disguise.

## How the browser is started, and why that matters more than anything

This is the most useful thing this project learned, and it was measured by
comparing this container against a browser that has been reading this kind of site
daily for months without ever being restricted.

| Seen from inside a page | Browser started by the library | Browser started normally |
|---|---|---|
| `navigator.webdriver` | `true` | `false` |
| `navigator.plugins` | 0 | 5 |

Letting Playwright launch the browser adds the automation flag, and the page can
see it. Starting an ordinary desktop browser with a debugging port open, then
merely attaching to it, does not. That was the only structural difference between
the two setups.

So the image starts Chromium itself and attaches to it, which is what
`LBC_BROWSER: attach` does. Nothing is forged and nothing is hidden: a flag is
simply not added. No `navigator` is patched, the well known flag that hides
automation is still not passed, and the whole difference comes down to how the
program is started.

`LBC_BROWSER: launch` gives the old behaviour back, simpler and louder.

## Nothing silent

Every reduction is counted and shown, on a line starting with `!!`. A criterion the
site ignored, an ad set aside because it came from another section, a duplicate, a
collection that stopped early, a search served from disk: each one produces a
number in the output.

This is the failure the project is built against. A table that dropped a third of
its rows without saying so reads exactly like a thin market, and an assistant has
no way to tell the difference.

## Configuration

Everything has a working default. Set what you need in `compose.yaml`.

| Variable | Default | What it is |
|---|---|---|
| `LBC_DATA` | `/data` | One folder per search. Mount it to keep your searches. |
| `LBC_PROFILE` | `/profile` | The browser profile, so a sign in survives a restart. |
| `LBC_HEADLESS` | `false` | A human must be able to see the page to pass a check. |
| `LBC_CDP_URL` | unset | Attach to a browser already running instead of starting one. |
| `LBC_VNC_PASSWORD` | unset | Set this before exposing port 7900 anywhere but localhost. |
| `LBC_VIEWER` | `on` | `off` skips the display and the viewer entirely. |
| `LBC_PACE_CALL_MIN_MS` | `1500` | Shortest pause between two data calls. |
| `LBC_PACE_CALL_MAX_MS` | `3500` | Longest, picked at random in between. |
| `LBC_PACE_RENDER_MIN_MS` | `6000` | Between two page renders, which cost the site far more. |
| `LBC_PACE_RENDER_MAX_MS` | `10000` | Same, upper bound. |
| `LBC_PACE_CALLS_PER_MINUTE` | `20` | Ceiling, whatever the pauses say. |
| `LBC_PACE_PAGES_MAX` | `20` | Hard stop on pages per collection. |
| `LBC_PACE_RENDERED_PAGES_MAX` | `3` | Pages per call on the slow way in. |
| `LBC_MAX_ADS` | `2000` | Hard stop on ads per search. |
| `SERVER_PORT` | `8788` | Where MCP listens. |

**Bring your own browser.** With `LBC_CDP_URL` pointing at a Chromium that is
already running with remote debugging on, LBC attaches to it and uses its first
context, which is the real profile with the real session. Nothing is launched and
nothing is closed: the browser is yours.

**Behind a reverse proxy.** Port 7900 is a web page, so any reverse proxy can put
it behind a name of your own, for instance `lbc.example.org`. Do set
`LBC_VNC_PASSWORD` first: whoever opens that page drives a browser that carries
your session, which is exactly why the compose file binds both ports to localhost
until you decide otherwise.

**Behind a VPN.** Nothing to build: Docker already does this with
`network_mode: "service:<vpn>"`, moving the published ports onto the VPN
container. Think first though, because it usually backfires here. A commercial VPN
exit is a datacenter address, and address reputation is exactly what anti robot
checks score: the wall will show up more often, not less. The case that does make
sense is the opposite one, a VPN back to your own network, which keeps a
residential address and lets the container run elsewhere while still coming out of
your home.

**Where your data lives.** One folder per search under `/data`:

```
/data/<search>/
  search.json    the url, the payload the site derived, totals, where paging stopped
  ads.jsonl      one line per ad, the site's own object, numbered
  skipped.jsonl  ads set aside, with the reason, so you can check the filter was fair
  seen.tsv       the id to number registry, which is why numbers never move
  ads/<id>.json  what a refresh brought back
```

The raw object is what gets stored, not a compact view. The full description is in
there anyway, and recomputing the view on read avoids stale columns the day the
formula changes.

**The pace is set in the compose file.** These are the numbers that decide whether
the site treats you as a visitor or as a nuisance. The defaults come from a browser
that has been reading this kind of site daily for months: one to three seconds
between calls, six to ten between page renders. Going faster does not collect more,
it collects for less time.

## If access gets temporarily restricted

It happens, and it was measured: a profile with no clearance that keeps pushing
ends up seeing "access temporarily restricted". That is not a check to pass, it is
a pause to take. Clicking will not help, and retrying makes it worse.

The tool recognises it and words it differently from a plain wall: it asks you to
stop rather than to go and click. Two things worth keeping in mind. Your address is
probably shared with your other tools, so leaning on it from this container
degrades what works elsewhere. And the way out is not a workaround: it is a profile
with a real session and a slower pace.

## Development

```bash
mvn verify              # the domain logic: no browser, no network, frozen samples
mvn spring-boot:run     # run it on your machine
docker build -t lbc .   # the image
```

Playwright ships its own Node runtime for five platforms, which is how it
guarantees the version it needs. The image keeps the one it runs and drops the
other four, saving 155 MB.

## Roadmap

**Messaging over MCP**, reading and replying, so an assistant can carry a
negotiation on a single item by itself. The line to hold is depth against breadth:
many messages in one thread is a negotiation, many threads with strangers is spam.
So the caps will sit on threads opened per day rather than on replies, autonomy
will be an explicit level rather than a default, everything sent will be logged,
and the rule that an assistant never states a fact that is not true belongs in the
tool description where it will actually be read.

**Signing in** without touching the viewer, for people who would rather put
credentials in the compose file. It will stay an option and not the default: a
marketplace password sitting in plain text is a poor trade for a gesture you make
once.

## Responsible use

This is a personal automation tool. It drives your own browser and your own
session, at a human pace, and reads pages you could open yourself. Automated
access is against the site's terms of use, so what you do with it is on you. Keep
it to your own searches and your own conversations: there is deliberately no way
to message many people at once, and there never will be.

## Licence

MIT. See [LICENSE](LICENSE).

Independent project, not affiliated with Leboncoin.
