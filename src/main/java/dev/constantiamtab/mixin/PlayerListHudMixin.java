package dev.constantiamtab.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.constantiamtab.modules.ConstantiamTab;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.font.TextRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow private Text header;
    @Shadow private Text footer;

    /** Wraps the calls rather than getPlayerName itself, since Meteor's Better Tab replaces that method's result outright. */
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/PlayerListHud;getPlayerName(Lnet/minecraft/client/network/PlayerListEntry;)Lnet/minecraft/text/Text;")
    )
    private Text ctab$addYear(PlayerListHud hud, PlayerListEntry entry, Operation<Text> original) {
        return ConstantiamTab.decorate(entry, original.call(hud, entry));
    }

    /** Sorts before the list is cut to its maximum length, so the sort covers every player. */
    @ModifyArg(
        method = "collectPlayerEntries",
        at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;sorted(Ljava/util/Comparator;)Ljava/util/stream/Stream;")
    )
    private Comparator<PlayerListEntry> ctab$sort(Comparator<PlayerListEntry> serverOrder) {
        return ConstantiamTab.sorting(serverOrder);
    }

    /** Players who have left go after everyone online, outside the sort. */
    @ModifyReturnValue(method = "collectPlayerEntries", at = @At("RETURN"))
    private List<PlayerListEntry> ctab$addLeft(List<PlayerListEntry> entries) {
        return ConstantiamTab.withLeftPlayers(entries);
    }

    /** Called just before each head is drawn, which tells the head drawing below whose head it is. */
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/PlayerListEntry;getSkinTextures()Lnet/minecraft/entity/player/SkinTextures;")
    )
    private SkinTextures ctab$noteHead(PlayerListEntry entry, Operation<SkinTextures> original) {
        ConstantiamTab.drawingHead(entry);
        return original.call(entry);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/PlayerSkinDrawer;draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;IIIZZI)V")
    )
    private void ctab$dimHead(DrawContext context, Identifier texture, int x, int y, int size, boolean hat, boolean upsideDown, int color, Operation<Void> original) {
        original.call(context, texture, x, y, size, hat, upsideDown, ConstantiamTab.headColor(color));
    }

    /** No connection bars or ping for players who aren't connected. Better Tab's ping number runs inside the original. */
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/PlayerListHud;renderLatencyIcon(Lnet/minecraft/client/gui/DrawContext;IIILnet/minecraft/client/network/PlayerListEntry;)V")
    )
    private void ctab$latency(PlayerListHud hud, DrawContext context, int width, int x, int y, PlayerListEntry entry, Operation<Void> original) {
        if (!ConstantiamTab.isLeft(entry)) original.call(hud, context, width, x, y, entry);
    }

    /**
     * Where vanilla settles the list's width, after counting the rows and columns and before drawing anything: sets
     * the rows and columns from the layout options, scales the whole list down when it can't otherwise fit, and moves
     * it left to make room for the statistics panel on its right.
     * Locals in 1.21.11's PlayerListHud.render: 8 the widest name, 10 the entry count, 11 rows per column, 12 columns,
     * 14 the score's width. The column width is worked out from them rather than from the argument, which Better Tab
     * changes when it shows ping as a number.
     */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int ctab$layout(int natural, int limit, Operation<Integer> original,
                            @Local(argsOnly = true) DrawContext context, @Local(argsOnly = true) int screenWidth,
                            @Local(index = 8) int nameWidth, @Local(index = 10) int count, @Local(index = 11) LocalIntRef rows,
                            @Local(index = 12) LocalIntRef columns, @Local(index = 14) int scoreWidth) {
        int textLines = 0;
        if (header != null) textLines += client.textRenderer.wrapLines(header, screenWidth - 50).size();
        if (footer != null) textLines += client.textRenderer.wrapLines(footer, screenWidth - 50).size();

        int panel = ConstantiamTab.statsPanelWidth();
        int reserved = panel == 0 ? 0 : panel + ConstantiamTab.PANEL_GAP;
        // Heads are shown in singleplayer and on servers with encryption, as vanilla decides
        boolean heads = client.isInSingleplayer() || client.getNetworkHandler().getConnection().isEncrypted();
        int columnWidth = (heads ? 9 : 0) + nameWidth + scoreWidth + 13;
        ConstantiamTab.Layout layout = ConstantiamTab.layout(count, columnWidth, screenWidth, client.getWindow().getScaledHeight(), textLines, reserved);
        if (layout == null) return original.call(natural, limit);
        rows.set(layout.rows());
        columns.set(layout.columns());
        float scale = layout.scale();
        if (scale < 1 || reserved > 0) {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(screenWidth / 2f, 0);
            context.getMatrices().scale(scale, scale);
            context.getMatrices().translate(-screenWidth / 2f - reserved / 2f, 0);
            ctab$pushed = true;
        }
        int room = (int) ((scale < 1 ? (screenWidth - 20) / scale : limit) - reserved);
        int width = Math.min(layout.columns() * layout.columnWidth(), Math.max(room, layout.columnWidth()));
        // Where vanilla will put the columns' right edge, centred on the screen before the move left
        int perColumn = width / layout.columns();
        ctab$panelX = reserved == 0 ? Integer.MIN_VALUE : screenWidth / 2 + (perColumn * layout.columns() + (layout.columns() - 1) * 5) / 2 + 1 + ConstantiamTab.PANEL_GAP;
        return width;
    }

    @Unique
    private boolean ctab$pushed;
    @Unique
    private int ctab$panelX = Integer.MIN_VALUE;
    @Unique
    private int ctab$rowsTop, ctab$rowsBottom;

    @Inject(method = "render", at = @At("HEAD"))
    private void ctab$start(DrawContext context, int screenWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        ctab$panelX = Integer.MIN_VALUE;
        ctab$rowsTop = Integer.MAX_VALUE;
        ctab$rowsBottom = Integer.MIN_VALUE;
    }

    /** Each player's name; notes how far down the rows go, for the statistics panel beside them. */
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V")
    )
    private void ctab$nameLine(DrawContext context, TextRenderer font, Text text, int x, int y, int color, Operation<Void> original) {
        ctab$rowsTop = Math.min(ctab$rowsTop, y);
        ctab$rowsBottom = Math.max(ctab$rowsBottom, y + 8);
        original.call(context, font, text, x, y, color);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ctab$finish(DrawContext context, int screenWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        if (ctab$panelX != Integer.MIN_VALUE && ctab$rowsTop <= ctab$rowsBottom) {
            ConstantiamTab.drawStatsPanel(context, ctab$panelX, ctab$rowsTop - 1, ctab$rowsBottom + 1);
        }
        if (!ctab$pushed) return;
        ctab$pushed = false;
        context.getMatrices().popMatrix();
    }
}
