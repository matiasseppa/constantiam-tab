package dev.joinyears.util;

/** Older is hotter, like item rarity: gold for the very first players, cooling through red and purple to plain grey for new ones. */
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

    private YearColors() {
    }

    public static int of(int year) {
        int index = Math.clamp(year - FIRST_YEAR, 0, COLORS.length - 1);
        return COLORS[index];
    }
}
