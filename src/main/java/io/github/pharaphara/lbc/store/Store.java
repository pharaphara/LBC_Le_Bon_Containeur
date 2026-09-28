package io.github.pharaphara.lbc.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.pharaphara.lbc.Json;
import io.github.pharaphara.lbc.LbcException;

/**
 * Searches on disk: one JSONL file per search, and numbers that never move.
 *
 * <p>This is what lets an assistant work calmly. A number is handed out the
 * first time an ad is seen and never changes again. Sorting or filtering the
 * view renumbers nothing, and collecting more pages appends new numbers without
 * touching the old ones. So "show me ad 7", ten messages later, after two more
 * pages and a sort by price, still means the same ad.
 *
 * <p>The raw object from the site is what gets stored, not a compact view: the
 * full description is in there anyway, and recomputing the view on read avoids
 * stale columns the day the formula changes.
 */
public class Store {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final int SCHEMA = 1;

    /** One stored ad. {@code kmApprox} is computed on read, never persisted. */
    public record AdRecord(int number, String id, String seen, String fresh,
                           Map<String, Object> ad, Double kmApprox) {

        public AdRecord withKm(Double km) {
            return new AdRecord(number, id, seen, fresh, ad, km);
        }

        public AdRecord refreshed(Map<String, Object> newAd, String at) {
            return new AdRecord(number, id, seen, at, newAd, kmApprox);
        }
    }

    public record Added(int fresh, int duplicates, int outOfCategory, int lastNumber) {
    }

    public record SearchInfo(String slug, String demand, int ads, double ageHours,
                             String stop, boolean current) {
    }

    public record Forgotten(boolean removed, long bytes) {
    }

    private final Path root;

    public Store(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Make sure we can write, and say so plainly otherwise.
     *
     * <p>The directory is a mounted volume. If the host forgot to mount it, that
     * shows up here as a directory we cannot write to, and the right answer is to
     * fix the mount rather than quietly write somewhere else.
     */
    public void ready() {
        try {
            Files.createDirectories(root);
            Path probe = root.resolve(".write-test");
            Files.writeString(probe, "ok");
            Files.delete(probe);
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK,
                    "cannot write in " + root + ": " + e,
                    "the data volume is probably not mounted; check the compose file");
        }
    }

    // ------------------------------------------------------------------ naming

    static String slug(String text) {
        String t = Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (t.length() > 48) {
            t = t.substring(0, 48).replaceAll("-$", "");
        }
        return t.isEmpty() ? "search" : t;
    }

