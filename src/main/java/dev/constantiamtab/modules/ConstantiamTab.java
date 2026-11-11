package dev.constantiamtab.modules;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.constantiamtab.ConstantiamTabAddon;
import dev.constantiamtab.util.JoinDateReply;
import dev.constantiamtab.util.JoinYearCache;
import dev.constantiamtab.util.YearColors;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.render.BetterTab;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConstantiamTab extends Module {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String SERVER = "constantiam.net";
    private static final Pattern WAIT_SECONDS = Pattern.compile("(?i)(\\d+)\\s*s(ec|econds?)?\\b");
    private static final Pattern UNAVAILABLE = Pattern.compile("(?i)unknown (or incomplete )?command|no permission|don't have permission");

    private static final long REPLY_TIMEOUT_MS = 8000;
    private static final long REPLY_END_GRACE_MS = 1500;
    private static final long HEADER_TIMEOUT_MS = 1000;
    private static final long NO_REPLY_PAUSE_MS = 60000;
    private static final long MAX_DELAY_MS = 60000;
    // Longer than this can't be the cooldown from our last lookup; it's the player running the command themselves
    private static final long MAX_LEARNED_COOLDOWN_MS = 15000;
    private static final int MAX_ATTEMPTS = 2;
    private static final int NO_REPLY_LIMIT = 3;
    // A clean run this long means whatever slowed lookups down has passed
    private static final int BACKOFF_RESET_SUCCESSES = 30;

    private enum ReplyState { WAITING, HEADER, BODY }

    private static final int LEFT_NAME = 0x8A8A8A;
    private static final int LEFT_AGO = 0x666666;
    private static final int LEFT_HEAD = 0xFF8C8C8C;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> interval = sgGeneral.add(new DoubleSetting.Builder()
        .name("interval")
        .description("Seconds between /joindate lookups. Constantiam's cooldown is just under 6 seconds; this rises by itself if the server refuses a lookup.")
        .defaultValue(6.5)
        .range(1.5, 60)
        .sliderRange(1.5, 15)
        .build()
    );

    private final Setting<Boolean> hideReplies = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-replies")
        .description("Keeps the module's own /joindate replies out of chat. Ones you run yourself still show.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyConstantiam = sgGeneral.add(new BoolSetting.Builder()
        .name("only-constantiam")
        .description("Only runs on constantiam.net, since other servers may not have this command.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refreshPlayTime = sgGeneral.add(new BoolSetting.Builder()
        .name("refresh-play-time")
        .description("Looks players up again once a day so their play time stays current. New players always go first.")
        .defaultValue(true)
        .build()
    );

    public enum SortBy { JoinDate, PlayTime }

    public enum SortOrder { Ascending, Descending }

    private final SettingGroup sgTab = settings.createGroup("Tab List");

    private final Setting<Boolean> showJoinYear = sgTab.add(new BoolSetting.Builder()
        .name("show-join-year")
        .description("Shows the year each player first joined, coloured from gold (2016) to grey (2026), and sorts by the whole join date. Turns play time off: only one shows at a time.")
        .defaultValue(true)
        .onChanged(on -> {
            if (on) this.showPlayTime.set(false);
        })
        .build()
    );

    private final Setting<Boolean> showPlayTime = sgTab.add(new BoolSetting.Builder()
        .name("show-play-time")
        .description("Shows each player's play time in days, coloured from grey (under a day) to gold (two years or more), and sorts by it. Turns the join date off: only one shows at a time.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) this.showJoinYear.set(false);
        })
        .build()
    );

    private final Setting<Boolean> sort = sgTab.add(new BoolSetting.Builder()
        .name("sort")
        .description("Orders the tab list by what is shown. Players not looked up yet go last.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SortOrder> joinOrder = sgTab.add(new EnumSetting.Builder<SortOrder>()
        .name("join-date-order")
        .description("Ascending puts the earliest join date first.")
        .defaultValue(SortOrder.Ascending)
        .visible(() -> sort.get() && showJoinYear.get())
        .build()
    );

    private final Setting<SortOrder> playTimeOrder = sgTab.add(new EnumSetting.Builder<SortOrder>()
        .name("play-time-order")
        .description("Descending puts the most play time first.")
        .defaultValue(SortOrder.Descending)
        .visible(() -> sort.get() && showPlayTime.get())
        .build()
    );

    private final Setting<Boolean> yearStats = sgTab.add(new BoolSetting.Builder()
        .name("join-year-stats")
        .description("Shows how many players joined in each year, and their share, in a panel to the right of the tab list. Its size follows the list.")
        .defaultValue(true)
        .build()
    );

    public enum StatsOf { Online, AllLogged }

    private final Setting<StatsOf> statsOf = sgTab.add(new EnumSetting.Builder<StatsOf>()
        .name("stats-of")
        .description("Online counts the players online now. All Logged counts every player the module has ever looked up, for statistics over a longer time.")
        .defaultValue(StatsOf.Online)
        .visible(yearStats::get)
        .build()
    );

    private final SettingGroup sgLeft = settings.createGroup("Offline Players");

    private final Setting<Boolean> showFriends = sgLeft.add(new BoolSetting.Builder()
        .name("show-friends")
        .description("Lists every Meteor friend who is offline at the end of the tab list, dimmed, and runs /joindate once per session on each of them for when they were last on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLeft = sgLeft.add(new BoolSetting.Builder()
        .name("show-left-players")
        .description("Lists other players who have left at the end of the tab list, dimmed, with how long ago they were last seen. Only uses what this client saw; nobody is looked up for this.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxLeft = sgLeft.add(new IntSetting.Builder()
        .name("max-left-players")
        .description("How many of the most recently seen players to list.")
        .defaultValue(20)
        .range(1, 500)
        .sliderRange(1, 80)
        .visible(showLeft::get)
        .build()
    );

    // Meteor's Better Tab is kept on alongside and keeps its own settings: tab size, column height, highlights, ping, gamemode
    private final SettingGroup sgLayout = settings.createGroup("Tab Layout");

    private final Setting<Boolean> autoFit = sgLayout.add(new BoolSetting.Builder()
        .name("auto-fit")
        .description("Keeps the whole tab list on screen: first adds columns up to the screen's width, then makes columns taller than Better Tab's column height up to the screen's height, and only then shrinks the text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> bottomMargin = sgLayout.add(new IntSetting.Builder()
        .name("bottom-margin")
        .description("Space auto-fit leaves free at the bottom of the screen, so the list stays above the hotbar, hearts and armour. 52 clears one row of armour.")
        .defaultValue(52)
        .range(0, 150)
        .sliderRange(0, 100)
        .visible(autoFit::get)
        .build()
    );

    private final Setting<Double> minScale = sgLayout.add(new DoubleSetting.Builder()
        .name("min-scale")
        .description("The smallest the text may shrink to when fitting the list on screen.")
        .defaultValue(0.5)
        .range(0.25, 1)
        .sliderRange(0.25, 1)
        .visible(autoFit::get)
        .build()
    );

    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Set<UUID> failed = new HashSet<>();

    private String pendingName;
    private UUID pendingId;
    private long pendingSince;
    private ReplyState replyState;
    private String firstSeen;
    private String playTime;
    private long lastAccepted;
    private Text header;
    private long headerSince;
    private boolean reemitting;

    // Who was in the tab list last tick, to notice who leaves
    private final Map<UUID, PlayerListEntry> online = new HashMap<>();
    // Tab entries of players who left, kept so their skins stay loaded, or made for players seen in earlier sessions
    private final Map<UUID, PlayerListEntry> leftEntryCache = new HashMap<>();
    private List<PlayerListEntry> leftEntries = List.of();
    private final Set<PlayerListEntry> leftSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, Long> leftSince = new HashMap<>();
    /** One line of the statistics table. */
    private record StatRow(String label, int color, String count, String share) {
    }

    private List<StatRow> statRows = List.of();
    private String statTitle, statSubtitle;
    // Measured when the panel is built, at full size
    private int statLabelWidth, statCountWidth, statShareWidth, statWidth;
    // Offline friends already looked up this session, by lower case name
    private final Set<String> friendsChecked = new HashSet<>();
    private boolean pendingFriend;
    private Long lastSeen;
    private long nextSeenSave;
    private long nextTabRefresh;
    private PlayerListEntry drawing;

    private long nextLookup;
    private double backoff = 1;
    private int successes;
    private int noReplies;

    public ConstantiamTab() {
        super(ConstantiamTabAddon.CATEGORY, "constantiam-tab", "Tab list for Constantiam: join dates and play time from /joindate, players who left, friends and join year statistics.");
    }

    private static ConstantiamTab running() {
        ConstantiamTab module = Modules.get().get(ConstantiamTab.class);
        return module != null && module.isActive() && module.onServer() ? module : null;
    }

    /** The module while it's on, on any server: the tab layout options aren't tied to Constantiam. */
    private static ConstantiamTab enabled() {
        ConstantiamTab module = Modules.get().get(ConstantiamTab.class);
        return module != null && module.isActive() ? module : null;
    }

    /** The name to draw in the tab list, with the player's year and play time in front while the module is running. */
    public static Text decorate(PlayerListEntry entry, Text name) {
        ConstantiamTab module = running();
        if (module == null) return name;
        boolean left = module.leftSet.contains(entry);

        UUID id = entry.getProfile().id();
        Long leftAt = left ? module.leftSince.get(id) : null;
        MutableText line = Text.empty();
        if (module.showJoinYear.get()) {
            Integer year = JoinYearCache.get(id);
            line.append(Text.literal(year == null ? "----" : String.valueOf(year)).withColor(dim(year == null ? YearColors.UNKNOWN : YearColors.of(year), left)));
            line.append(" ");
        }
        if (module.showPlayTime.get()) {
            Integer minutes = JoinYearCache.playMinutes(id);
            int days = minutes == null ? 0 : minutes / (24 * 60);
            line.append(minutes == null
                ? Text.literal("--d").withColor(dim(YearColors.UNKNOWN, left))
                : Text.literal(days + "d").withColor(dim(YearColors.ofDays(days), left)));
            line.append(" ");
        }
        if (!left) return line.append(name);
        int nameColor = Friends.get().isFriend(entry) ? dim(Config.get().friendColor.get().getPacked() & 0xFFFFFF, true) : LEFT_NAME;
        return line.append(Text.literal(entry.getProfile().name()).withColor(nameColor))
            .append(Text.literal(" " + (leftAt != null ? ago(System.currentTimeMillis() - leftAt) : JoinYearCache.isNotFound(id) ? "never joined" : "not seen")).withColor(LEFT_AGO));
    }

    /** How the tab list is laid out: rows per column, columns, one column's width, and the scale everything is drawn at. */
    public record Layout(int rows, int columns, int columnWidth, float scale) {
    }

    /**
     * Picks the layout for {@code count} entries of {@code columnWidth} each. Without auto-fit it's vanilla's rule with Better
     * Tab's column height, or vanilla's 20. With it, the list first spreads into more columns up to the screen's width, then the columns
     * grow taller up to the screen's height, and only then does everything shrink, down to min-scale.
     */
    public static Layout layout(int count, int columnWidth, int screenWidth, int screenHeight, int textLines, int reserved) {
        ConstantiamTab module = enabled();
        if (module == null) return null;
        BetterTab betterTab = Modules.get().get(BetterTab.class);
        boolean betterTabOn = betterTab != null && betterTab.isActive();
        // Better Tab writes ping as a number where vanilla draws bars, which needs more room
        if (betterTabOn && betterTab.accurateLatency.get()) columnWidth += 18;
        int height = Math.max(1, betterTabOn ? betterTab.tabHeight.get() : 20);
        int columns = Math.max(1, (count + height - 1) / height);
        int rows = columns == 0 ? 0 : (count + columns - 1) / columns;
        if (!module.autoFit.get() || count == 0) return new Layout(rows, columns, columnWidth, 1);

        float min = module.minScale.get().floatValue();
        for (float scale = 1; scale >= min - 0.001f; scale -= 0.05f) {
            // Columns are 5 apart; 20 of margin at the sides, 10 at the top and some at the bottom
            int maxColumns = Math.max(1, (int) ((screenWidth - 20) / scale - reserved + 5) / (columnWidth + 5));
            int maxRows = (int) ((screenHeight - module.bottomMargin.get()) / scale - 14 - textLines * 9) / 9;
            if (maxRows < 1) continue;
            int wanted = Math.max(1, Math.min(maxColumns, (count + height - 1) / height));
            if ((count + wanted - 1) / wanted > maxRows) wanted = Math.min(maxColumns, (count + maxRows - 1) / maxRows);
            int fitRows = (count + wanted - 1) / wanted;
            if (fitRows <= maxRows) return new Layout(fitRows, wanted, columnWidth, scale);
        }
        int maxColumns = Math.max(1, (int) ((screenWidth - 20) / min - reserved + 5) / (columnWidth + 5));
        int fallbackColumns = Math.min(maxColumns, count);
        return new Layout((count + fallbackColumns - 1) / fallbackColumns, fallbackColumns, columnWidth, min);
    }

    /** The tab list's entries with the players who have left added at the end, after the sort and the length cut. */
    public static List<PlayerListEntry> withLeftPlayers(List<PlayerListEntry> entries) {
        ConstantiamTab module = running();
        if (module == null || module.leftEntries.isEmpty()) return entries;
        Set<UUID> shown = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (PlayerListEntry entry : entries) {
            shown.add(entry.getProfile().id());
            names.add(entry.getProfile().name().toLowerCase(Locale.ROOT));
        }
        List<PlayerListEntry> all = new ArrayList<>(entries);
        // Someone may have come back since the last refresh
        for (PlayerListEntry entry : module.leftEntries) {
            if (!shown.contains(entry.getProfile().id()) && !names.contains(entry.getProfile().name().toLowerCase(Locale.ROOT))) all.add(entry);
        }
        return all;
    }

    public static boolean isLeft(PlayerListEntry entry) {
        ConstantiamTab module = running();
        return module != null && module.leftSet.contains(entry);
    }

    /** Remembers which entry's head is drawn next, see headColor. */
    public static void drawingHead(PlayerListEntry entry) {
        ConstantiamTab module = running();
        if (module != null) module.drawing = entry;
    }

    /** Dims the head of a player who has left. */
    public static int headColor(int color) {
        ConstantiamTab module = running();
        if (module == null || module.drawing == null || !module.leftSet.contains(module.drawing)) return color;
        return LEFT_HEAD;
    }

    /** Gap between the player columns and the statistics panel. */
    public static final int PANEL_GAP = 6;

    /** Width the statistics panel needs at full size, or 0 when there's none to show. */
    public static int statsPanelWidth() {
        ConstantiamTab module = running();
        if (module == null || !module.yearStats.get() || module.statRows.isEmpty()) return 0;
        return module.statWidth + 6;
    }

    /**
     * Draws the statistics panel with its left edge at x, beside the player rows that span top to bottom. The years are
     * stacked one per line and the panel is scaled down to the rows' height, though never below half size.
     */
    public static void drawStatsPanel(DrawContext context, int x, int top, int bottom) {
        ConstantiamTab module = running();
        if (module == null || !module.yearStats.get() || module.statRows.isEmpty() || bottom <= top) return;
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int lines = 2 + module.statRows.size();
        int naturalHeight = lines * 10 + 4;
        float scale = Math.max(0.5f, Math.min(1, (bottom - top) / (float) naturalHeight));
        int width = Math.round((module.statWidth + 6) * scale);
        int height = Math.max(bottom - top, Math.round(naturalHeight * scale));

        context.fill(x, top, x + width, top + height, 0x80000000);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x + 3 * scale, top + 2 * scale);
        context.getMatrices().scale(scale, scale);
        int inner = module.statWidth;
        context.drawTextWithShadow(font, module.statTitle, (inner - font.getWidth(module.statTitle)) / 2, 0, 0xFFAAAAAA);
        context.drawTextWithShadow(font, module.statSubtitle, (inner - font.getWidth(module.statSubtitle)) / 2, 10, 0xFF777777);
        for (int i = 0; i < module.statRows.size(); i++) {
            StatRow row = module.statRows.get(i);
            int y = 22 + i * 10;
            context.drawTextWithShadow(font, row.label(), 0, y, 0xFF000000 | row.color());
            int countRight = module.statLabelWidth + 6 + module.statCountWidth;
            context.drawTextWithShadow(font, row.count(), countRight - font.getWidth(row.count()), y, 0xFFE0E0E0);
            context.drawTextWithShadow(font, row.share(), inner - font.getWidth(row.share()), y, 0xFF9A9A9A);
        }
        context.getMatrices().popMatrix();
    }

    private static int dim(int color, boolean left) {
        if (!left) return color;
        int r = (color >> 16 & 0xFF) * 55 / 100, g = (color >> 8 & 0xFF) * 55 / 100, b = (color & 0xFF) * 55 / 100;
        return r << 16 | g << 8 | b;
    }

    private static String ago(long millis) {
        long minutes = Math.max(0, millis / 60000);
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h ago";
        long days = hours / 24;
        if (days < 365) return days + "d ago";
        return (days / 365) + "y ago";
    }

    /** The tab list's order: the chosen sort first, then the server's own order for ties and unknown players. */
    public static Comparator<PlayerListEntry> sorting(Comparator<PlayerListEntry> serverOrder) {
        ConstantiamTab module = running();
        if (module == null || !module.sort.get()) return serverOrder;

        boolean year = module.showJoinYear.get(), time = module.showPlayTime.get();
        if (!year && !time) return serverOrder;
        SortBy by = year ? SortBy.JoinDate : SortBy.PlayTime;
        Comparator<PlayerListEntry> known = switch (by) {
            case JoinDate -> Comparator.comparing(entry -> JoinYearCache.firstSeenKey(entry.getProfile().id()),
                Comparator.nullsLast(module.joinOrder.get() == SortOrder.Ascending ? Comparator.<String>naturalOrder() : Comparator.<String>reverseOrder()));
            case PlayTime -> Comparator.comparing(entry -> JoinYearCache.playMinutes(entry.getProfile().id()),
                Comparator.nullsLast(module.playTimeOrder.get() == SortOrder.Ascending ? Comparator.<Integer>naturalOrder() : Comparator.<Integer>reverseOrder()));
        };
        return known.thenComparing(serverOrder);
    }

    @Override
    public String getInfoString() {
        if (mc.getNetworkHandler() == null || !onServer()) return null;
        long left = mc.getNetworkHandler().getPlayerList().stream().filter(this::needsLookup).count();
        return left > 0 ? String.valueOf(left) : null;
    }

    @Override
    public void onActivate() {
        keepBetterTabOn();
    }

    /** Better Tab is part of how this module lays out the tab list, so it's switched back on whenever it's off. */
    private void keepBetterTabOn() {
        BetterTab betterTab = Modules.get().get(BetterTab.class);
        if (betterTab == null || betterTab.isActive()) return;
        betterTab.toggle();
        info("Turned Better Tab on; it stays on while Constantiam Tab is on.");
    }

    @Override
    public void onDeactivate() {
        clearPending();
        markAllSeen();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        markAllSeen();
        clearPending();
        attempts.clear();
        failed.clear();
        friendsChecked.clear();
        noReplies = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();
        keepBetterTabOn();

        if (replyState == ReplyState.HEADER && now - headerSince > HEADER_TIMEOUT_MS) {
            releaseHeader();
            replyState = ReplyState.WAITING;
        }

        trackPresence(now);

        if (pendingName != null) {
            if (firstSeen != null && now - pendingSince > REPLY_END_GRACE_MS) finishLookup();
            else if (now - pendingSince > REPLY_TIMEOUT_MS) onNoReply(now);
            return;
        }

        if (now < nextLookup || mc.player == null || mc.getNetworkHandler() == null || !onServer()) return;

        PlayerListEntry next = null, refresh = null;
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            JoinYearCache.updateName(entry.getProfile().id(), entry.getProfile().name());
            if (next == null && needsLookup(entry)) next = entry;
            if (refresh == null && needsRefresh(entry)) refresh = entry;
        }
        JoinYearCache.saveIfChanged();
        Friend friend = next == null ? nextOfflineFriend() : null;
        if (next != null) lookUp(next.getProfile().id(), next.getProfile().name(), now);
        else if (friend != null) {
            lookUp(friendId(friend), friend.name, now);
            pendingFriend = true;
        } else if (refresh != null) lookUp(refresh.getProfile().id(), refresh.getProfile().name(), now);
    }

    // Before Better Chat, which rewrites and merges repeated lines
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        if (pendingName == null || reemitting) return;

        String line = JoinDateReply.clean(event.getMessage().getString());
        switch (replyState) {
            case WAITING -> {
                if (JoinDateReply.isBorder(line)) {
                    if (hideReplies.get()) {
                        header = event.getMessage();
                        event.cancel();
                    }
                    headerSince = System.currentTimeMillis();
                    replyState = ReplyState.HEADER;
                } else if (JoinDateReply.isNotFound(line)) {
                    // Tab list entries that aren't real players, so asking again won't help
                    failed.add(pendingId);
                    if (pendingFriend) {
                        // A friend who has never been on the server: remembered, so it shows and isn't asked again today
                        friendsChecked.add(pendingName.toLowerCase(Locale.ROOT));
                        JoinYearCache.markNotFound(pendingId, pendingName);
                        nextTabRefresh = 0;
                    }
                    LOG.info("[ConstantiamTab] /joindate: {} not found on the server", pendingName);
                    hide(event);
                    finishLookup();
                } else if (JoinDateReply.cooldownSeconds(line) != -1) {
                    onCooldown(JoinDateReply.cooldownSeconds(line));
                    hide(event);
                } else if (!JoinDateReply.isPlayerChat(line)) {
                    checkRefusal(line, event);
                }
            }
            case HEADER -> {
                String player = JoinDateReply.player(line);
                if (player == null) return;
                if (player.equalsIgnoreCase(pendingName)) {
                    header = null;
                    replyState = ReplyState.BODY;
                    hide(event);
                } else {
                    // The player's own /joindate for someone else. Its top border is lost: re-adding a line here would
                    // reuse the event being handled, which Meteor keeps as a single instance
                    header = null;
                    replyState = ReplyState.WAITING;
                }
            }
            case BODY -> {
                String date = JoinDateReply.firstSeen(line);
                if (date != null) {
                    firstSeen = date;
                    hide(event);
                } else if (JoinDateReply.isOtherBodyLine(line)) {
                    if (JoinDateReply.playTime(line) != null) playTime = JoinDateReply.playTime(line);
                    if (JoinDateReply.lastSeen(line) != null) lastSeen = JoinDateReply.lastSeen(line);
                    hide(event);
                } else if (JoinDateReply.isBorder(line)) {
                    hide(event);
                    finishLookup();
                }
            }
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof DisconnectS2CPacket(Text reason))) return;
        if (reason.getString().toLowerCase(Locale.ROOT).contains("spam")) {
            interval.set(Math.min(interval.get() * 2, 60));
            LOG.warn("[ConstantiamTab] Kicked for spam, interval is now {}s: {}", interval.get(), reason.getString());
        }
    }

    private boolean onServer() {
        if (!onlyConstantiam.get()) return true;
        ServerInfo server = MinecraftClient.getInstance().getCurrentServerEntry();
        if (server == null) return false;
        String address = server.address.toLowerCase(Locale.ROOT);
        return address.equals(SERVER) || address.startsWith(SERVER + ":") || address.contains("." + SERVER);
    }

    /** Notes who leaves, keeps everyone's last online time saved, and rebuilds the left players and statistics. */
    private void trackPresence(long now) {
        if (mc.getNetworkHandler() == null || !onServer()) return;

        Map<UUID, PlayerListEntry> current = new HashMap<>();
        for (PlayerListEntry entry : mc.getNetworkHandler().getListedPlayerListEntries()) {
            if (JoinDateReply.isValidName(entry.getProfile().name())) current.put(entry.getProfile().id(), entry);
        }
        for (Map.Entry<UUID, PlayerListEntry> before : online.entrySet()) {
            if (current.containsKey(before.getKey())) continue;
            JoinYearCache.seen(before.getKey(), before.getValue().getProfile().name(), now);
            leftEntryCache.put(before.getKey(), before.getValue());
            nextTabRefresh = 0;
        }
        online.clear();
        online.putAll(current);

        // Now and then rather than every tick, so a crash loses at most a minute of last online times
        if (now >= nextSeenSave) {
            nextSeenSave = now + 60000;
            for (PlayerListEntry entry : current.values()) JoinYearCache.seen(entry.getProfile().id(), entry.getProfile().name(), now);
        }
        JoinYearCache.saveIfChanged();

        if (now < nextTabRefresh) return;
        nextTabRefresh = now + 1000;
        refreshLeft();
        refreshStats();
    }

    private void refreshLeft() {
        List<PlayerListEntry> entries = new ArrayList<>();
        leftSet.clear();
        leftSince.clear();
        if (showLeft.get() || showFriends.get()) {
            Map<UUID, JoinYearCache.Left> chosen = new java.util.LinkedHashMap<>();
            if (showFriends.get()) {
                // Every offline friend, even ones never seen
                for (Friend friend : Friends.get()) {
                    if (friend.name == null || isOnline(friend)) continue;
                    UUID id = friendId(friend);
                    if (online.containsKey(id)) continue;
                    chosen.put(id, new JoinYearCache.Left(id, friend.name, JoinYearCache.lastOnline(id)));
                }
            }
            if (showLeft.get()) {
                Set<String> names = new HashSet<>();
                for (JoinYearCache.Left left : chosen.values()) names.add(left.name().toLowerCase(Locale.ROOT));
                // Max left players counts only these; friends already listed above don't use up the limit
                int others = 0;
                for (JoinYearCache.Left left : JoinYearCache.recentlyLeft(online.keySet(), maxLeft.get() + chosen.size())) {
                    if (others >= maxLeft.get()) break;
                    if (names.add(left.name().toLowerCase(Locale.ROOT)) && chosen.putIfAbsent(left.id(), left) == null) others++;
                }
            }
            List<JoinYearCache.Left> ordered = new ArrayList<>(chosen.values());
            ordered.sort(Comparator.comparing(JoinYearCache.Left::lastOnline, Comparator.nullsLast(Comparator.<Long>reverseOrder())));
            for (JoinYearCache.Left left : ordered) {
                PlayerListEntry entry = leftEntryCache.computeIfAbsent(left.id(), id -> new PlayerListEntry(new GameProfile(id, left.name()), false));
                entries.add(entry);
                leftSet.add(entry);
                if (left.lastOnline() != null) leftSince.put(left.id(), left.lastOnline());
            }
        }
        leftEntries = entries;
    }

    private void refreshStats() {
        if (!yearStats.get() || online.isEmpty()) {
            statRows = List.of();
            return;
        }
        Map<Integer, Integer> years = new TreeMap<>();
        int unknown = 0;
        int total;
        String title, subtitle;
        if (statsOf.get() == StatsOf.AllLogged) {
            // Only players with a known year: the rest are offline friends or names /joindate didn't know
            years.putAll(JoinYearCache.yearCounts());
            total = years.values().stream().mapToInt(Integer::intValue).sum();
            title = "Join years";
            subtitle = total + " logged";
        } else {
            for (UUID id : online.keySet()) {
                Integer year = JoinYearCache.get(id);
                if (year == null) unknown++;
                else years.merge(year, 1, Integer::sum);
            }
            total = online.size();
            title = "Join years";
            subtitle = total + " online";
        }
        if (total == 0) {
            statRows = List.of();
            return;
        }
        List<Map.Entry<Integer, Integer>> counts = new ArrayList<>(years.entrySet());
        if (unknown > 0) counts.add(Map.entry(-1, unknown));
        List<StatRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, Integer> count : counts) {
            boolean known = count.getKey() != -1;
            rows.add(new StatRow(known ? String.valueOf(count.getKey()) : "????", known ? YearColors.of(count.getKey()) : YearColors.UNKNOWN,
                String.valueOf(count.getValue()), String.format(Locale.ROOT, "%.1f%%", count.getValue() * 100.0 / total)));
        }

        TextRenderer font = mc.textRenderer;
        statLabelWidth = statCountWidth = statShareWidth = 0;
        for (StatRow row : rows) {
            statLabelWidth = Math.max(statLabelWidth, font.getWidth(row.label()));
            statCountWidth = Math.max(statCountWidth, font.getWidth(row.count()));
            statShareWidth = Math.max(statShareWidth, font.getWidth(row.share()));
        }
        statWidth = Math.max(Math.max(font.getWidth(title), font.getWidth(subtitle)), statLabelWidth + 6 + statCountWidth + 6 + statShareWidth);
        statTitle = title;
        statSubtitle = subtitle;
        statRows = rows;
    }

    /** Everyone still in the tab list was online until now. Used when leaving, so they don't keep an old time. */
    private void markAllSeen() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PlayerListEntry> entry : online.entrySet()) {
            JoinYearCache.seen(entry.getKey(), entry.getValue().getProfile().name(), now);
            leftEntryCache.put(entry.getKey(), entry.getValue());
        }
        online.clear();
        JoinYearCache.saveIfChanged();
        nextSeenSave = 0;
        nextTabRefresh = 0;
    }

    private boolean needsLookup(PlayerListEntry entry) {
        UUID id = entry.getProfile().id();
        return !failed.contains(id) && !JoinYearCache.isComplete(id) && JoinDateReply.isValidName(entry.getProfile().name());
    }

    private boolean needsRefresh(PlayerListEntry entry) {
        UUID id = entry.getProfile().id();
        return refreshPlayTime.get() && !failed.contains(id) && JoinYearCache.isComplete(id) && JoinYearCache.isStale(id);
    }

    private void lookUp(UUID id, String name, long now) {
        pendingName = name;
        pendingId = id;
        pendingSince = now;
        replyState = ReplyState.WAITING;
        firstSeen = null;
        playTime = null;
        nextLookup = now + delay();
        mc.getNetworkHandler().sendChatCommand("joindate " + pendingName);
    }

    private void finishLookup() {
        if (firstSeen != null) JoinYearCache.put(pendingId, pendingName, firstSeen, playTime);
        if (firstSeen != null && lastSeen != null) {
            JoinYearCache.seenAtLeast(pendingId, pendingName, lastSeen);
            nextTabRefresh = 0;
        }
        if (pendingFriend) {
            friendsChecked.add(pendingName.toLowerCase(Locale.ROOT));
            // The replies are hidden from chat, so friends' lookups are logged instead
            if (firstSeen != null) LOG.info("[ConstantiamTab] Friend {}: first seen {}, last seen {}", pendingName, firstSeen, lastSeen == null ? "now" : java.time.Instant.ofEpochMilli(lastSeen));
        }
        lastAccepted = pendingSince;
        attempts.remove(pendingId);
        noReplies = 0;
        if (++successes >= BACKOFF_RESET_SUCCESSES) {
            backoff = 1;
            successes = 0;
        }
        clearPending();
    }

    private void onNoReply(long now) {
        if (attempts.merge(pendingId, 1, Integer::sum) >= MAX_ATTEMPTS) {
            failed.add(pendingId);
            LOG.warn("[ConstantiamTab] No /joindate reply for {}, skipping them until you rejoin", pendingName);
        }
        if (++noReplies >= NO_REPLY_LIMIT) {
            noReplies = 0;
            backoff *= 2;
            nextLookup = now + NO_REPLY_PAUSE_MS;
        }
        clearPending();
    }

    /** Not a failure: the player is simply asked again once it runs out, and the interval learns from it to avoid the next one. */
    private void onCooldown(int seconds) {
        long now = System.currentTimeMillis();
        // The server rounds down, so "2s" means 2 to 3 seconds are left. Taking the middle keeps the interval close to the
        // real cooldown; if that's slightly short, the next refusal raises it again
        long left = seconds * 1000L + 500;
        long cooldown = now - lastAccepted + left;
        if (cooldown <= MAX_LEARNED_COOLDOWN_MS && cooldown > interval.get() * 1000) {
            interval.set(Math.ceil(cooldown / 500.0) / 2);
            LOG.info("[ConstantiamTab] /joindate cooldown is about {}s, interval is now {}s", cooldown / 1000.0, interval.get());
        }
        nextLookup = now + left + 250;
        clearPending();
    }

    private void checkRefusal(String line, ReceiveMessageEvent event) {
        if (UNAVAILABLE.matcher(line).find()) {
            LOG.warn("[ConstantiamTab] /joindate isn't available here: {}", line);
            error("/joindate isn't available here: %s", line);
            toggle();
        } else if (JoinDateReply.isLimitMessage(line)) {
            long now = System.currentTimeMillis();
            successes = 0;
            backoff *= 2;
            Matcher wait = WAIT_SECONDS.matcher(line);
            nextLookup = now + Math.min(wait.find() ? Long.parseLong(wait.group(1)) * 1000 + 500 : delay(), MAX_DELAY_MS);
            LOG.warn("[ConstantiamTab] Slowing down after: {}", line);
            hide(event);
            clearPending();
        }
    }

    private long delay() {
        return Math.min((long) (interval.get() * backoff * 1000), MAX_DELAY_MS);
    }

    private void hide(ReceiveMessageEvent event) {
        if (hideReplies.get()) event.cancel();
    }

    /** Puts back a held "---" line that no reply followed. Only safe outside the chat event, see onMessage. */
    private void releaseHeader() {
        if (header == null) return;
        Text line = header;
        header = null;
        reemitting = true;
        mc.inGameHud.getChatHud().addMessage(line);
        reemitting = false;
    }

    private void clearPending() {
        header = null;
        pendingName = null;
        pendingId = null;
        replyState = null;
        firstSeen = null;
        playTime = null;
        lastSeen = null;
        pendingFriend = false;
    }

    /** A Meteor friend who isn't online and hasn't been looked up this session. */
    private Friend nextOfflineFriend() {
        if (!showFriends.get()) return null;
        // Friends with nothing saved yet first, so a newly added friend isn't stuck behind everyone already known
        Friend known = null;
        for (Friend friend : Friends.get()) {
            String name = friend.name;
            if (name == null || !JoinDateReply.isValidName(name) || friendsChecked.contains(name.toLowerCase(Locale.ROOT))) continue;
            UUID id = friendId(friend);
            if (isOnline(friend) || failed.contains(id) || JoinYearCache.notFoundToday(id)) continue;
            if (!JoinYearCache.isComplete(id)) return friend;
            if (known == null) known = friend;
        }
        return known;
    }

    private boolean isOnline(Friend friend) {
        for (PlayerListEntry entry : online.values()) {
            if (entry.getProfile().name().equalsIgnoreCase(friend.name)) return true;
        }
        return false;
    }

    /**
     * The friend's UUID: Meteor's own (looked up from Mojang when they were added), else a saved player with that name,
     * else a stand-in made from the name, which is only used until they are seen online.
     */
    private static UUID friendId(Friend friend) {
        UUID meteor = null;
        try {
            if (FRIEND_ID == null) {
                FRIEND_ID = Friend.class.getDeclaredField("id");
                FRIEND_ID.setAccessible(true);
            }
            if (FRIEND_ID.get(friend) instanceof UUID id) meteor = id;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Meteor changed the field; fall back to the name
        }
        // Whichever already has saved data, so one name never splits into two players
        if (meteor != null && JoinYearCache.name(meteor) != null) return meteor;
        UUID saved = JoinYearCache.idByName(friend.name);
        if (saved != null) return saved;
        return meteor != null ? meteor : UUID.nameUUIDFromBytes(("OfflinePlayer:" + friend.name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static java.lang.reflect.Field FRIEND_ID;
}
