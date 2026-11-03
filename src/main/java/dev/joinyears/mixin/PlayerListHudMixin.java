package dev.joinyears.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.joinyears.modules.JoinYears;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wraps the calls rather than getPlayerName itself, since Meteor's Better Tab replaces that method's result outright. */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/PlayerListHud;getPlayerName(Lnet/minecraft/client/network/PlayerListEntry;)Lnet/minecraft/text/Text;")
    )
    private Text joinyears$addYear(PlayerListHud hud, PlayerListEntry entry, Operation<Text> original) {
        return JoinYears.decorate(entry, original.call(hud, entry));
    }
}
