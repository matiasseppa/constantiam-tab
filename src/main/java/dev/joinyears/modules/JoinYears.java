package dev.joinyears.modules;

import com.mojang.logging.LogUtils;
import dev.joinyears.JoinYearsAddon;
import dev.joinyears.util.JoinDateReply;
import dev.joinyears.util.JoinYearCache;
import dev.joinyears.util.YearColors;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JoinYears extends Module {
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

    private long nextLookup;
    private double backoff = 1;
    private int successes;
    private int noReplies;

    public JoinYears() {
        super(JoinYearsAddon.CATEGORY, "join-years", "Shows the year each player first joined in the tab list, using /joindate.");
    }

    /** The name to draw in the tab list, with the player's year in front while the module is running. */
    public static Text decorate(PlayerListEntry entry, Text name) {
        JoinYears module = Modules.get().get(JoinYears.class);
        if (module == null || !module.isActive() || !module.onServer()) return name;

        Integer year = JoinYearCache.get(entry.getProfile().id());
        Text label = year == null
            ? Text.literal("----").withColor(YearColors.UNKNOWN)
            : Text.literal(String.valueOf(year)).withColor(YearColors.of(year));
        return Text.empty().append(label).append(" ").append(name);
    }

    @Override
    public String getInfoString() {
        if (mc.getNetworkHandler() == null || !onServer()) return null;
        long left = mc.getNetworkHandler().getPlayerList().stream().filter(this::needsLookup).count();
        return left > 0 ? String.valueOf(left) : null;
    }

    @Override
    public void onDeactivate() {
        clearPending();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clearPending();
        attempts.clear();
        failed.clear();
        noReplies = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();

        if (replyState == ReplyState.HEADER && now - headerSince > HEADER_TIMEOUT_MS) {
            releaseHeader();
            replyState = ReplyState.WAITING;
        }

        if (pendingName != null) {
            if (firstSeen != null && now - pendingSince > REPLY_END_GRACE_MS) finishLookup();
            else if (now - pendingSince > REPLY_TIMEOUT_MS) onNoReply(now);
            return;
        }

        if (now < nextLookup || mc.player == null || mc.getNetworkHandler() == null || !onServer()) return;

        PlayerListEntry next = null;
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            JoinYearCache.updateName(entry.getProfile().id(), entry.getProfile().name());
            if (next == null && needsLookup(entry)) next = entry;
        }
        JoinYearCache.saveIfChanged();
        if (next != null) lookUp(next, now);
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
            LOG.warn("[JoinYears] Kicked for spam, interval is now {}s: {}", interval.get(), reason.getString());
        }
    }

    private boolean onServer() {
        if (!onlyConstantiam.get()) return true;
        ServerInfo server = MinecraftClient.getInstance().getCurrentServerEntry();
        if (server == null) return false;
        String address = server.address.toLowerCase(Locale.ROOT);
        return address.equals(SERVER) || address.startsWith(SERVER + ":") || address.contains("." + SERVER);
    }

    private boolean needsLookup(PlayerListEntry entry) {
        UUID id = entry.getProfile().id();
        return !failed.contains(id) && !JoinYearCache.isComplete(id) && JoinDateReply.isValidName(entry.getProfile().name());
    }

    private void lookUp(PlayerListEntry entry, long now) {
        pendingName = entry.getProfile().name();
        pendingId = entry.getProfile().id();
        pendingSince = now;
        replyState = ReplyState.WAITING;
        firstSeen = null;
        playTime = null;
        nextLookup = now + delay();
        mc.getNetworkHandler().sendChatCommand("joindate " + pendingName);
    }

    private void finishLookup() {
        if (firstSeen != null) JoinYearCache.put(pendingId, pendingName, firstSeen, playTime);
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
            LOG.warn("[JoinYears] No /joindate reply for {}, skipping them until you rejoin", pendingName);
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
            LOG.info("[JoinYears] /joindate cooldown is about {}s, interval is now {}s", cooldown / 1000.0, interval.get());
        }
        nextLookup = now + left + 250;
        clearPending();
    }

    private void checkRefusal(String line, ReceiveMessageEvent event) {
        if (UNAVAILABLE.matcher(line).find()) {
            LOG.warn("[JoinYears] /joindate isn't available here: {}", line);
            error("/joindate isn't available here: %s", line);
            toggle();
        } else if (JoinDateReply.isLimitMessage(line)) {
            long now = System.currentTimeMillis();
            successes = 0;
            backoff *= 2;
            Matcher wait = WAIT_SECONDS.matcher(line);
            nextLookup = now + Math.min(wait.find() ? Long.parseLong(wait.group(1)) * 1000 + 500 : delay(), MAX_DELAY_MS);
            LOG.warn("[JoinYears] Slowing down after: {}", line);
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
    }
}
