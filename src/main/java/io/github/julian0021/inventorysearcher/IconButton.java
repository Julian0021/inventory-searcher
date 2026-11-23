package io.github.julian0021.inventorysearcher;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The small square buttons with a pixel icon at the top of the terminal and the crafting table panel. */
final class IconButton {
    static final int SIZE = 11;
    /** Room between two buttons next to each other. */
    static final int GAP = 2;

    static final String[] RESCAN = {
        "..XXX.X",
        ".X...XX",
        "X...XXX",
        "X......",
        "X.....X",
        ".X...X.",
        "..XXX..",
    };
    static final String[] SETTINGS = {
        "..X.X..",
        ".XXXXX.",
        "XX...XX",
        ".X...X.",
        "XX...XX",
        ".XXXXX.",
        "..X.X..",
    };
    static final String[] CLOSE = {
        "X...X",
        ".X.X.",
        "..X..",
        ".X.X.",
        "X...X",
    };

    private static final int ICON_COLOR = 0xFF404040;
    private static final int DISABLED_ICON_COLOR = 0xFF707070;

    private IconButton() {
    }

    static boolean isOver(int x, int y, double mx, double my) {
        return mx >= x && mx < x + SIZE && my >= y && my < y + SIZE;
    }

    /** Raised like a vanilla button, the icon drawn pixel by pixel: the font has no readable glyph for a circular arrow. */
    static void draw(GuiGraphicsExtractor graphics, int x, int y, String[] icon, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && isOver(x, y, mouseX, mouseY);
        ItemGrid.drawRaised(graphics, x, y, SIZE, SIZE, hovered ? 0xFFE0E0E0 : enabled ? 0xFFC6C6C6 : 0xFFA0A0A0);
        int color = enabled ? ICON_COLOR : DISABLED_ICON_COLOR;
        int left = x + (SIZE - icon[0].length()) / 2, top = y + (SIZE - icon.length) / 2;
        for (int row = 0; row < icon.length; row++) {
            for (int col = 0; col < icon[row].length(); col++) {
                if (icon[row].charAt(col) == 'X') graphics.fill(left + col, top + row, left + col + 1, top + row + 1, color);
            }
        }
    }
}
