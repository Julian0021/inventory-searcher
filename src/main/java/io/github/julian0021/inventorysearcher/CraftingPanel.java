package io.github.julian0021.inventorysearcher;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Next to a crafting table: a button at its right edge that scans the containers in reach, and in its place a panel with
 * what they hold. Items from the panel go straight into the inventory, a cursor wouldn't survive the crafting table
 * closing in between. One widget for both, so they always follow the window when the recipe book moves it.
 */
public final class CraftingPanel extends AbstractWidget {
    private static final int MIN_COLUMNS = 3;
    private static final int MAX_COLUMNS = 9;
    private static final int PADDING = 7;
    private static final int GAP = 2;
    private static final int SEARCH_TOP = 30;
    private static final int GRID_TOP = 45;
    /** Room below the grid for the status line and the border. */
    private static final int GRID_BOTTOM = 21;
    private static final int BUTTON_WIDTH = 20;
    private static final int BUTTON_HEIGHT = 18;
    private static final int TEXT_COLOR = 0xFF404040;

    private static final WidgetSprites BUTTON_SPRITES = new WidgetSprites(
        Identifier.withDefaultNamespace("widget/button"),
        Identifier.withDefaultNamespace("widget/button_disabled"),
        Identifier.withDefaultNamespace("widget/button_highlighted")
    );
    private static final ItemStack BUTTON_ICON = new ItemStack(Items.CHEST);
    private static final Component TITLE = Lang.text("panel.title");

    /** The panel of the crafting screen that is showing, the screen hooks look it up here. */
    private static CraftingPanel current;

    private final Minecraft mc = Minecraft.getInstance();
    private final InventorySearcher searcher;
    private final CraftingScreen screen;
    private final IntSupplier leftPos;
    private final IntSupplier topPos;
    private final int imageWidth;
    private final int imageHeight;
    private final EditBox searchBox;

    private int buttonX, buttonY;
    private int panelX, panelY, panelWidth, panelHeight;
    private boolean pressed;

