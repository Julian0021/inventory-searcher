package io.github.julian0021.inventorysearcher;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The terminal of the {@link InventorySearcher}: a searchable, scrollable grid of everything stored in the containers
 * in reach, styled like a vanilla container.
 */
public class InventorySearcherScreen extends Screen {
    private static final Identifier SLOT_TEXTURE = Identifier.withDefaultNamespace("container/slot");
    private static final int SLOT = 18;
    private static final int COLUMNS = 9;
    private static final int PADDING = 8;
    private static final int SCROLLBAR_WIDTH = 12;
    private static final int GRID_WIDTH = COLUMNS * SLOT;
    private static final int WIDTH = PADDING + GRID_WIDTH + 4 + SCROLLBAR_WIDTH + PADDING;
    private static final int GRID_TOP = 45;
    private static final int FIXED_HEIGHT = GRID_TOP + 16 + 3 * SLOT + 4 + SLOT + PADDING;

    private static final int TEXT_COLOR = 0xFF404040;
    private static final int SHULKER_COLOR = 0xFFE0B0FF;

    private final Minecraft mc = Minecraft.getInstance();
    private final InventorySearcher searcher;
    private boolean openingSettings;
    private final Inventory playerInventory;

    private EditBox searchBox;
    private String filter = "";
    private List<InventorySearcher.Entry> filtered = List.of();
    private int filteredVersion = -1;
    private String filteredFor = null;

    private int rows;
    private int scrollRow;
    private boolean draggingScrollbar;

    private int left, top, height0;
    private int gridX, gridY;
    private int scrollbarX;
    private int statusY;
    private int playerY, hotbarY;

    public InventorySearcherScreen(InventorySearcher searcher) {
        super(Component.literal("Inventory Searcher"));
        this.searcher = searcher;
        this.playerInventory = mc.player.getInventory();
    }

    // Layout

    @Override
    protected void init() {
        rows = Mth.clamp((height - 10 - FIXED_HEIGHT) / SLOT, 1, 8);
        height0 = FIXED_HEIGHT + rows * SLOT;

        left = (width - WIDTH) / 2;
        top = (height - height0) / 2;
        gridX = left + PADDING;
        gridY = top + GRID_TOP;
        scrollbarX = gridX + GRID_WIDTH + 4;
        statusY = gridY + rows * SLOT + 4;
        playerY = gridY + rows * SLOT + 16;
        hotbarY = playerY + 3 * SLOT + 4;

        String previous = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, gridX + 1, top + 30, GRID_WIDTH + 4 + SCROLLBAR_WIDTH - 2, 12, Component.literal("Search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal("Search...  @mod  #tooltip").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(previous);
        searchBox.setResponder(value -> {
            filter = value;
            scrollRow = 0;
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Settings"), button -> {
            // the terminal stays active while the settings are open
            openingSettings = true;
            mc.gui.setScreen(new SettingsScreen(this));
        }).bounds(left + WIDTH - PADDING - 50, top + 4, 50, 12).build());
    }

    @Override
    public boolean isPauseScreen() {
        // the integrated server has to keep running, or the containers never open
        return false;
    }

    @Override
    public void removed() {
        if (openingSettings) {
            openingSettings = false;
            return;
        }
        searcher.onTerminalClosed();
    }

    // Rendering

    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        drawPanel(graphics, left, top, WIDTH, height0);

