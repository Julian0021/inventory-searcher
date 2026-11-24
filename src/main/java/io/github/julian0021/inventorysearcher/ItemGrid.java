package io.github.julian0021.inventorysearcher;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A searchable, scrollable grid of the stored items, used by the terminal and by the panel next to the crafting table. */
final class ItemGrid {
    static final int SLOT = 18;
    static final int SCROLLBAR_WIDTH = 12;
    /** Room the scrollbar takes to the right of the slots. */
    static final int SCROLLBAR_SPACE = 4 + SCROLLBAR_WIDTH;

    private static final Identifier SLOT_TEXTURE = Identifier.withDefaultNamespace("container/slot");
    private static final int SHULKER_COLOR = 0xFFE0B0FF;

    private final Minecraft mc = Minecraft.getInstance();
    private final InventorySearcher searcher;

    private String filter = "";
    private List<InventorySearcher.Entry> filtered = List.of();
    private int filteredVersion = -1;
    private String filteredFor;

    private int x, y;
    private int columns = 9, rows = 1;
    private int scrollRow;
    private boolean draggingScrollbar;

    ItemGrid(InventorySearcher searcher) {
        this.searcher = searcher;
    }

    // Layout

    /** Puts the top left corner of the slots at x, y. */
    void setBounds(int x, int y, int columns, int rows) {
        this.x = x;
        this.y = y;
        this.columns = columns;
        this.rows = rows;
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    int width() {
        return columns * SLOT;
    }

    int height() {
        return rows * SLOT;
    }

    private int scrollbarX() {
        return x + width() + 4;
    }

    // Filter

    void setFilter(String filter) {
        this.filter = filter;
        scrollRow = 0;
    }

    /** Applies the filter again when it or the stored items changed. */
    void update() {
        if (filteredVersion == searcher.version() && filter.equals(filteredFor)) return;
        filteredVersion = searcher.version();
        filteredFor = filter;

        String[] tokens = filter.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<InventorySearcher.Entry> result = new ArrayList<>();
        for (InventorySearcher.Entry entry : searcher.entries()) {
            if (matches(entry.stack, tokens)) result.add(entry);
        }
        filtered = result;
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    private boolean matches(ItemStack stack, String[] tokens) {
        String name = null;
        String tooltip = null;
        for (String token : tokens) {
            if (token.isEmpty()) continue;

            if (token.startsWith("@")) {
                if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().contains(token.substring(1))) return false;
            } else if (token.startsWith("#")) {
                if (tooltip == null) {
                    StringBuilder builder = new StringBuilder();
                    for (Component line : Screen.getTooltipFromItem(mc, stack)) builder.append(line.getString()).append('\n');
                    tooltip = builder.toString().toLowerCase(Locale.ROOT);
                }
                if (!tooltip.contains(token.substring(1))) return false;
            } else {
                if (name == null) name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                if (!name.contains(token)) return false;
            }
        }
        return true;
    }

    // Drawing

    /** The empty slots and the scrollbar. */
    void drawSlots(GuiGraphicsExtractor graphics) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_TEXTURE, x + col * SLOT, y + row * SLOT, SLOT, SLOT);
            }
        }

        int trackHeight = height();
        drawInset(graphics, scrollbarX(), y, SCROLLBAR_WIDTH, trackHeight);
        int maxScroll = maxScroll();
        int thumbHeight = maxScroll == 0 ? trackHeight - 2 : Math.max(10, (trackHeight - 2) * rows / (rows + maxScroll));
        int thumbY = y + 1 + (maxScroll == 0 ? 0 : (trackHeight - 2 - thumbHeight) * scrollRow / maxScroll);
        drawRaised(graphics, scrollbarX() + 1, thumbY, SCROLLBAR_WIDTH - 2, thumbHeight, maxScroll == 0 ? 0xFFA0A0A0 : 0xFFC6C6C6);
    }

    /** The stored items in the slots, returns the one under the mouse or null. */
    InventorySearcher.Entry drawEntries(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        InventorySearcher.Entry hovered = null;
        int first = scrollRow * columns;
        for (int i = 0; i < rows * columns; i++) {
            int index = first + i;
            if (index >= filtered.size()) break;
            InventorySearcher.Entry entry = filtered.get(index);
            int ex = x + (i % columns) * SLOT + 1;
            int ey = y + (i / columns) * SLOT + 1;
            drawEntry(graphics, font, entry, ex, ey);
            if (isOver(mouseX, mouseY, ex, ey)) {
                hovered = entry;
                graphics.fill(ex, ey, ex + 16, ey + 16, 0x80FFFFFF);
            }
        }

        if (filtered.isEmpty()) {
            Component text = Lang.text(searcher.isScanning() ? "grid.scanning" : searcher.entries().isEmpty() ? "grid.nothing_found" : "grid.no_matches");
            graphics.centeredText(font, text, x + width() / 2, y + height() / 2 - 4, 0xFFFFFFFF);
        }
        return hovered;
    }

    private void drawEntry(GuiGraphicsExtractor graphics, Font font, InventorySearcher.Entry entry, int x, int y) {
        boolean onlyInShulkers = entry.loose == 0;
        graphics.item(entry.stack, x, y);
        graphics.itemDecorations(font, entry.stack, x, y, "");

        String count = formatCount(onlyInShulkers ? entry.inShulkers : entry.loose);
        if (!onlyInShulkers && entry.loose == 1) count = "";

        int color = onlyInShulkers ? SHULKER_COLOR : 0xFFFFFFFF;
        if (count.length() <= 2) {
            if (!count.isEmpty()) graphics.text(font, count, x + 17 - font.width(count), y + 9, color, true);
        } else {
            // bigger numbers in a smaller font so they fit in the slot
            float scale = 0.666f;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 16 - font.width(count) * scale, y + 16 - 7 * scale);
            graphics.pose().scale(scale, scale);
            graphics.text(font, count, 0, 0, color, true);
            graphics.pose().popMatrix();
        }

        if (entry.inShulkers > 0) {
            // small marker for items that are (also) stored in shulker boxes
            graphics.fill(x, y, x + 3, y + 3, 0xFF9B59B6);
        }
    }

    /** The item's tooltip with what is stored where, the caller adds what clicking it does. */
    List<Component> tooltip(InventorySearcher.Entry entry) {
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, entry.stack));
        lines.add(Component.empty());
        lines.add(Lang.text("tooltip.stored", String.format("%,d", entry.loose)).withStyle(ChatFormatting.GRAY));
        if (entry.inShulkers > 0) {
            lines.add(Lang.text("tooltip.in_shulker_boxes", String.format("%,d", entry.inShulkers)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        lines.add(Lang.text(entry.containers.size() == 1 ? "tooltip.in_container" : "tooltip.in_containers", entry.containers.size()).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /** A line saying what a click does, from its lang key. */
    static Component hint(String key) {
        return Lang.text(key).withStyle(ChatFormatting.DARK_GRAY);
    }

    static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
        graphics.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
        graphics.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFFFFFFF);
        graphics.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        graphics.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, 0xFF555555);
    }

    static void drawInset(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF8B8B8B);
        graphics.fill(x, y, x + w - 1, y + 1, 0xFF373737);
        graphics.fill(x, y, x + 1, y + h - 1, 0xFF373737);
        graphics.fill(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        graphics.fill(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
    }

    static void drawRaised(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + h, color);
        graphics.fill(x, y, x + w - 1, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + h - 1, 0xFFFFFFFF);
        graphics.fill(x + 1, y + h - 1, x + w, y + h, 0xFF555555);
        graphics.fill(x + w - 1, y + 1, x + w, y + h, 0xFF555555);
    }

    static boolean isOver(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17;
    }

    static String formatCount(long count) {
        if (count < 1000) return Long.toString(count);
        if (count < 10_000) return String.format(Locale.ROOT, "%.1fk", count / 1000.0).replace(".0k", "k");
        if (count < 1_000_000) return (count / 1000) + "k";
        if (count < 10_000_000) return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0).replace(".0M", "M");
        return (count / 1_000_000) + "M";
    }

    // Input

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + width() && my >= y && my < y + height();
    }

    InventorySearcher.Entry entryAt(double mx, double my) {
        if (!contains(mx, my)) return null;
        int col = (int) (mx - x) / SLOT;
        int row = (int) (my - y) / SLOT;
        int index = (scrollRow + row) * columns + col;
        return index < filtered.size() ? filtered.get(index) : null;
    }

    /** Starts dragging when the click is on the scrollbar, returns whether it was. */
    boolean startScrollbarDrag(double mx, double my) {
        if (mx < scrollbarX() || mx >= scrollbarX() + SCROLLBAR_WIDTH || my < y || my >= y + height()) return false;
        draggingScrollbar = true;
        scrollTo(my);
        return true;
    }

    boolean isDraggingScrollbar() {
        return draggingScrollbar;
    }

    void dragScrollbar(double my) {
        if (draggingScrollbar) scrollTo(my);
    }

    void stopScrollbarDrag() {
        draggingScrollbar = false;
    }

    void scroll(double scrollY) {
        if (scrollY != 0) scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll());
    }

    private void scrollTo(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll == 0) return;
        double progress = (mouseY - y) / height();
        scrollRow = Mth.clamp((int) Math.round(progress * maxScroll), 0, maxScroll);
    }

    private int maxScroll() {
        int totalRows = (filtered.size() + columns - 1) / columns;
        return Math.max(0, totalRows - rows);
    }
}
