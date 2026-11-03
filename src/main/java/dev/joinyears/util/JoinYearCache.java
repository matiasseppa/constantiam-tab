package dev.joinyears.util;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What /joindate said about each player, saved to meteor-client/join-years.json. First join dates never change, so each
 * player is only ever looked up once. Keyed by UUID since names can change.
 */
public final class JoinYearCache {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = MeteorClient.FOLDER.toPath().resolve("join-years.json");

    private static class Entry {
        String name;
        Integer year;
        String firstSeen;
        String playTime;
        String lookedUp;
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

    public static int size() {
        return load().size();
    }

    public static void put(UUID player, String name, String firstSeen, String playTime) {
        Entry entry = new Entry();
        entry.name = name;
        entry.year = Integer.parseInt(firstSeen.substring(0, 4));
        entry.firstSeen = firstSeen;
        entry.playTime = playTime;
        entry.lookedUp = LocalDate.now().toString();
        load().put(player, entry);
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
        if (Files.exists(FILE)) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> player : saved.entrySet()) {
                    entries.put(UUID.fromString(player.getKey()), parse(player.getValue()));
                }
            } catch (Exception e) {
                LOG.error("Could not read join years", e);
            }
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
