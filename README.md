# LBC, le bon container

Browse [leboncoin.fr](https://www.leboncoin.fr) with an AI, through a real
browser, over [MCP](https://modelcontextprotocol.io).

One container, one search tool, and results an assistant can actually work with:
a compact table in the conversation, the full detail on disk, and ad numbers that
never move.

> **Status: in progress.** The domain logic and its tests are in. The browser
> layer, the MCP tools and the image are landing next, brick by brick.

## Why this exists

Scraping a classifieds site with an assistant usually goes one of two ways. Either
a headless robot gets refused at the door, or it works and buries the
conversation under thousands of tokens of markup. This project takes a third way,
and it is built on things that were measured rather than assumed.

* **The page hands over its own search payload.** A results page embeds the exact
  query the site derived from the URL. So LBC never invents a filter name: it
  builds a URL out of parameters that were each measured against the total the
  site announces, loads it once, then replays the site's own payload to page
  through the rest. One page render, and the whole result set.
* **The list already carries everything.** Measured on one ad: 6588 characters of
  description in the search results, exactly as many as on the ad's own page, plus
  every attribute and every photo. So opening an ad costs nothing and touches no
  network.
* **The api repeats itself.** Three responses totalling 224 lines once carried
  only 120 distinct ids. Deduplication is not a precaution here, it is a
  requirement, and the count an assistant reads is always the count of unique ads.
* **Numbers never move.** An ad keeps the number it was given the first time it
  was seen, so "show me ad 7" means the same ad ten messages later, after two more
  pages and a sort by price.

## No disguise, ever

The wall on this kind of site is a JavaScript challenge, not a fingerprint. Six
sessions across four spoofed fingerprints were all refused, while one real
browser with a real session walked straight through. A better disguise is
therefore the wrong dimension.

So LBC uses a real browser with your own session, it does not patch
`navigator`, it does not forge a user agent, and it never calls a captcha
solving service. When a wall does appear, the tool says so and asks you to click
once in the browser, which you can see over VNC. A test enforces this: fifteen
patterns are banned from the source tree.

## Nothing silent

Every reduction is counted and shown. A criterion the site quietly ignored, an ad
set aside because it came from another section, a duplicate, a collection that
stopped early: each one produces a number in the output. A table that dropped a
third of its rows without saying so reads exactly like a thin market, and that is
the failure this project refuses.

## Roadmap

* **Messaging over MCP**, both reading and replying, so an assistant can carry a
  negotiation on a single item by itself. The line to hold is depth against
  breadth: many messages in one thread is a negotiation, many threads with
  strangers is spam. Expect an explicit autonomy level, a cap on threads opened
  per day rather than on replies, a log of everything sent, and the rule that the
  assistant never states a fact that is not true.
* **Signing in.** Either credentials supplied through the compose file, or a
  remote view of the browser where you log in by hand once, which the profile
  volume then remembers. The second is the intended default: a marketplace
  password sitting in plain text is a poor trade.

## Development

```bash
mvn test          # the domain logic, no browser and no network needed
```

## Licence

MIT. See [LICENSE](LICENSE).

This is a personal automation tool. It drives your own browser and your own
session, at a human pace. Automated access is against the site's terms of use, so
what you do with it is on you: keep it to your own searches and your own
conversations.
