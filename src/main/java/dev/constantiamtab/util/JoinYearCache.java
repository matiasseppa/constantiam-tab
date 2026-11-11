package dev.constantiamtab.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * What /joindate said about each player, saved to meteor-client/constantiam-tab.json. First join dates never change, so each
 * player is only ever looked up once. Keyed by UUID since names can change.
 */
public final class JoinYearCache {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = MeteorClient.FOLDER.toPath().resolve("constantiam-tab.json");
    // Where the addon kept it while it was called Join Years; read once if the new file isn't there yet, and left as it is
    private static final Path OLD_FILE = MeteorClient.FOLDER.toPath().resolve("join-years.json");

    private static class Entry {
        String name;
        Integer year;
        String firstSeen;
        String playTime;
        String lookedUp;
        /** When this client last saw the player in the tab list, in epoch milliseconds. */
        Long lastOnline;
        /** The day /joindate last said the player was not found, for friends who have never been on the server. */
        String notFound;
    }

    /** A saved player who isn't online now, for the tab list's left players section. */
    public record Left(UUID id, String name, Long lastOnline) {
    }

    private static Map<UUID, Entry> entries;
    private static boolean dirty;

    private JoinYearCache() {
    }

    public static Path file() {
        return FILE;
    }

    public static Integer get(UUID player) {
        Entry entry = load().get(player);
        return entry == null ? null : entry.year;
    }

    /** False for players not looked up yet, and for those saved before dates were stored. */
    public static boolean isComplete(UUID player) {
        Entry entry = load().get(player);
        return entry != null && entry.firstSeen != null;
    }

    /** Whether the saved play time is from an earlier day, so it has fallen behind for anyone still playing. */
    public static boolean isStale(UUID player) {
        Entry entry = load().get(player);
        return entry != null && !LocalDate.now().toString().equals(entry.lookedUp);
    }

    /** The first seen date as yyyy/mm/dd, for sorting. Players saved before dates were stored sort by year alone. */
    public static String firstSeenKey(UUID player) {
        Entry entry = load().get(player);
        if (entry == null || entry.year == null) return null;
        return entry.firstSeen != null ? entry.firstSeen : entry.year + "/00/00";
    }

    /** Total play time in minutes, or null if it isn't known. */
    public static Integer playMinutes(UUID player) {
        Entry entry = load().get(player);
        return entry == null || entry.playTime == null ? null : JoinDateReply.playTimeMinutes(entry.playTime);
    }

    public static int size() {
        return load().size();
    }

    public static void put(UUID player, String name, String firstSeen, String playTime) {
        // Keeps what else is saved about the player, such as when they were last online
        Entry entry = load().computeIfAbsent(player, id -> new Entry());
        entry.name = name;
        entry.year = Integer.parseInt(firstSeen.substring(0, 4));
        entry.firstSeen = firstSeen;
        entry.playTime = playTime;
        entry.lookedUp = LocalDate.now().toString();
        dirty = true;
        saveIfChanged();
    }

    /** Keeps the saved name current, for renamed players and entries saved before names were stored. */
    public static void updateName(UUID player, String name) {
        Entry entry = load().get(player);
        if (entry != null && !name.equals(entry.name)) {
            entry.name = name;
            dirty = true;
        }
    }

    /** Records that /joindate didn't know the player, keeping anything else saved about them. */
    public static void markNotFound(UUID player, String name) {
        Entry entry = load().computeIfAbsent(player, id -> new Entry());
        if (name != null) entry.name = name;
        entry.notFound = LocalDate.now().toString();
        dirty = true;
        saveIfChanged();
    }

    /** Whether /joindate didn't know the player, and they haven't been found since. */
    public static boolean isNotFound(UUID player) {
        Entry entry = load().get(player);
        return entry != null && entry.notFound != null && entry.firstSeen == null;
    }

    /** Whether /joindate already said today that it doesn't know the player, so asking again would only waste a lookup. */
    public static boolean notFoundToday(UUID player) {
        Entry entry = load().get(player);
        return entry != null && entry.firstSeen == null && LocalDate.now().toString().equals(entry.notFound);
    }

    /** The first seen date as yyyy/mm/dd, or null if it isn't known. */
    public static String firstSeen(UUID player) {
        Entry entry = load().get(player);
        return entry == null ? null : entry.firstSeen;
    }

    /** How many saved players joined in each year; players without a known year are left out. */
    public static Map<Integer, Integer> yearCounts() {
        Map<Integer, Integer> counts = new java.util.TreeMap<>();
        for (Entry entry : load().values()) {
            if (entry.year != null) counts.merge(entry.year, 1, Integer::sum);
        }
        return counts;
    }

    /** Like seen, but never moves the time back, for last seen dates from /joindate that may be older than our own. */
    public static void seenAtLeast(UUID player, String name, long time) {
        Entry entry = load().get(player);
        if (entry == null || entry.lastOnline == null || entry.lastOnline < time) seen(player, name, time);
    }

    public static Long lastOnline(UUID player) {
        Entry entry = load().get(player);
        return entry == null ? null : entry.lastOnline;
    }

    public static String name(UUID player) {
        Entry entry = load().get(player);
        return entry == null ? null : entry.name;
    }

    /** The saved player with this name, ignoring case, or null. */
    public static UUID idByName(String name) {
        for (Map.Entry<UUID, Entry> saved : load().entrySet()) {
            if (name.equalsIgnoreCase(saved.getValue().name)) return saved.getKey();
        }
        return null;
    }

    /** Records that the player was in the tab list at this time, saving them even if they were never looked up. */
    public static void seen(UUID player, String name, long time) {
        Entry entry = load().computeIfAbsent(player, id -> new Entry());
        if (name != null) entry.name = name;
        entry.lastOnline = time;
        dirty = true;
    }

    /** The saved players not in {@code online}, last online most recently first, at most {@code max} of them. */
    public static List<Left> recentlyLeft(Set<UUID> online, int max) {
        PriorityQueue<Left> newest = new PriorityQueue<>(Comparator.comparingLong(Left::lastOnline));
        for (Map.Entry<UUID, Entry> saved : load().entrySet()) {
            Entry entry = saved.getValue();
            if (entry.lastOnline == null || entry.name == null || online.contains(saved.getKey())) continue;
            newest.add(new Left(saved.getKey(), entry.name, entry.lastOnline));
            if (newest.size() > max) newest.poll();
        }
        List<Left> left = new ArrayList<>(newest);
        left.sort(Comparator.comparingLong(Left::lastOnline).reversed());
        return left;
    }

    public static void saveIfChanged() {
        if (!dirty) return;
        dirty = false;
        try {
            // Through a temporary file, so a crash mid-write can't lose everything saved so far
            Files.createDirectories(FILE.getParent());
            Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(entries));
            Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.error("Could not save join years", e);
        }
    }

    private static Map<UUID, Entry> load() {
        if (entries != null) return entries;
        entries = new LinkedHashMap<>();
        Path source = Files.exists(FILE) ? FILE : OLD_FILE;
        if (Files.exists(source)) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> player : saved.entrySet()) {
                    entries.put(UUID.fromString(player.getKey()), parse(player.getValue()));
                }
            } catch (Exception e) {
                LOG.error("Could not read join years", e);
            }
            if (source == OLD_FILE) dirty = true;
        }
        return entries;
    }

    private static Entry parse(JsonElement saved) {
        if (saved.isJsonObject()) return GSON.fromJson(saved, Entry.class);
        // The first version saved only the year
        Entry entry = new Entry();
        entry.year = saved.getAsInt();
        return entry;
    }
}