        // slots
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_TEXTURE, gridX + col * SLOT, gridY + row * SLOT, SLOT, SLOT);
            }
        }
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_TEXTURE, gridX + col * SLOT, playerRowY(row), SLOT, SLOT);
            }
        }

        // scrollbar track
        int trackHeight = rows * SLOT;
        drawInset(graphics, scrollbarX, gridY, SCROLLBAR_WIDTH, trackHeight);
        int maxScroll = maxScroll();
        int thumbHeight = maxScroll == 0 ? trackHeight - 2 : Math.max(10, (trackHeight - 2) * rows / (rows + maxScroll));
        int thumbY = gridY + 1 + (maxScroll == 0 ? 0 : (trackHeight - 2 - thumbHeight) * scrollRow / maxScroll);
        drawRaised(graphics, scrollbarX + 1, thumbY, SCROLLBAR_WIDTH - 2, thumbHeight, maxScroll == 0 ? 0xFFA0A0A0 : 0xFFC6C6C6);
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        updateFilter();
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(font, title, gridX, top + 6, TEXT_COLOR, false);
        String summary = searcher.summary();
        graphics.text(font, font.plainSubstrByWidth(summary, WIDTH - 2 * PADDING), gridX, top + 18, TEXT_COLOR, false);

        // stored items
        InventorySearcher.Entry hovered = null;
        int first = scrollRow * COLUMNS;
        for (int i = 0; i < rows * COLUMNS; i++) {
            int index = first + i;
            if (index >= filtered.size()) break;
            InventorySearcher.Entry entry = filtered.get(index);
            int x = gridX + (i % COLUMNS) * SLOT + 1;
            int y = gridY + (i / COLUMNS) * SLOT + 1;
            drawEntry(graphics, entry, x, y);
            if (isOver(mouseX, mouseY, x, y)) {
                hovered = entry;
                graphics.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
            }
        }

        if (filtered.isEmpty()) {
            String text = searcher.isScanning() ? "Scanning..." : searcher.entries().isEmpty() ? "Nothing found" : "No matches";
            graphics.centeredText(font, text, gridX + GRID_WIDTH / 2, gridY + rows * SLOT / 2 - 4, 0xFFFFFFFF);
        }

        // status line
        String status = searcher.status();
        if (!status.isEmpty()) graphics.text(font, font.plainSubstrByWidth(status, GRID_WIDTH + 16), gridX, statusY, TEXT_COLOR, false);

        // player inventory
        ItemStack hoveredOwn = ItemStack.EMPTY;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                ItemStack stack = playerInventory.getItem(row < 3 ? 9 + row * 9 + col : col);
                int x = gridX + col * SLOT + 1;
                int y = playerRowY(row) + 1;
                if (!stack.isEmpty()) {
                    graphics.item(stack, x, y);
                    graphics.itemDecorations(font, stack, x, y);
                }
                if (isOver(mouseX, mouseY, x, y)) {
                    hoveredOwn = stack;
                    graphics.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
                }
            }
        }

        ItemStack carried = searcher.cursorStack();
        if (!carried.isEmpty()) {
            graphics.item(carried, mouseX - 8, mouseY - 8);
            graphics.itemDecorations(font, carried, mouseX - 8, mouseY - 8);
        } else if (hovered != null) {
            graphics.setTooltipForNextFrame(font, tooltip(hovered), hovered.stack.getTooltipImage(), mouseX, mouseY);
        } else if (!hoveredOwn.isEmpty()) {
            List<Component> lines = new ArrayList<>(getTooltipFromItem(mc, hoveredOwn));
            lines.add(Component.empty());
            lines.add(Component.literal("Shift + Left: store in the nearest chest").withStyle(ChatFormatting.DARK_GRAY));
            graphics.setTooltipForNextFrame(font, lines, hoveredOwn.getTooltipImage(), mouseX, mouseY);
        }
    }

    private void drawEntry(GuiGraphicsExtractor graphics, InventorySearcher.Entry entry, int x, int y) {
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

    private List<Component> tooltip(InventorySearcher.Entry entry) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(mc, entry.stack));
        lines.add(Component.empty());
        lines.add(Component.literal("Stored: " + String.format("%,d", entry.loose)).withStyle(ChatFormatting.GRAY));
        if (entry.inShulkers > 0) {
            lines.add(Component.literal("In shulker boxes: " + String.format("%,d", entry.inShulkers)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        lines.add(Component.literal("In " + entry.containers.size() + (entry.containers.size() == 1 ? " container" : " containers")).withStyle(ChatFormatting.GRAY));

        if (entry.loose > 0) {
            lines.add(Component.literal("Left: pick up stack · Right: pick up half").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("Shift + Left: move a stack into inventory").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("Click with an item: store it").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal("Click: take a shulker box with it").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
        graphics.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
        graphics.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFFFFFFF);
        graphics.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        graphics.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, 0xFF555555);
    }

    private void drawInset(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF8B8B8B);
        graphics.fill(x, y, x + w - 1, y + 1, 0xFF373737);
        graphics.fill(x, y, x + 1, y + h - 1, 0xFF373737);
        graphics.fill(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        graphics.fill(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
    }

    private void drawRaised(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + h, color);
        graphics.fill(x, y, x + w - 1, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + h - 1, 0xFFFFFFFF);
        graphics.fill(x + 1, y + h - 1, x + w, y + h, 0xFF555555);
        graphics.fill(x + w - 1, y + 1, x + w, y + h, 0xFF555555);
    }

    // Input

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent click, boolean doubled) {
        if (InventorySearcherMod.openKey().matchesMouse(click)) {
            onClose();
            return true;
        }
        if (super.mouseClicked(click, doubled)) return true;

        double mx = click.x(), my = click.y();
        int inventoryIndex = playerInventoryAt(mx, my);
        if (inventoryIndex >= 0) {
            if (click.hasShiftDown() && click.button() == InputConstants.MOUSE_BUTTON_LEFT) searcher.depositSlot(inventoryIndex);
            // 26.3 numbers mouse buttons from 1, container clicks still use 0 for left and 1 for right
            else if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) searcher.clickPlayerInventory(inventoryIndex, 0);
            else if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) searcher.clickPlayerInventory(inventoryIndex, 1);
            return true;
        }
        if (mx >= scrollbarX && mx < scrollbarX + SCROLLBAR_WIDTH && my >= gridY && my < gridY + rows * SLOT) {
            draggingScrollbar = true;
            scrollTo(my);
            return true;
        }

        if (inGrid(mx, my) && !searcher.cursorStack().isEmpty()) {
            searcher.depositCursor();
            return true;
        }

        InventorySearcher.Entry entry = entryAt(mx, my);
        if (entry == null) return false;

        int stack = entry.stack.getMaxStackSize();
        switch (click.button()) {
            case InputConstants.MOUSE_BUTTON_LEFT -> {
                // like vanilla, shift moves one stack straight into the inventory
                if (click.hasShiftDown()) searcher.withdraw(entry, stack);
                else searcher.withdrawToCursor(entry, stack);
            }
            // half of what a left click would take, rounded up like vanilla
            case InputConstants.MOUSE_BUTTON_RIGHT -> searcher.withdrawToCursor(entry, (int) Math.max(1, (Math.min(entry.loose, stack) + 1) / 2));
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent click, double dx, double dy) {
        if (draggingScrollbar) {
            scrollTo(click.y());
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent click) {
        draggingScrollbar = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent input) {
        if (input.key() == InputConstants.KEY_R && input.hasControlDown()) {
            searcher.rescan();
            return true;
        }
        if (super.keyPressed(input)) return true;

        if (!searchBox.isFocused() && (mc.options.keyInventory.matches(input) || InventorySearcherMod.openKey().matches(input))) {
            onClose();
            return true;
        }
        return false;
    }

    // Helpers

    private void updateFilter() {
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
                    for (Component line : getTooltipFromItem(mc, stack)) builder.append(line.getString()).append('\n');
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

    private boolean inGrid(double mx, double my) {
        return mx >= gridX && mx < gridX + GRID_WIDTH && my >= gridY && my < gridY + rows * SLOT;
    }

    private InventorySearcher.Entry entryAt(double mx, double my) {
        if (!inGrid(mx, my)) return null;
        int col = (int) (mx - gridX) / SLOT;
        int row = (int) (my - gridY) / SLOT;
        int index = (scrollRow + row) * COLUMNS + col;
        return index < filtered.size() ? filtered.get(index) : null;
    }

    private int playerInventoryAt(double mx, double my) {
        if (mx < gridX || mx >= gridX + GRID_WIDTH) return -1;
        int col = (int) (mx - gridX) / SLOT;
        for (int row = 0; row < 4; row++) {
            if (my >= playerRowY(row) && my < playerRowY(row) + SLOT) return row < 3 ? 9 + row * 9 + col : col;
        }
        return -1;
    }

    private void scrollTo(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll == 0) return;
        double progress = (mouseY - gridY) / (rows * SLOT);
        scrollRow = Mth.clamp((int) Math.round(progress * maxScroll), 0, maxScroll);
    }

    private int maxScroll() {
        int totalRows = (filtered.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, totalRows - rows);
    }

    private int playerRowY(int row) {
        return row < 3 ? playerY + row * SLOT : hotbarY;
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17;
    }

    private static String formatCount(long count) {
        if (count < 1000) return Long.toString(count);
        if (count < 10_000) return String.format(Locale.ROOT, "%.1fk", count / 1000.0).replace(".0k", "k");
        if (count < 1_000_000) return (count / 1000) + "k";
        if (count < 10_000_000) return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0).replace(".0M", "M");
        return (count / 1_000_000) + "M";
    }
}
