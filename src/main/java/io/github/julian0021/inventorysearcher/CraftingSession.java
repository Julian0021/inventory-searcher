package io.github.julian0021.inventorysearcher;

import io.github.julian0021.inventorysearcher.mixin.AbstractRecipeBookScreenAccessor;
import io.github.julian0021.inventorysearcher.mixin.RecipeBookComponentAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.block.CraftingTableBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The panel next to a crafting table. A server keeps only one menu open, so the crafting table is closed while the
 * searcher opens the containers and opened again afterwards. The crafting grid goes into the inventory before that, so
 * nothing can drop on the ground, and back into the same slots once the table is open again.
 */
public final class CraftingSession {
    /** Slots of the crafting menu: 0 is the result, then the 3x3 grid. */
    private static final int GRID_START = 1;
    private static final int GRID_SIZE = 9;
    /** Putting the grid back gives up after this many clicks, a safety net against a server that keeps refusing them. */
    private static final int MAX_RESTORE_CLICKS = GRID_SIZE * 64 + 64;
    private static final int RECIPE_BOOK_COOLDOWN = 5;

    private enum Stage {
        /** The table is open on the server and can be used. */
        TableOpen,
        /** The cursor and the grid go into the inventory, then the table closes. */
        Preparing,
        /** The table is closed, the searcher works through the containers. */
        Away,
        /** Waiting for the server to open the table again. */
        Reopening,
        /** The grid goes back, or the clicked recipe gets placed. */
        Restoring
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final InventorySearcher searcher;
    private final SearcherConfig config;
    private final BlockPos table;
    final ItemGrid grid;
    String search = "";

    private CraftingScreen host;
    private boolean hostRemoved;
    private Stage stage = Stage.TableOpen;
    private int stageTicks;

    private ItemStack[] savedGrid;
    private final Set<Integer> unrestorable = new HashSet<>();
    private int restoreClicks;
    private RecipeDisplayId pendingRecipe;
    /** The recipe book of the screen before the table opened again, until the new one took over its search. */
    private RecipeBookComponent<?> previousBook;
    private boolean pendingUseMax;
    /** The inventory slot (0-35) with a stack taken for the cursor, picked up once the grid is back, or -1. */
    private int pickUpSlot = -1;

    private int countedVersion = -1;
    private int recipeBookCooldown;

    CraftingSession(InventorySearcher searcher, SearcherConfig config, CraftingScreen host, BlockPos table) {
        this.searcher = searcher;
        this.config = config;
        this.host = host;
        this.table = table;
        this.grid = new ItemGrid(searcher);
    }

    // State for the panel and the hooks

    boolean isHost(Object screen) {
        return screen == host;
    }

    boolean isHostMenu(Object menu) {
        return menu == host.getMenu();
    }

    /** Whether the crafting table on the screen is the menu open on the server, then the game handles it as usual. */
    boolean tableOpenOnServer() {
        return stage == Stage.TableOpen || stage == Stage.Preparing || stage == Stage.Restoring;
    }

    /** Whether the player may use the crafting table right now. */
    boolean acceptsCraftingInput() {
        return stage == Stage.TableOpen;
    }

    /** Nothing left to do, so the panel can be closed. */
    boolean isIdle() {
        return stage == Stage.TableOpen && !searcher.hasWork();
    }

    String status() {
        return switch (stage) {
            case Preparing -> "Putting the crafting grid away...";
            case Reopening -> "Opening the crafting table...";
            case Restoring -> pendingRecipe != null ? "Placing the recipe..." : "Putting the crafting grid back...";
            default -> "";
        };
    }

    /**
     * Takes items out of the containers onto the cursor. They wait in an inventory slot of their own while the table
     * opens again and are picked up from there. With other work waiting they only go into the inventory, the terminal
     * also takes onto the cursor only when nothing else is going on.
     */
    void withdrawToCursor(InventorySearcher.Entry entry, int amount) {
        if (acceptsCraftingInput() && !searcher.hasWork()) searcher.withdrawToCursor(entry, amount);
        else searcher.withdraw(entry, amount);
    }

