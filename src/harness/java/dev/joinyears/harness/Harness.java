package dev.joinyears.harness;

import com.mojang.authlib.GameProfile;
import dev.joinyears.modules.JoinYears;
import dev.joinyears.util.JoinYearCache;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only: runs commands written to run/harness/cmd.txt and plays the part of constantiam.net's /joindate, answering the
 * module's command packets with chat lines in the format seen in the real logs. Fake players are named like P2019.
 */
public class Harness implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("HARNESS");
    private final Path commands = Path.of("harness", "cmd.txt");
    private final Deque<String> replies = new ArrayDeque<>();
    private final List<String> seen = new ArrayList<>();
    private boolean subscribed;
    private String mode = "normal";
    private int count;
    private long lastCommand;
    private long lastAccepted;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        if (!subscribed) {
            MeteorClient.EVENT_BUS.subscribe(this);
            subscribed = true;
        }
        // One tick's worth, like the server sending the whole reply at once
        while (!replies.isEmpty()) {
            String line = replies.poll();
            if (line.equals("<tick>")) break;
            mc.inGameHud.getChatHud().addMessage(Text.literal(line));
        }
        if (!Files.exists(commands)) return;
        try {
            List<String> lines = Files.readAllLines(commands);
            Files.delete(commands);
            for (String line : lines) if (!line.isBlank()) run(mc, line.trim());
        } catch (Exception e) {
            LOG.error("[HARNESS] command failed", e);
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof CommandExecutionC2SPacket(String command)) || !command.startsWith("joindate")) return;
        event.cancel();
        long now = System.currentTimeMillis();
        long gap = now - lastCommand;
        lastCommand = now;
        count++;
        seen.add(command);
        LOG.info("[HARNESS] server got /{} gap={}ms", command, gap);

        String[] parts = command.split(" ");
        if (parts.length != 2) {
            replies.add("Usage: /joindate <player>");
            return;
        }
        String name = parts[1];
        if (name.equals("Ghost")) return;
        if (name.equals("Nobody")) {
            replies.add("Player Nobody not found.");
            return;
        }
        switch (mode) {
            case "cooldown" -> {
                if (count % 3 == 0) {
                    replies.add("Please wait 2 seconds before using this command again.");
                    return;
                }
            }
            case "real" -> {
                // Constantiam's cooldown, as seen in the live log: whole seconds rounded down, no number under one
                long left = 5800 - (now - lastAccepted);
                if (left > 0) {
                    long seconds = left / 1000;
                    replies.add("You still have " + (seconds == 0 ? "" : seconds + "s") + " left on that command");
                    return;
                }
                lastAccepted = now;
            }
            default -> {
            }
        }
        String year = name.matches("P\\d{4}") ? name.substring(1) : "2026";
        replies.add("---");
        if (mode.equals("interleave")) {
            replies.add("<someone> talking over the reply");
            replies.add("<tick>");
        }
        replies.add("Player: " + name);
        replies.add("First seen: " + year + "/06/13");
        if (mode.equals("interleave")) replies.add("<someone> and again");
        replies.add("Last seen: Online now");
        replies.add("Play time: 0d 6h 14m");
        replies.add("---");
    }

    private void addFakePlayers(MinecraftClient mc, String[] names) throws Exception {
        PlayerListS2CPacket packet = new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER, PlayerListS2CPacket.Action.UPDATE_LISTED), List.of());
        List<PlayerListS2CPacket.Entry> entries = new ArrayList<>();
        for (String name : names) {
            UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            entries.add(new PlayerListS2CPacket.Entry(id, new GameProfile(id, name), true, 50, GameMode.SURVIVAL, null, true, 0, null));
        }
        Field field = PlayerListS2CPacket.class.getDeclaredField("entries");
        field.setAccessible(true);
        field.set(packet, entries);
        mc.getNetworkHandler().onPlayerList(packet);
    }

    private void run(MinecraftClient mc, String line) throws Exception {
        LOG.info("[HARNESS] > {}", line);
        String[] parts = line.split(" ", 2);
        Module module = Modules.get().get(JoinYears.class);
        switch (parts[0]) {
            case "players" -> addFakePlayers(mc, parts[1].split(" "));
            case "mode" -> {
                mode = parts[1];
                count = 0;
            }
            case "on" -> {
                if (!module.isActive()) module.toggle();
            }
            case "off" -> {
                if (module.isActive()) module.toggle();
            }
            case "set" -> {
                String[] args = parts[1].split(" ");
                module.settings.get(args[0]).parse(args[1]);
            }
            case "tab" -> mc.options.playerListKey.setPressed(parts[1].equals("1"));
            case "manual" -> mc.getNetworkHandler().sendChatCommand("joindate " + parts[1]);
            case "foreign" -> {
                replies.add("---");
                replies.add("Player: " + parts[1]);
                replies.add("First seen: 2019/01/01");
                replies.add("Last seen: Online now");
                replies.add("Play time: 1d 1h 1m");
                replies.add("---");
            }
            case "chatlog" -> {
                LOG.info("[HARNESS] chat history follows");
                mc.inGameHud.getChatHud().getMessageHistory().forEach(sent -> LOG.info("[HARNESS]  sent: {}", sent));
            }
            case "shot" -> ScreenshotRecorder.saveScreenshot(mc.runDirectory, parts[1] + ".png", mc.getFramebuffer(), 1, text -> LOG.info("[HARNESS] {}", text.getString()));
            case "status" -> LOG.info("[HARNESS] status active={} cached={} commands={} listed={} info={}",
                module.isActive(), JoinYearCache.size(), seen.size(), mc.getNetworkHandler().getPlayerList().size(), module.getInfoString());
            default -> LOG.warn("[HARNESS] unknown command {}", parts[0]);
        }
    }
}
