package dev.constantiamtab.util;

/**
 * Rarer is hotter, like item rarity. For join years, older is rarer: gold for the very first players, cooling through red
 * and purple to plain grey for new ones. Play time uses the same colours the other way round: the most days are gold.
 */
public final class YearColors {
    public static final int FIRST_YEAR = 2016;
    public static final int LAST_YEAR = 2026;
    public static final int UNKNOWN = 0x555555;

    private static final int[] COLORS = {
        0xFFD700, // 2016 gold
        0xFF8C00, // 2017 orange
        0xFF4040, // 2018 red
        0xFF55AA, // 2019 pink
        0xC060FF, // 2020 purple
        0x7080FF, // 2021 indigo
        0x40B8FF, // 2022 sky blue
        0x40E0C8, // 2023 teal
        0x55E055, // 2024 green
        0xC8F070, // 2025 pale lime
        0xB0B0B0, // 2026 grey
    };

    /** Lowest day count for each colour, from grey up to gold. */
    public static final int[] DAY_STEPS = {0, 1, 3, 7, 14, 30, 60, 100, 200, 365, 730};

    private YearColors() {
    }

    public static int of(int year) {
        int index = Math.clamp(year - FIRST_YEAR, 0, COLORS.length - 1);
        return COLORS[index];
    }

    public static int ofDays(int days) {
        int step = 0;
        while (step + 1 < DAY_STEPS.length && days >= DAY_STEPS[step + 1]) step++;
        return COLORS[COLORS.length - 1 - step];
    }
}