    /** A readable name plus four characters of fingerprint, so two close
     * searches never end up in the same folder. */
    public static String slugify(String demand, String url) {
        String seed = url == null || url.isBlank() ? String.valueOf(demand) : url;
        return slug(demand) + "-" + sha1(seed).substring(0, 4);
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path dir(String name, boolean create) {
        Path d = root.resolve(slug(name));
        if (create) {
            ready();
            try {
                Files.createDirectories(d);
            } catch (IOException e) {
                throw new LbcException(LbcException.Kind.DISK, "cannot create " + d + ": " + e);
            }
        }
        return d;
    }

    public boolean exists(String name) {
        return Files.isRegularFile(dir(name, false).resolve("search.json"));
    }

    // ------------------------------------------------------------------ metadata

    public Map<String, Object> meta(String name) {
        Path f = dir(name, false).resolve("search.json");
        if (!Files.isRegularFile(f)) {
            throw new LbcException(LbcException.Kind.NOT_FOUND,
                    "no search called \"" + name + "\"",
                    "the searches tool lists the ones on disk");
        }
        return new LinkedHashMap<>(Json.read(f));
    }

    public void writeMeta(String name, Map<String, Object> meta) {
        Path d = dir(name, true);
        meta.put("schema", SCHEMA);
        meta.put("updated", now());
        Path tmp = d.resolve("search.json.tmp");
        try {
            Files.writeString(tmp, Json.pretty(meta));
            Files.move(tmp, d.resolve("search.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot write " + d + ": " + e);
        }
    }

    public Map<String, Object> create(String name, Map<String, Object> seed) {
        Map<String, Object> meta = exists(name) ? meta(name) : new LinkedHashMap<>();
        meta.putIfAbsent("created", now());
        seed.forEach((k, v) -> {
            if (v != null) {
                meta.put(k, v);
            }
        });
        writeMeta(name, meta);
        return meta;
    }

    public String current() {
        Path f = root.resolve(".current");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        String s = Json.readString(f).trim();
        return s.isEmpty() ? null : s;
    }

    public void setCurrent(String name) {
        ready();
        try {
            Files.writeString(root.resolve(".current"), slug(name));
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot write .current: " + e);
        }
    }

    /** The name given, else the last search. Never a guess. */
    public String resolve(String name) {
        String s = name == null || name.isBlank() ? current() : slug(name);
        if (s == null) {
            throw new LbcException(LbcException.Kind.NOT_FOUND,
                    "no search in progress",
                    "start with the search tool");
        }
        return s;
    }

    // ------------------------------------------------------------------ ads

    /**
     * Site id to stable number. Ads dropped out of category sit at 0, so they are
     * not counted as duplicates on every later page.
     */
    public Map<String, Integer> index(String name) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Path f = dir(name, false).resolve("seen.tsv");
        if (!Files.isRegularFile(f)) {
            return out;
        }
        for (String line : lines(f)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            try {
                out.put(line.substring(tab + 1), Integer.parseInt(line.substring(0, tab)));
            } catch (NumberFormatException ignored) {
                // A damaged line is not worth losing the rest of the index over.
            }
        }
        return out;
    }

    /**
     * Deduplicate, number, write. Returns the count of every case.
     *
     * <p>Deduplication is not a precaution: the site's api repeats ads, including
     * inside a single response. Measured once, three responses totalling 224
     * lines carried only 120 distinct ids.
     */
    public Added add(String name, List<Map<String, Object>> raw, String categoryId) {
        Path d = dir(name, true);
        Map<String, Integer> seen = index(name);
        int number = seen.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> fresh = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> seenRows = new ArrayList<>();
        int duplicates = 0;

        for (Map<String, Object> ad : raw) {
            String id = Ads.id(ad);
            if (id.isEmpty()) {
                continue;
            }
            if (seen.containsKey(id)) {
                duplicates++;
                continue;
            }
            String cat = Ads.categoryId(ad);
            if (categoryId != null && !categoryId.isBlank()
                    && !cat.isEmpty() && !cat.equals(categoryId)) {
                seen.put(id, 0);
                seenRows.add("0\t" + id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("reason", "out_of_category");
                row.put("category", Ads.categoryName(ad));
                row.put("seen", now());
                row.put("ad", ad);
                skipped.add(Json.write(row));
                continue;
            }
            number++;
            seen.put(id, number);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", number);
            row.put("id", id);
            row.put("seen", now());
            row.put("ad", ad);
            fresh.add(Json.write(row));
            seenRows.add(number + "\t" + id);
        }

        append(d.resolve("ads.jsonl"), fresh);
        append(d.resolve("skipped.jsonl"), skipped);
        append(d.resolve("seen.tsv"), seenRows);
        return new Added(fresh.size(), duplicates, skipped.size(), number);
    }

    public List<AdRecord> load(String name) {
        List<AdRecord> out = new ArrayList<>();
        Path f = dir(name, false).resolve("ads.jsonl");
        if (!Files.isRegularFile(f)) {
            return out;
        }
        for (String line : lines(f)) {
            AdRecord rec = parse(line);
            if (rec != null) {
                out.add(rec);
            }
        }
        return out;
    }

    private static AdRecord parse(String line) {
        try {
            Map<String, Object> row = Json.object(line);
            Double n = Ads.num(row.get("n"));
            Map<String, Object> ad = Json.map(row.get("ad"));
            if (n == null || ad.isEmpty()) {
                return null;
            }
            return new AdRecord(n.intValue(), String.valueOf(row.get("id")),
                    str(row.get("seen")), str(row.get("fresh")), ad, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Lines the reader could not parse.
     *
     * <p>Reported on its own rather than slipped into the list: a fake ad would
     * skew every count, and rewriting the file would then lose the real one.
     */
    public int unreadable(String name) {
        Path f = dir(name, false).resolve("ads.jsonl");
        if (!Files.isRegularFile(f)) {
            return 0;
        }
        int bad = 0;
        for (String line : lines(f)) {
            if (parse(line) == null) {
                bad++;
            }
        }
        return bad;
    }

    public Optional<AdRecord> byNumber(String name, String target) {
        String t = target == null ? "" : target.trim();
        for (AdRecord rec : load(name)) {
            if (String.valueOf(rec.number()).equals(t) || rec.id().equals(t)) {
                return Optional.of(rec);
            }
        }
        return Optional.empty();
    }

    /** Rewrite one ad in place. Refuses when a line is unreadable, because the
     * rewrite would lose it. */
    public void replace(String name, AdRecord rec) {
        int bad = unreadable(name);
        if (bad > 0) {
            throw new LbcException(LbcException.Kind.DISK,
                    bad + " unreadable line(s) in ads.jsonl of \"" + name + "\"",
                    "rewriting the file would lose them, so nothing was touched");
        }
        List<AdRecord> all = load(name);
        List<String> rows = new ArrayList<>();
        for (AdRecord r : all) {
            AdRecord keep = r.id().equals(rec.id()) ? rec : r;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", keep.number());
            row.put("id", keep.id());
            row.put("seen", keep.seen());
            if (keep.fresh() != null) {
                row.put("fresh", keep.fresh());
            }
            row.put("ad", keep.ad());
            rows.add(Json.write(row));
        }
        Path d = dir(name, true);
        Path tmp = d.resolve("ads.jsonl.tmp");
        try {
            Files.write(tmp, rows, StandardCharsets.UTF_8);
            Files.move(tmp, d.resolve("ads.jsonl"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot rewrite ads.jsonl: " + e);
        }
    }

    public void writeAd(String name, String id, Map<String, Object> ad) {
        Path d = dir(name, true).resolve("ads");
        try {
            Files.createDirectories(d);
            Files.writeString(d.resolve(id + ".json"), Json.write(ad));
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot write ad " + id + ": " + e);
        }
    }

    // ------------------------------------------------------------------ housekeeping

    public List<SearchInfo> searches() {
        List<SearchInfo> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        String cur = current();
        try (Stream<Path> kids = Files.list(root)) {
            for (Path d : kids.sorted().toList()) {
                Path f = d.resolve("search.json");
                if (!Files.isRegularFile(f)) {
                    continue;
                }
                Map<String, Object> meta;
                try {
                    meta = Json.read(f);
                } catch (RuntimeException e) {
                    continue;
                }
                int ads = 0;
                Path jsonl = d.resolve("ads.jsonl");
                if (Files.isRegularFile(jsonl)) {
                    ads = lines(jsonl).size();
                }
                double age = (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis())
                        / 3_600_000.0;
                out.add(new SearchInfo(d.getFileName().toString(), str(meta.get("demand")), ads,
                        Math.round(age * 10) / 10.0,
                        str(Json.map(meta.get("pagination")).get("stop")),
                        d.getFileName().toString().equals(cur)));
            }
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot list " + root + ": " + e);
        }
        out.sort(Comparator.comparingDouble(SearchInfo::ageHours));
        return out;
    }

    public Forgotten forget(String name) {
        Path d = dir(name, false);
        if (!Files.isDirectory(d)) {
            return new Forgotten(false, 0);
        }
        long bytes = size(d);
        deleteTree(d);
        return new Forgotten(true, bytes);
    }

    /** Searches older than {@code days}, then the oldest ones if the quota spills. */
    public List<String> prune(int days, long quota) {
        List<String> gone = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return gone;
        }
        String cur = current();
        record Entry(Path dir, long modified, long bytes) {
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> kids = Files.list(root)) {
            for (Path d : kids.toList()) {
                Path f = d.resolve("search.json");
                if (!Files.isDirectory(d) || !Files.isRegularFile(f)) {
                    continue;
                }
                entries.add(new Entry(d, Files.getLastModifiedTime(f).toMillis(), size(d)));
            }
        } catch (IOException e) {
            return gone;
        }
        long cut = System.currentTimeMillis() - days * 86_400_000L;
        for (Entry e : new ArrayList<>(entries)) {
            if (e.modified() < cut && !e.dir().getFileName().toString().equals(cur)) {
                deleteTree(e.dir());
                gone.add(e.dir().getFileName().toString());
                entries.remove(e);
            }
        }
        long total = entries.stream().mapToLong(Entry::bytes).sum();
        entries.sort(Comparator.comparingLong(Entry::modified));
        for (Entry e : entries) {
            if (total <= quota) {
                break;
            }
            if (e.dir().getFileName().toString().equals(cur)) {
                continue;
            }
            deleteTree(e.dir());
            gone.add(e.dir().getFileName().toString());
            total -= e.bytes();
        }
        return gone;
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Read a file line by line.
     *
     * <p>Worth a note: a Java reader splits on newlines only, where some
     * languages also split on U+2028 and friends. Ad descriptions do contain
     * those, and a splitter that honours them cuts a json line in half and loses
     * the ad in silence. Jackson escapes carriage returns inside strings, so this
     * stays safe both ways.
     */
    private static List<String> lines(Path f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot read " + f + ": " + e);
        }
        return out;
    }

    private static void append(Path f, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (String row : rows) {
                w.write(row);
                w.newLine();
            }
        } catch (IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot append to " + f + ": " + e);
        }
    }

    private static long size(Path d) {
        try (Stream<Path> all = Files.walk(d)) {
            return all.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteTree(Path d) {
        try (Stream<Path> all = Files.walk(d)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best effort: a leftover file is better than a half deletion loop.
                }
            });
        } catch (IOException ignored) {
            // Nothing to do: the caller only reports what it could remove.
        }
    }

    public static String now() {
        return LocalDateTime.now().format(STAMP);
    }

    static String stamp(Instant i) {
        return LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault()).format(STAMP);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
