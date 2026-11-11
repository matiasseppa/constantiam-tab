package dev.constantiamtab.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.constantiamtab.util.JoinYearCache;
import dev.constantiamtab.util.YearColors;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public class ConstantiamTabCommand extends Command {
    public ConstantiamTabCommand() {
        super("constantiam-tab", "Shows the year and play time colours, and how many players are saved.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            MutableText legend = Text.empty();
            for (int year = YearColors.FIRST_YEAR; year <= YearColors.LAST_YEAR; year++) {
                legend.append(Text.literal(year + " ").withColor(YearColors.of(year)));
            }
            ChatUtils.sendMsg(Text.literal("Remembered players: " + JoinYearCache.size() + ", saved in " + JoinYearCache.file().getFileName() + ". ").append(legend));

            MutableText days = Text.literal("Play time: ");
            for (int step : YearColors.DAY_STEPS) {
                days.append(Text.literal(step + "d+ ").withColor(YearColors.ofDays(step)));
            }
            ChatUtils.sendMsg(days);
            return SINGLE_SUCCESS;
        });
    }
}
