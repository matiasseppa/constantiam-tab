package dev.joinyears.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.joinyears.util.JoinYearCache;
import dev.joinyears.util.YearColors;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public class JoinYearsCommand extends Command {
    public JoinYearsCommand() {
        super("join-years", "Shows the year colours and how many players are saved.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            MutableText legend = Text.empty();
            for (int year = YearColors.FIRST_YEAR; year <= YearColors.LAST_YEAR; year++) {
                legend.append(Text.literal(year + " ").withColor(YearColors.of(year)));
            }
            ChatUtils.sendMsg(Text.literal("Remembered players: " + JoinYearCache.size() + ", saved in " + JoinYearCache.file().getFileName() + ". ").append(legend));
            return SINGLE_SUCCESS;
        });
    }
}