    /** Called when a stack for the cursor waits in the inventory slot. */
    void pickUpLater(int inventorySlot) {
        pickUpSlot = inventorySlot;
    }

    /** Places the recipe once the crafting table is open again. */
    void placeRecipeLater(RecipeDisplayId id, boolean useMax) {
        pendingRecipe = id;
        pendingUseMax = useMax;
    }

    boolean isReopening() {
        return stage == Stage.Reopening;
    }

    void hostRemoved() {
        hostRemoved = true;
    }

    // Work loop

    /** Runs before the searcher's work loop, returns true while the loop has to wait for the crafting table. */
    boolean tick() {
        stageTicks++;
        updateRecipeBook();
        return switch (stage) {
            case TableOpen -> {
                if (!searcher.hasWork()) yield true;
                setStage(Stage.Preparing);
                yield prepare();
            }
            case Preparing -> prepare();
            case Away -> {
                if (searcher.hasWork() || !searcher.isWaitingForWork()) yield false;
                searcher.closeHeldContainer();
                reopen();
                yield true;
            }
            case Reopening -> {
                if (stageTicks > config.openTimeout) fail("The crafting table didn't open again");
                yield true;
            }
            case Restoring -> {
                restore();
                yield true;
            }
        };
    }

    private void setStage(Stage stage) {
        this.stage = stage;
        this.stageTicks = 0;
    }

    /** Puts the cursor and the crafting grid into the inventory, then closes the table. Returns false once it's closed. */
    private boolean prepare() {
        CraftingMenu menu = host.getMenu();
        if (mc.player.containerMenu != menu) {
            fail("The crafting table was closed");
            return true;
        }
        if (savedGrid == null) {
            if (!fitsIntoInventory(menu)) {
                searcher.dropJobs("Inventory full, can't put the crafting grid away");
                pendingRecipe = null;
                setStage(Stage.TableOpen);
                return true;
            }
            savedGrid = new ItemStack[GRID_SIZE];
            for (int i = 0; i < GRID_SIZE; i++) savedGrid[i] = menu.getSlot(GRID_START + i).getItem().copy();
            unrestorable.clear();
            restoreClicks = 0;
        }

        while (searcher.hasClickBudget()) {
            ItemStack carried = menu.getCarried();
            Slot next = carried.isEmpty() ? firstFilledGridSlot(menu) : inventorySlotFor(menu, carried);
            if (next == null) {
                // Everything is in the inventory. A stack that unexpectedly found no slot is put away by the server
                // when the table closes, there was room for it when this started.
                searcher.closeMenu(menu, false);
                setStage(Stage.Away);
                return false;
            }
            searcher.clickIn(menu, next.index, 0, ContainerInput.PICKUP);
        }
        return true;
    }

    private void reopen() {
        if (!(mc.level.getBlockState(table).getBlock() instanceof CraftingTableBlock)) {
            fail("The crafting table is gone");
            return;
        }
        if (!searcher.inReach(table)) {
            fail("The crafting table is out of reach");
            return;
        }
        setStage(Stage.Reopening);
        searcher.interactWith(table);
    }

    /** Called when a screen is about to show, returns whether it is the crafting table opening again. */
    boolean onTableOpened(Screen screen) {
        if (stage != Stage.Reopening || !(screen instanceof CraftingScreen crafting)) return false;
        // the new screen shows up as usual and takes over the panel, its recipe book takes over the search
        RecipeBookComponent<?> book = ((AbstractRecipeBookScreenAccessor) host).inventorySearcher$recipeBookComponent();
        if (((RecipeBookComponentAccessor) book).inventorySearcher$searchBox() != null) previousBook = book;
        host = crafting;
        setStage(Stage.Restoring);
        return true;
    }

    /** The old recipe book for the new one of the table that just opened again, once. */
    RecipeBookComponent<?> takePreviousRecipeBook(RecipeBookMenu menu) {
        if (previousBook == null || host.getMenu() != menu) return null;
        RecipeBookComponent<?> book = previousBook;
        previousBook = null;
        return book;
    }