    public CraftingPanel(CraftingScreen screen, IntSupplier leftPos, IntSupplier topPos, int imageWidth, int imageHeight) {
        super(0, 0, 0, 0, TITLE);
        this.searcher = InventorySearcherMod.searcher();
        this.screen = screen;
        this.leftPos = leftPos;
        this.topPos = topPos;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;

        searchBox = new EditBox(mc.font, 0, 0, 10, 12, Lang.text("search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Lang.text("panel.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        CraftingSession session = searcher.crafting();
        if (session != null && session.isHost(screen)) {
            // the screen is built again when the table opens again or the window moves, typing goes on
            searchBox.setValue(session.search);
            searchBox.setFocused(session.searchFocused);
        }
        searchBox.setResponder(value -> {
            CraftingSession s = session();
            if (s == null) return;
            s.search = value;
            s.grid.setFilter(value);
        });

        current = this;
        layout();
    }

    /** The panel of this screen, or null when it has none. */
    public static CraftingPanel of(Object screen) {
        return current != null && current.screen == screen ? current : null;
    }

    /** The session of this screen while the panel is open. */
    private CraftingSession session() {
        CraftingSession session = searcher.crafting();
        return session != null && session.isHost(screen) ? session : null;
    }

    // Layout

    private void layout() {
        int left = leftPos.getAsInt();
        // at the right edge of the window, the recipe book never covers it there
        buttonX = left + imageWidth + GAP;
        buttonY = topPos.getAsInt() + 4;

        panelX = left + imageWidth + GAP;
        panelY = topPos.getAsInt();
        panelHeight = imageHeight;
        CraftingSession session = session();
        int columns = Math.max(MIN_COLUMNS, columnsFor(roomRight(screen.width, left, imageWidth)));
        panelWidth = panelWidth(columns);
        int rows = Math.max(1, (panelHeight - GRID_TOP - GRID_BOTTOM) / ItemGrid.SLOT);

        if (session != null) session.grid.setBounds(panelX + PADDING, panelY + GRID_TOP, columns, rows);
        searchBox.setX(panelX + PADDING + 1);
        searchBox.setY(panelY + SEARCH_TOP);
        searchBox.setWidth(columns * ItemGrid.SLOT + ItemGrid.SCROLLBAR_SPACE - 2);
    }

    // Where the game puts the window and the recipe book, see RecipeBookComponent.updateScreenPosition and initVisuals

    private static int vanillaLeftPos(int screenWidth, int imageWidth, boolean recipeBookBeside) {
        return recipeBookBeside ? 177 + (screenWidth - imageWidth - 200) / 2 : (screenWidth - imageWidth) / 2;
    }

    /** The left edge of the recipe book including its tabs, they stick out 30 to the left of it. */
    private static int vanillaRecipeBookLeft(int screenWidth, boolean overWindow) {
        return (screenWidth - 147) / 2 - (overWindow ? 0 : 86) - 30;
    }

    /**
     * How far the window and its recipe book move left so the panel fits to the right of them, as far as there is room
     * on the left. 0 when it fits already.
     *
     * @param overWindow whether the recipe book lies over the window instead of next to it
     */
    public static int shift(int screenWidth, int imageWidth, boolean recipeBookVisible, boolean overWindow) {
        int leftPos = vanillaLeftPos(screenWidth, imageWidth, recipeBookVisible && !overWindow);
        int groupLeft = recipeBookVisible ? Math.min(leftPos, vanillaRecipeBookLeft(screenWidth, overWindow)) : leftPos;
        int room = roomRight(screenWidth, leftPos, imageWidth);
        int wanted = panelWidth(MAX_COLUMNS);
        if (room >= wanted) return 0;
        return Math.max(0, Math.min(wanted - room, groupLeft - GAP));
    }

    private static int minWidthCachedFor = -1;
    private static int minWidthCached;

    /**
     * The narrowest screen where the recipe book still fits next to the window with the panel open. Below that it lies
     * over the window, like the game does on small screens.
     */
    public static int minWidthBesideRecipeBook(int imageWidth) {
        if (minWidthCachedFor != imageWidth) {
            minWidthCachedFor = imageWidth;
            minWidthCached = Integer.MAX_VALUE;
            for (int width = 0; width < 4096; width++) {
                int leftPos = vanillaLeftPos(width, imageWidth, true) - shift(width, imageWidth, true, false);
                if (vanillaRecipeBookLeft(width, false) >= 0 && columnsFor(roomRight(width, leftPos, imageWidth)) >= MIN_COLUMNS) {
                    minWidthCached = width;
                    break;
                }
            }
        }
        return minWidthCached;
    }

    private static int roomRight(int screenWidth, int leftPos, int imageWidth) {
        return screenWidth - (leftPos + imageWidth + GAP) - GAP;
    }

    private static int panelWidth(int columns) {
        return 2 * PADDING + columns * ItemGrid.SLOT + ItemGrid.SCROLLBAR_SPACE;
    }

    private static int columnsFor(int room) {
        return Math.min(MAX_COLUMNS, (room - 2 * PADDING - ItemGrid.SCROLLBAR_SPACE) / ItemGrid.SLOT);
    }

    private boolean overButton(double mx, double my) {
        if (session() != null) return false;
        return mx >= buttonX && mx < buttonX + BUTTON_WIDTH && my >= buttonY && my < buttonY + BUTTON_HEIGHT;
    }

    private boolean overPanel(double mx, double my) {
        return session() != null && mx >= panelX && mx < panelX + panelWidth && my >= panelY && my < panelY + panelHeight;
    }

    private int rescanX() {
        return closeX() - IconButton.SIZE - IconButton.GAP;
    }

    private int closeX() {
        return panelX + panelWidth - PADDING - IconButton.SIZE;
    }

    private int iconButtonY() {
        return panelY + 4;
    }

    private boolean overSmallButton(int x, double mx, double my) {
        return IconButton.isOver(x, iconButtonY(), mx, my);
    }

    // Rendering

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        layout();
        Font font = mc.font;
        CraftingSession session = session();
        if (session == null) {
            drawButton(graphics, font, mouseX, mouseY);
            return;
        }

        ItemGrid grid = session.grid;
        grid.update();
        ItemGrid.drawPanel(graphics, panelX, panelY, panelWidth, panelHeight);
        int textX = panelX + PADDING;
        int textWidth = panelWidth - 2 * PADDING;
        graphics.text(font, TITLE, textX, panelY + 6, TEXT_COLOR, false);
        IconButton.draw(graphics, rescanX(), iconButtonY(), IconButton.RESCAN, session.isIdle(), mouseX, mouseY);
        IconButton.draw(graphics, closeX(), iconButtonY(), IconButton.CLOSE, session.isIdle(), mouseX, mouseY);
        graphics.text(font, font.plainSubstrByWidth(searcher.summary(), textWidth), textX, panelY + 18, TEXT_COLOR, false);

        searchBox.extractRenderState(graphics, mouseX, mouseY, delta);
        grid.drawSlots(graphics);
        InventorySearcher.Entry hovered = grid.drawEntries(graphics, font, mouseX, mouseY);

        String status = session.status().isEmpty() ? searcher.status() : session.status();
        if (!status.isEmpty()) graphics.text(font, font.plainSubstrByWidth(status, textWidth), textX, grid.y() + grid.height() + 4, TEXT_COLOR, false);

        if (hovered != null && mc.player.containerMenu.getCarried().isEmpty()) {
            List<Component> lines = grid.tooltip(hovered);
            if (hovered.loose > 0) {
                lines.add(ItemGrid.hint("hint.pick_up"));
                lines.add(ItemGrid.hint("hint.move_to_inventory"));
                lines.add(ItemGrid.hint("hint.store"));
            } else {
                lines.add(ItemGrid.hint("hint.take_shulker_box"));
            }
            graphics.setTooltipForNextFrame(font, lines, hovered.stack.getTooltipImage(), mouseX, mouseY);
        } else if (overSmallButton(rescanX(), mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, Lang.text("button.rescan"), mouseX, mouseY);
        } else if (overSmallButton(closeX(), mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, Lang.text("button.close"), mouseX, mouseY);
        }
    }

    private void drawButton(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        boolean enabled = searcher.canOpenCraftingPanel(screen);
        boolean hovered = overButton(mouseX, mouseY);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON_SPRITES.get(enabled, hovered && enabled), buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
        graphics.item(BUTTON_ICON, buttonX + 2, buttonY + 1);
        if (!hovered) return;

        Component tooltip = Lang.text(enabled ? "panel.button" : "panel.button.disabled");
        graphics.setTooltipForNextFrame(font, font.split(tooltip, 200), mouseX, mouseY);
    }

    // Input

    @Override
    public boolean isMouseOver(double mx, double my) {
        return overButton(mx, my) || overPanel(mx, my);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        pressed = false;
        if (overButton(mx, my)) {
            pressed = true;
            if (searcher.canOpenCraftingPanel(screen) && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                playButtonClickSound(mc.getSoundManager());
                searcher.toggleCraftingPanel(screen);
            }
            return true;
        }

        CraftingSession session = session();
        if (session == null || !overPanel(mx, my)) {
            focusSearch(false);
            return false;
        }
        pressed = true;

        if (overSmallButton(rescanX(), mx, my) || overSmallButton(closeX(), mx, my)) {
            focusSearch(false);
            if (session.isIdle()) {
                playButtonClickSound(mc.getSoundManager());
                if (overSmallButton(rescanX(), mx, my)) searcher.rescan();
                else searcher.toggleCraftingPanel(screen);
            }
            return true;
        }
        if (searchBox.isMouseOver(mx, my)) {
            focusSearch(true);
            searchBox.mouseClicked(event, doubled);
            return true;
        }
        focusSearch(false);

        ItemGrid grid = session.grid;
        if (grid.startScrollbarDrag(mx, my) || !grid.contains(mx, my)) return true;

        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            // the held stack goes into the nearest chest with room
            if (session.acceptsCraftingInput()) searcher.depositCursor();
            return true;
        }
        InventorySearcher.Entry entry = grid.entryAt(mx, my);
        if (entry == null) return true;
        int stack = entry.stack.getMaxStackSize();
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (event.hasShiftDown()) searcher.withdraw(entry, stack);
            else session.withdrawToCursor(entry, stack);
        }
        // half of what a left click takes, rounded up like vanilla
        else if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) session.withdrawToCursor(entry, (int) Math.max(1, (Math.min(entry.loose, stack) + 1) / 2));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        CraftingSession session = session();
        if (session == null || !session.grid.isDraggingScrollbar()) return false;
        session.grid.dragScrollbar(event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        CraftingSession session = session();
        if (session != null) session.grid.stopScrollbarDrag();
        boolean handled = pressed;
        pressed = false;
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        CraftingSession session = session();
        if (session == null || !overPanel(mx, my)) return false;
        session.grid.scroll(scrollY);
        return true;
    }

    /** Whether the search field has the keyboard, then keys like the inventory key only type. */
    public boolean capturesKeyboard() {
        return session() != null && searchBox.isFocused();
    }

    /** Whether this widget took the last mouse press, its release belongs to it as well. */
    public boolean handlesRelease() {
        CraftingSession session = session();
        return pressed || session != null && session.grid.isDraggingScrollbar();
    }

    public boolean isDraggingScrollbar() {
        CraftingSession session = session();
        return session != null && session.grid.isDraggingScrollbar();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return capturesKeyboard() && searchBox.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return capturesKeyboard() && searchBox.charTyped(event);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) focusSearch(false);
    }

    public void unfocusSearch() {
        focusSearch(false);
    }

    private void focusSearch(boolean focused) {
        searchBox.setFocused(focused);
        CraftingSession session = session();
        if (session != null) session.searchFocused = focused;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
