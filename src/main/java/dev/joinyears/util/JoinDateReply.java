package dev.joinyears.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines of a /joindate reply, as logged on constantiam.net:
 * <pre>
 * ---
 * Player: gouachi
 * First seen: 2026/09/14
 * Last seen: Online now
 * Play time: 0d 6h 14m
 * ---
 * </pre>
 */
public final class JoinDateReply {
    private static final Pattern VALID_NAME = Pattern.compile("\\w{1,16}");
    private static final Pattern NOT_FOUND = Pattern.compile("Player \\S+ not found\\.");
    private static final Pattern LIMITED = Pattern.compile("(?i)cool.?down|too (fast|many|quick)|slow down|please wait|wait \\d|try again|rate.?limit|spam");
    private static final Pattern PLAYER = Pattern.compile("Player: (\\w+)");
    private static final Pattern FIRST_SEEN = Pattern.compile("First seen: ((\\d{4})/\\d{2}/\\d{2})");
    private static final Pattern PLAY_TIME = Pattern.compile("Play time: (.+)");
    // Under a second left, the server leaves the number out: "You still have  left on that command"
    private static final Pattern COOLDOWN = Pattern.compile("You still have\\s*(?:(\\d+)s)?\\s*left on that command");
    // Meteor's Better Chat counts repeated lines with a suffix, in case it got to the line first
    private static final Pattern REPEAT_SUFFIX = Pattern.compile(" \\(\\d+\\)$");

    private JoinDateReply() {
    }

    public static String clean(String line) {
        return REPEAT_SUFFIX.matcher(line.strip()).replaceFirst("");
    }

    public static boolean isBorder(String line) {
        return line.equals("---");
    }

    /** The player's name, or null if this isn't a Player line. */
    public static String player(String line) {
        Matcher matcher = PLAYER.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The first seen date as yyyy/mm/dd, or null if this isn't a First seen line. */
    public static String firstSeen(String line) {
        Matcher matcher = FIRST_SEEN.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The play time text, or null if this isn't a Play time line. */
    public static String playTime(String line) {
        Matcher matcher = PLAY_TIME.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Whole seconds left on the command's cooldown (0 when under one), or -1 if this isn't that message. */
    public static int cooldownSeconds(String line) {
        Matcher matcher = COOLDOWN.matcher(line);
        if (!matcher.matches()) return -1;
        return matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
    }

    /** The reply to a name the server has never seen. */
    public static boolean isNotFound(String line) {
        return NOT_FOUND.matcher(line).matches();
    }

    public static boolean isValidName(String name) {
        return VALID_NAME.matcher(name).matches();
    }

    public static boolean isLimitMessage(String line) {
        return LIMITED.matcher(line).find();
    }

    public static boolean isOtherBodyLine(String line) {
        return line.startsWith("Last seen: ") || line.startsWith("Play time: ");
    }

    public static boolean isPlayerChat(String line) {
        return line.startsWith("<") || line.contains(" whispers: ") || line.startsWith("You whisper to ");
    }
}