    private void restore() {
        CraftingMenu menu = host.getMenu();
        if (mc.player.containerMenu != menu) return;
        if (searcher.hasWork() && menu.getCarried().isEmpty()) {
            // more to do in the containers first, the grid comes back afterwards
            setStage(Stage.Preparing);
            return;
        }
        if (pendingRecipe != null) {
            // placing a recipe clears the grid anyway, like a click in the recipe book always does
            mc.gameMode.handlePlaceRecipe(menu.containerId, pendingRecipe, pendingUseMax);
            pendingRecipe = null;
            savedGrid = null;
            pickUpSlot = -1;
            setStage(Stage.TableOpen);
            return;
        }

        while (searcher.hasClickBudget()) {
            if (savedGrid == null || restoreClicks++ >= MAX_RESTORE_CLICKS || !restoreStep(menu)) {
                savedGrid = null;
                pickUp(menu);
                setStage(Stage.TableOpen);
                return;
            }
        }
    }

    /** One click picks up the stack taken for the cursor, the server has an empty cursor once the grid is back. */
    private void pickUp(CraftingMenu menu) {
        int inventorySlot = pickUpSlot;
        pickUpSlot = -1;
        if (inventorySlot < 0 || !menu.getCarried().isEmpty()) return;
        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventorySlot && slot.hasItem()) {
                searcher.clickIn(menu, slot.index, 0, ContainerInput.PICKUP);
                return;
            }
        }
    }

    /** One click towards the saved grid, returns false once everything that can be is back. */
    private boolean restoreStep(CraftingMenu menu) {
        ItemStack carried = menu.getCarried();
        int target = nextSlotToRestore(menu);
        ItemStack wanted = target < 0 ? ItemStack.EMPTY : savedGrid[target - GRID_START];
        if (!carried.isEmpty() && (target < 0 || !ItemStack.isSameItemSameComponents(carried, wanted))) {
            // what's left on the cursor goes back into the inventory
            Slot into = inventorySlotFor(menu, carried);
            if (into == null) return false;
            searcher.clickIn(menu, into.index, 0, ContainerInput.PICKUP);
            return true;
        }
        if (target < 0) return false;

        int needed = wanted.getCount() - menu.getSlot(target).getItem().getCount();
        if (carried.isEmpty()) {
            Slot source = sourceSlot(menu, wanted);
            if (source == null) {
                // not in the inventory anymore
                unrestorable.add(target);
                return true;
            }
            // a whole stack that isn't bigger than needed, otherwise half of it
            searcher.clickIn(menu, source.index, source.getItem().getCount() <= needed ? 0 : 1, ContainerInput.PICKUP);
            return true;
        }
        // all of it if that's no more than needed, otherwise one at a time
        searcher.clickIn(menu, target, carried.getCount() <= needed ? 0 : 1, ContainerInput.PICKUP);
        return true;
    }

    private int nextSlotToRestore(CraftingMenu menu) {
        for (int i = 0; i < GRID_SIZE; i++) {
            int slot = GRID_START + i;
            ItemStack wanted = savedGrid[i];
            if (wanted.isEmpty() || unrestorable.contains(slot)) continue;
            ItemStack current = menu.getSlot(slot).getItem();
            if (current.isEmpty() || (ItemStack.isSameItemSameComponents(current, wanted) && current.getCount() < wanted.getCount())) return slot;
        }
        return -1;
    }

    private Slot firstFilledGridSlot(CraftingMenu menu) {
        for (int i = 0; i < GRID_SIZE; i++) {
            Slot slot = menu.getSlot(GRID_START + i);
            if (slot.hasItem()) return slot;
        }
        return null;
    }

    /** The biggest stack of the item in the player's inventory. */
    private Slot sourceSlot(AbstractContainerMenu menu, ItemStack wanted) {
        Slot best = null;
        for (Slot slot : playerSlots(menu)) {
            ItemStack item = slot.getItem();
            if (!ItemStack.isSameItemSameComponents(item, wanted)) continue;
            if (best == null || item.getCount() > best.getItem().getCount()) best = slot;
        }
        return best;
    }

    /** A player slot for the stack: one with the same item and room first, then an empty one. */
    private Slot inventorySlotFor(AbstractContainerMenu menu, ItemStack stack) {
        Slot empty = null;
        for (Slot slot : playerSlots(menu)) {
            ItemStack item = slot.getItem();
            if (item.isEmpty()) {
                if (empty == null) empty = slot;
            } else if (ItemStack.isSameItemSameComponents(item, stack) && item.getCount() < slot.getMaxStackSize(item)) {
                return slot;
            }
        }
        return empty;
    }

    /**
     * The main inventory and hotbar slots of the menu, without the ones queued for storing, those are about to leave,
     * and without the one waiting to be picked up.
     */
    private List<Slot> playerSlots(AbstractContainerMenu menu) {
        Set<Integer> reserved = reservedSlots();
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container != mc.player.getInventory() || slot.getContainerSlot() >= 36 || reserved.contains(slot.getContainerSlot())) continue;
            slots.add(slot);
        }
        return slots;
    }

    /** Whether the cursor and the whole grid fit into the inventory, the same way the clicks put them there. */
    private boolean fitsIntoInventory(CraftingMenu menu) {
        Set<Integer> reserved = reservedSlots();
        ItemStack[] inventory = new ItemStack[36];
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = reserved.contains(i) ? null : mc.player.getInventory().getItem(i).copy();
        }

        List<ItemStack> toPlace = new ArrayList<>();
        toPlace.add(menu.getCarried());
        for (int i = 0; i < GRID_SIZE; i++) toPlace.add(menu.getSlot(GRID_START + i).getItem());

        for (ItemStack stack : toPlace) {
            int left = stack.getCount();
            // onto stacks of the same item first, then into empty slots
            for (int i = 0; i < inventory.length && left > 0; i++) {
                ItemStack slot = inventory[i];
                if (slot == null || slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
                int moved = Math.min(left, stack.getMaxStackSize() - slot.getCount());
                if (moved <= 0) continue;
                slot.grow(moved);
                left -= moved;
            }
            for (int i = 0; i < inventory.length && left > 0; i++) {
                if (inventory[i] == null || !inventory[i].isEmpty()) continue;
                int moved = Math.min(left, stack.getMaxStackSize());
                inventory[i] = stack.copyWithCount(moved);
                left -= moved;
            }
            if (left > 0) return false;
        }
        return true;
    }

    private Set<Integer> reservedSlots() {
        Set<Integer> reserved = searcher.depositSlots();
        if (pickUpSlot >= 0) reserved.add(pickUpSlot);
        return reserved;
    }

    private void fail(String message) {
        mc.player.sendOverlayMessage(Component.literal(message));
        searcher.deactivate();
    }

    /** Called when the searcher stops: a screen whose table is closed goes away, the recipe book counts without the containers again. */
    void end() {
        if (hostRemoved || mc.gui.screen() != host) return;
        if (!tableOpenOnServer()) {
            mc.gui.setScreen(null);
            return;
        }
        // the window moves back, the recipe book counts without the containers again
        searcher.relayoutLater(host);
        refreshRecipeBook();
    }

    // Recipe book

    /** Lets the recipe book count again when the stored items changed, at most every few ticks. */
    private void updateRecipeBook() {
        if (recipeBookCooldown > 0) {
            recipeBookCooldown--;
            return;
        }
        if (searcher.version() == countedVersion) return;
        countedVersion = searcher.version();
        recipeBookCooldown = RECIPE_BOOK_COOLDOWN;
        refreshRecipeBook();
    }

    private void refreshRecipeBook() {
        if (mc.gui.screen() != host) return;
        RecipeBookComponent<?> book = ((AbstractRecipeBookScreenAccessor) host).inventorySearcher$recipeBookComponent();
        // a hidden recipe book counts everything again when it opens
        if (book.isVisible()) ((RecipeBookComponentAccessor) book).inventorySearcher$updateStackedContents();
    }
}
