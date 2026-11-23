package io.github.julian0021.inventorysearcher;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The terminal of the {@link InventorySearcher}: a searchable, scrollable grid of everything stored in the containers
 * in reach, styled like a vanilla container.
 */
public class InventorySearcherScreen extends Screen {
    private static final Identifier SLOT_TEXTURE = Identifier.withDefaultNamespace("container/slot");
    private static final int SLOT = ItemGrid.SLOT;
    private static final int COLUMNS = 9;
    private static final int PADDING = 8;
    private static final int GRID_WIDTH = COLUMNS * SLOT;
    private static final int WIDTH = PADDING + GRID_WIDTH + ItemGrid.SCROLLBAR_SPACE + PADDING;
    private static final int GRID_TOP = 45;
    private static final int FIXED_HEIGHT = GRID_TOP + 16 + 3 * SLOT + 4 + SLOT + PADDING;

    private static final int TEXT_COLOR = 0xFF404040;

    private final Minecraft mc = Minecraft.getInstance();
    private final InventorySearcher searcher;
    private final ItemGrid grid;
    private boolean openingSettings;
    private final Inventory playerInventory;

    private EditBox searchBox;

    private int rows;
    private int left, top, height0;
    private int gridX, gridY;
    private int statusY;
    private int playerY, hotbarY;

    public InventorySearcherScreen(InventorySearcher searcher) {
        super(Component.literal("Inventory Searcher"));
        this.searcher = searcher;
        this.grid = new ItemGrid(searcher);
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
        statusY = gridY + rows * SLOT + 4;
        playerY = gridY + rows * SLOT + 16;
        hotbarY = playerY + 3 * SLOT + 4;
        grid.setBounds(gridX, gridY, COLUMNS, rows);

        String previous = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, gridX + 1, top + 30, GRID_WIDTH + ItemGrid.SCROLLBAR_SPACE - 2, 12, Component.literal("Search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal("Search...  @mod  #tooltip").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(previous);
        searchBox.setResponder(grid::setFilter);
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);
    }

    private int closeX() {
        return left + WIDTH - PADDING - IconButton.SIZE;
    }

    private int settingsX() {
        return closeX() - IconButton.SIZE - IconButton.GAP;
    }

    private int rescanX() {
        return settingsX() - IconButton.SIZE - IconButton.GAP;
    }

    private int iconButtonY() {
        return top + 4;
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
        ItemGrid.drawPanel(graphics, left, top, WIDTH, height0);
        grid.drawSlots(graphics);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_TEXTURE, gridX + col * SLOT, playerRowY(row), SLOT, SLOT);
            }
        }
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        grid.update();
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(font, title, gridX, top + 6, TEXT_COLOR, false);
        IconButton.draw(graphics, rescanX(), iconButtonY(), IconButton.RESCAN, true, mouseX, mouseY);
        IconButton.draw(graphics, settingsX(), iconButtonY(), IconButton.SETTINGS, true, mouseX, mouseY);
        IconButton.draw(graphics, closeX(), iconButtonY(), IconButton.CLOSE, true, mouseX, mouseY);
        String summary = searcher.summary();
        graphics.text(font, font.plainSubstrByWidth(summary, WIDTH - 2 * PADDING), gridX, top + 18, TEXT_COLOR, false);

        InventorySearcher.Entry hovered = grid.drawEntries(graphics, font, mouseX, mouseY);

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
                if (ItemGrid.isOver(mouseX, mouseY, x, y)) {
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
            lines.add(ItemGrid.hint("Shift + Left: store in the nearest chest"));
            graphics.setTooltipForNextFrame(font, lines, hoveredOwn.getTooltipImage(), mouseX, mouseY);
        } else if (iconButtonTooltip(mouseX, mouseY) != null) {
            graphics.setTooltipForNextFrame(font, Component.literal(iconButtonTooltip(mouseX, mouseY)), mouseX, mouseY);
        }
    }

    private String iconButtonTooltip(double mx, double my) {
        if (IconButton.isOver(rescanX(), iconButtonY(), mx, my)) return "Scan again (Ctrl + R)";
        if (IconButton.isOver(settingsX(), iconButtonY(), mx, my)) return "Settings";
        if (IconButton.isOver(closeX(), iconButtonY(), mx, my)) return "Close (Esc)";
        return null;
    }

    private List<Component> tooltip(InventorySearcher.Entry entry) {
        List<Component> lines = grid.tooltip(entry);
        if (entry.loose > 0) {
            lines.add(ItemGrid.hint("Left: pick up stack · Right: pick up half"));
            lines.add(ItemGrid.hint("Shift + Left: move a stack into inventory"));
            lines.add(ItemGrid.hint("Click with an item: store it"));
        } else {
            lines.add(ItemGrid.hint("Click: take a shulker box with it"));
        }
        return lines;
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
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && clickIconButton(mx, my)) return true;
        if (mx < left || my < top || mx >= left + WIDTH || my >= top + height0) {
            // like vanilla, a click outside the window drops the held stack, a right click a single item
            if (searcher.cursorStack().isEmpty()) return false;
            if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) searcher.dropCursor(0);
            else if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) searcher.dropCursor(1);
            return true;
        }

        int inventoryIndex = playerInventoryAt(mx, my);
        if (inventoryIndex >= 0) {
            if (click.hasShiftDown() && click.button() == InputConstants.MOUSE_BUTTON_LEFT) searcher.depositSlot(inventoryIndex);
            // 26.3 numbers mouse buttons from 1, container clicks still use 0 for left and 1 for right
            else if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) searcher.clickPlayerInventory(inventoryIndex, 0);
            else if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) searcher.clickPlayerInventory(inventoryIndex, 1);
            return true;
        }
        if (grid.startScrollbarDrag(mx, my)) return true;

        if (grid.contains(mx, my) && !searcher.cursorStack().isEmpty()) {
            searcher.depositCursor();
            return true;
        }

        InventorySearcher.Entry entry = grid.entryAt(mx, my);
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
        if (grid.isDraggingScrollbar()) {
            grid.dragScrollbar(click.y());
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent click) {
        grid.stopScrollbarDrag();
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        grid.scroll(scrollY);
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

    private boolean clickIconButton(double mx, double my) {
        Runnable action;
        if (IconButton.isOver(rescanX(), iconButtonY(), mx, my)) action = searcher::rescan;
        else if (IconButton.isOver(settingsX(), iconButtonY(), mx, my)) action = this::openSettings;
        else if (IconButton.isOver(closeX(), iconButtonY(), mx, my)) action = this::onClose;
        else return false;
        AbstractWidget.playButtonClickSound(mc.getSoundManager());
        action.run();
        return true;
    }

    private void openSettings() {
        // the terminal stays active while the settings are open
        openingSettings = true;
        mc.gui.setScreen(new SettingsScreen(this));
    }

    // Helpers

    private int playerInventoryAt(double mx, double my) {
        if (mx < gridX || mx >= gridX + GRID_WIDTH) return -1;
        int col = (int) (mx - gridX) / SLOT;
        for (int row = 0; row < 4; row++) {
            if (my >= playerRowY(row) && my < playerRowY(row) + SLOT) return row < 3 ? 9 + row * 9 + col : col;
        }
        return -1;
    }

    private int playerRowY(int row) {
        return row < 3 ? playerY + row * SLOT : hotbarY;
    }
}
