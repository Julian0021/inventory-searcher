package io.github.julian0021.inventorysearcher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Opens every container in reach without showing its screen, caches the contents and shows them in a searchable
 * terminal. Taking items out of the terminal clicks them out of the real containers, so client and server stay in sync.
 */
public class InventorySearcher {
    private static final int MAX_STALLED_CLICKS = 3;
    private static final int SETTLE_TICKS = 2;

    private final Minecraft mc = Minecraft.getInstance();
    private final SearcherConfig config;
    private boolean active;

    // Containers and their cached contents

    private final Map<BlockPos, StorageContainer> containers = new LinkedHashMap<>();
    private final Map<BlockPos, List<ItemStack>> snapshots = new HashMap<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private List<Entry> entries = List.of();
    private int version;
    private long totalItems;
    private int excludedInReach;

    // Work queue, only one container can be open at a time

    private final Deque<StorageContainer> scanQueue = new ArrayDeque<>();
    private final Deque<Withdrawal> withdrawals = new ArrayDeque<>();
    private Withdrawal withdrawal;
    private final Deque<Deposit> deposits = new ArrayDeque<>();
    private Deposit deposit;
    private boolean scanning;

    private Phase phase = Phase.Idle;
    private StorageContainer target;
    private AbstractContainerMenu menu;
    private int phaseTicks;
    private int delayTicks;
    private volatile int openContainerId = -1;
    private volatile int serverClosedId = -1;

    private boolean closingTerminal;
    private String status = "";

    public InventorySearcher(SearcherConfig config) {
        this.config = config;
    }

    // Lifecycle

    public boolean isActive() {
        return active;
    }

    public void toggle() {
        if (active) deactivate();
        else activate();
    }

    /** Scans the containers in reach and opens the terminal. */
    public void activate() {
        if (active || mc.player == null || mc.level == null) return;
        active = true;

        resetWork();
        clearCache();
        closingTerminal = false;
        scan();
        mc.gui.setScreen(new InventorySearcherScreen(this));
    }

    /** Closes the terminal and any container that is still open. */
    public void deactivate() {
        if (!active) return;
        active = false;

        if (menu != null) closeMenu(menu, true);
        else if (mc.player != null && !mc.player.inventoryMenu.getCarried().isEmpty()) closeMenu(mc.player.inventoryMenu, true);
        resetWork();
        clearCache();

        if (!closingTerminal && mc.gui.screen() instanceof InventorySearcherScreen) {
            closingTerminal = true;
            mc.gui.setScreen(null);
        }
        closingTerminal = false;
    }

    /** Called by the terminal when it gets closed. */
    public void onTerminalClosed() {
        if (closingTerminal || !active) return;
        closingTerminal = true;
        deactivate();
    }

    /** Rescans all containers in reach. */
    public void rescan() {
        if (mc.player == null) return;
        clearCache();
        scanQueue.clear();
        scan();
    }

    private void scan() {
        containers.clear();

        boolean enderChestAdded = false;
        List<StorageContainer> found = new ArrayList<>();
        Set<BlockEntityType<?>> types = config.containerTypes();
        for (BlockEntity blockEntity : blockEntitiesInReach()) {
            BlockEntityType<?> type = blockEntity.getType();
            if (!types.contains(type)) continue;

            BlockPos pos = blockEntity.getBlockPos();
            if (!inReach(pos)) continue;

            StorageContainer container = containerAt(pos, blockEntity.getBlockState(), type);
            if (containers.containsKey(container.key())) continue;
            containers.put(container.key(), container);
            found.add(container);
        }

        Vec3 eye = mc.player.getEyePosition();
        found.sort(Comparator.comparingDouble(c -> c.distanceTo(eye)));

        excludedInReach = 0;
        for (Iterator<StorageContainer> it = found.iterator(); it.hasNext(); ) {
            StorageContainer container = it.next();
            if (!exclusions().isExcluded(container.key())) continue;
            it.remove();
            containers.remove(container.key());
            excludedInReach++;
        }

        for (StorageContainer container : found) {
            // every ender chest opens the same inventory
            if (container.type() == BlockEntityTypes.ENDER_CHEST) {
                if (enderChestAdded) {
                    containers.remove(container.key());
                    continue;
                }
                enderChestAdded = true;
            }
            scanQueue.add(container);
        }

        scanning = !scanQueue.isEmpty();
        status = scanning ? "" : "No containers in reach";
        version++;
    }

    private void resetWork() {
        scanQueue.clear();
        withdrawals.clear();
        withdrawal = null;
        deposits.clear();
        deposit = null;
        scanning = false;
        phase = Phase.Idle;
        target = null;
        menu = null;
        openContainerId = -1;
        serverClosedId = -1;
        phaseTicks = 0;
        delayTicks = 0;
        status = "";
    }

    private void clearCache() {
        containers.clear();
        snapshots.clear();
        failed.clear();
        entries = List.of();
        totalItems = 0;
        version++;
    }

    /** The block entities of the chunks the player can reach into. */
    private List<BlockEntity> blockEntitiesInReach() {
        List<BlockEntity> found = new ArrayList<>();
        BlockPos center = mc.player.blockPosition();
        int range = (int) Math.ceil(mc.player.blockInteractionRange()) + 1;
        for (int cx = (center.getX() - range) >> 4; cx <= (center.getX() + range) >> 4; cx++) {
            for (int cz = (center.getZ() - range) >> 4; cz <= (center.getZ() + range) >> 4; cz++) {
                found.addAll(mc.level.getChunk(cx, cz).getBlockEntities().values());
            }
        }
        return found;
    }

    // Opening containers without their screen

    /**
     * Called before a screen is set, returns true to keep it from showing.
     * The menu is already assigned to the player at this point, only its screen is about to be shown.
     */
    public boolean onSetScreen(Screen newScreen) {
        if (!active || !(newScreen instanceof AbstractContainerScreen<?> screen)) return false;
        AbstractContainerMenu opened = screen.getMenu();
        if (opened.containerId == 0) return false; // the player's own inventory

        if (phase == Phase.Opening && menu == null) {
            menu = opened;
            openContainerId = opened.containerId;
            setPhase(Phase.Loading);
        } else {
            // opened too late or not by us, close it right away so no real screen ever shows up
            closeMenu(opened, false);
        }
        return true;
    }

    /**
     * Called on the network thread for every container close packet, returns true to drop it.
     * The server closing a container would also close the terminal, so it is handled here instead.
     */
    public boolean onContainerClosePacket(int containerId) {
        if (openContainerId != -1 && containerId == openContainerId) {
            serverClosedId = containerId;
            return true;
        }
        return false;
    }

    // Work loop

    /** Runs at the start of every client tick. */
    public void tick() {
        if (!active) return;
        // the terminal or its settings are always open while active, without them container screens would stay blocked
        if (mc.player == null || mc.gui.screen() == null) {
            deactivate();
            return;
        }

        if (menu != null && (serverClosedId == menu.containerId || mc.player.containerMenu != menu)) {
            // closed by the server (container broken, out of reach, ...)
            if (mc.player.containerMenu == menu) mc.player.containerMenu = mc.player.inventoryMenu;
            serverClosedId = -1;
            menu = null;
            openContainerId = -1;
            finishContainer();
            return;
        }

        if (menu == null && phase != Phase.Idle && phase != Phase.Opening) {
            // every other phase works on an open container
            finishContainer();
            return;
        }

        phaseTicks++;
        switch (phase) {
            case Idle -> {
                if (delayTicks > 0 && --delayTicks > 0) return;
                startNext();
            }
            case Opening -> {
                if (phaseTicks > config.openTimeout) {
                    failed.add(target.key());
                    finishContainer();
                }
            }
            case Loading -> {
                if (menu.getStateId() != 0) {
                    takeSnapshot();
                    if (withdrawal != null || deposit != null) {
                        setPhase(Phase.Working);
                    } else {
                        // Scans make no inventory changes, so there are no predicted clicks to settle.
                        // Contents have been applied on the main thread before this tick.
                        closeMenu(menu, false);
                        finishContainer();
                        if (delayTicks == 0) startNext();
                    }
                } else if (phaseTicks > config.openTimeout) {
                    failed.add(target.key());
                    closeMenu(menu, true);
                    finishContainer();
                }
            }
            case Working -> {
                for (int i = 0; i < config.clicksPerTick; i++) {
                    if (!(deposit != null ? depositClick() : withdrawClick())) {
                        boolean holding = withdrawal != null && withdrawal.cursorReady;
                        setPhase(holding ? Phase.Holding : Phase.Settling);
                        // the stack on the cursor has left the container, the terminal shows that right away
                        if (holding) takeSnapshot();
                        break;
                    }
                }
            }
            case Settling -> {
                // give the server a moment to correct any mispredicted clicks before reading the final contents
                if (phaseTicks >= (withdrawal != null || deposit != null ? SETTLE_TICKS : 0)) {
                    if (withdrawal != null || deposit != null) {
                        // nothing may stay on the cursor, it would block the next job
                        returnCursor(menu);
                        takeSnapshot();
                        // keep the last used container open, it's only switched when another one is needed
                        setPhase(Phase.Open);
                        version++;
                    } else {
                        takeSnapshot();
                        closeMenu(menu, true);
                        finishContainer();
                    }
                }
            }
            case Open -> {
                // the contents can change while it is open (other players, hoppers, own clicks)
                takeSnapshot();
                if (hasWork()) startNext();
            }
            case Holding -> {
                // Keep the real container open while its server-authoritative cursor is in use.
                // Opening another container would return or drop the carried stack.
                if (menu.getCarried().isEmpty()) setPhase(Phase.Settling);
                else takeSnapshot();
            }
        }
    }

    private void startNext() {
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        if (withdrawal == null) {
            withdrawal = withdrawals.poll();
            // measured when it starts, earlier withdrawals of the same item change the count
            if (withdrawal != null) withdrawal.carriedAtStart = countInInventory(withdrawal.stack);
        }

        if (withdrawal != null) {
            StorageContainer source = withdrawal.nextSource();
            if (source == null) {
                finishWithdrawal();
                return;
            }
            open(source);
            return;
        }

        if (deposit == null) deposit = deposits.poll();
        if (deposit != null) {
            StorageContainer destination = deposit.nextDestination();
            if (destination == null) {
                finishDeposit();
                return;
            }
            open(destination);
            return;
        }

        StorageContainer next = scanQueue.poll();
        if (next == null) {
            if (scanning) {
                scanning = false;
                version++;
            }
            return;
        }
        if (phase == Phase.Open && next != target && !next.key().equals(target.key()) && scanQueue.stream().anyMatch(c -> c.key().equals(target.key()))) {
            // scan the open container first instead of closing and reopening it later
            scanQueue.removeIf(c -> c.key().equals(target.key()));
            scanQueue.addFirst(next);
            next = target;
        }
        open(next);
    }

    private boolean hasWork() {
        return withdrawal != null || !withdrawals.isEmpty() || deposit != null || !deposits.isEmpty() || !scanQueue.isEmpty();
    }

    /** The container that is still open from the last withdrawal or deposit, or null. */
    private StorageContainer openContainer() {
        return phase == Phase.Open ? target : null;
    }

    private void open(StorageContainer container) {
        if (phase == Phase.Open) {
            if (container == target || container.key().equals(target.key())) {
                // already open, the snapshot is up to date
                takeSnapshot();
                if (withdrawal != null || deposit != null) setPhase(Phase.Working);
                else if (scanQueue.isEmpty() && scanning) {
                    scanning = false;
                    version++;
                }
                return;
            }
            // back to idle first, the new container may turn out to be gone or out of reach
            closeMenu(menu, true);
            finishContainer();
        }

        if (!container.isStillThere() || !inReach(container)) {
            removeContainer(container.key());
            return;
        }

        target = container;
        menu = null;
        setPhase(Phase.Opening);

        Vec3 eye = mc.player.getEyePosition();
        BlockPos pos = container.closestBlock(eye);
        Vec3 center = Vec3.atCenterOf(pos);
        Direction side = Direction.getApproximateNearest(eye.subtract(center));
        Vec3 hit = center.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        BlockHitResult hitResult = new BlockHitResult(hit, side, pos, false);

        Runnable interact = () -> {
            if (phase == Phase.Opening && target == container && mc.player != null) {
                interact(hitResult);
            }
        };

        if (config.rotate) lookAt(hit);
        interact.run();
    }

    /** Turns the player towards the container on the server only, the camera stays where it is. */
    private void lookAt(Vec3 target) {
        Vec3 delta = target.subtract(mc.player.getEyePosition());
        float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
    }

    private void interact(BlockHitResult hitResult) {
        boolean wasSneaking = mc.player.isShiftKeyDown();
        mc.player.setShiftKeyDown(false);

        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        if (config.swing && result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getInteractAnimation(), false);
        }

        mc.player.setShiftKeyDown(wasSneaking);
    }

    private void finishContainer() {
        target = null;
        menu = null;
        openContainerId = -1;
        setPhase(Phase.Idle);
        delayTicks = config.openDelay;
        version++;
    }

    private void closeMenu(AbstractContainerMenu toClose, boolean returnCursor) {
        if (mc.player == null || mc.getConnection() == null) return;
        if (returnCursor) returnCursor(toClose);

        mc.getConnection().send(new ServerboundContainerClosePacket(toClose.containerId));
        if (mc.player.containerMenu == toClose) mc.player.containerMenu = mc.player.inventoryMenu;
        if (toClose == menu) openContainerId = -1;
    }

    private void returnCursor(AbstractContainerMenu toClose) {
        // never leave an item on the cursor, the server would drop it if the inventory is full
        if (withdrawal != null && withdrawal.heldFrom >= 0 && !toClose.getCarried().isEmpty() && mc.player.containerMenu == toClose) {
            click(withdrawal.heldFrom, 0, ContainerInput.PICKUP);
            withdrawal.heldFrom = -1;
        }

        if (mc.player.containerMenu == toClose) {
            // Closing the terminal must also put a user-held stack away, including after swaps.
            // Prefer player slots, then return anything that doesn't fit to the real container.
            for (boolean playerSlots : new boolean[] {true, false}) {
                for (Slot slot : toClose.slots) {
                    ItemStack carried = toClose.getCarried();
                    if (carried.isEmpty()) break;
                    if ((slot.container instanceof Inventory) != playerSlots || !slot.mayPlace(carried)) continue;
                    ItemStack item = slot.getItem();
                    if (!item.isEmpty() && (!ItemStack.isSameItemSameComponents(item, carried) || item.getCount() >= slot.getMaxStackSize(carried))) continue;
                    mc.gameMode.handleContainerInput(toClose.containerId, slot.index, 0, ContainerInput.PICKUP, mc.player);
                }
            }
        }
    }

    private void setPhase(Phase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
    }

    // Taking items out

    /**
     * Queues taking items out of the containers.
     *
     * @param amount how many items to take, capped to what fits in the inventory
     */
    public void withdraw(Entry entry, int amount) {
        queueWithdrawal(entry, amount, false);
    }

    public void withdrawToCursor(Entry entry, int amount) {
        queueWithdrawal(entry, Math.min(amount, entry.stack.getMaxStackSize()), true);
    }

    private void queueWithdrawal(Entry entry, int amount, boolean toCursor) {
        if (mc.player == null || amount <= 0) return;
        if (!mc.player.containerMenu.getCarried().isEmpty() || phase == Phase.Holding) return;
        if (toCursor && (withdrawal != null || !withdrawals.isEmpty())) return;

        boolean wholeBox = entry.loose == 0;
        if (wholeBox && entry.inShulkers == 0) return;
        withdrawals.add(new Withdrawal(entry.stack, wholeBox ? 1 : amount, wholeBox, toCursor));
        version++;
    }

    private boolean withdrawClick() {
        Withdrawal w = withdrawal;
        if (w == null || menu == null) return false;
        if (w.toCursor) return withdrawCursorClick(w);

        // finish an exact split that is in progress first
        if (w.heldFrom >= 0) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                w.heldFrom = -1;
                return true;
            }
            if (w.remaining() > 0) {
                Slot into = inventorySlotFor(carried);
                if (into != null) {
                    click(into.index, 1, ContainerInput.PICKUP);
                    return true;
                }
            }
            click(w.heldFrom, 0, ContainerInput.PICKUP);
            w.heldFrom = -1;
            return true;
        }

        if (w.remaining() <= 0) return false;
        if (!hasRoomFor(w.wholeBox ? new ItemStack(Items.SHULKER_BOX) : w.stack)) {
            w.inventoryFull = true;
            return false;
        }

        if (w.wholeBox) {
            Slot box = bestShulkerSlot(w.stack);
            if (box == null) return false;
            ItemStack boxStack = box.getItem().copy();
            int before = countInInventory(boxStack);
            click(box.index, 0, ContainerInput.QUICK_MOVE);
            if (countInInventory(boxStack) <= before) return ++w.stalled < MAX_STALLED_CLICKS;
            w.boxesTaken++;
            return true;
        }

        Slot source = bestLooseSlot(w.stack, w.remaining());
        if (source == null) return false;

        if (source.getItem().getCount() <= w.remaining()) {
            int before = countInInventory(w.stack);
            click(source.index, 0, ContainerInput.QUICK_MOVE);
            if (countInInventory(w.stack) <= before) return ++w.stalled < MAX_STALLED_CLICKS;
            w.stalled = 0;
        } else {
            // take the whole stack on the cursor, place exactly what we need, put the rest back
            click(source.index, 0, ContainerInput.PICKUP);
            w.heldFrom = source.index;
        }
        return true;
    }

    private boolean withdrawCursorClick(Withdrawal w) {
        ItemStack carried = menu.getCarried();
        int surplus = carried.getCount() - w.amount;

        // exact split: deal the wanted amount into an empty slot, put the rest back, then pick the split up
        if (w.splitTo >= 0) {
            if (!carried.isEmpty() && menu.getSlot(w.splitTo).getItem().getCount() < w.amount) return cursorClick(w, w.splitTo, 1);
            if (!carried.isEmpty()) return cursorClick(w, w.heldFrom, 0);
            w.heldFrom = w.splitTo;
            w.splitTo = -1;
            return cursorClick(w, w.heldFrom, 0);
        }

        if (surplus > 0 && w.heldFrom >= 0) {
            Slot empty = w.amount < surplus ? emptyContainerSlot() : null;
            if (empty != null) {
                w.splitTo = empty.index;
                return cursorClick(w, w.splitTo, 1);
            }
            // return the surplus one by one, no temporary space needed
            return cursorClick(w, w.heldFrom, 1);
        }

        Slot source = null;
        if (carried.isEmpty()) source = w.wholeBox ? bestShulkerSlot(w.stack) : bestLooseSlot(w.stack, w.amount);
        else if (!w.wholeBox && surplus < 0) source = mergeSlot(w.stack, carried, -surplus);

        if (source == null) {
            // nothing here, or the cursor holds all we can get from this container
            w.cursorReady = !carried.isEmpty();
            w.cursorTaken = carried.getCount();
            w.heldFrom = -1;
            return false;
        }

        w.heldFrom = source.index;
        if (carried.isEmpty()) return cursorClick(w, source.index, 0);

        // a left click with a matching cursor deposits it, so merge into the slot and pick the whole slot up
        int before = carried.getCount();
        click(source.index, 0, ContainerInput.PICKUP);
        click(source.index, 0, ContainerInput.PICKUP);
        if (menu.getCarried().getCount() <= before) return ++w.stalled < MAX_STALLED_CLICKS;
        w.stalled = 0;
        return true;
    }

    private boolean cursorClick(Withdrawal w, int slot, int button) {
        ItemStack before = menu.getCarried().copy();
        click(slot, button, ContainerInput.PICKUP);
        if (ItemStack.matches(before, menu.getCarried())) return ++w.stalled < MAX_STALLED_CLICKS;
        w.stalled = 0;
        return true;
    }

    /** The biggest matching slot that merges completely with the cursor without exceeding the needed amount. */
    private Slot mergeSlot(ItemStack stack, ItemStack carried, int needed) {
        Slot best = null;
        for (Slot slot : containerSlots()) {
            ItemStack item = slot.getItem();
            if (!ItemStack.isSameItemSameComponents(item, stack) || !slot.mayPickup(mc.player) || !slot.mayPlace(carried)) continue;
            if (item.getCount() > needed || item.getCount() + carried.getCount() > slot.getMaxStackSize(carried)) continue;
            if (best == null || item.getCount() > best.getItem().getCount()) best = slot;
        }
        return best;
    }

    private Slot emptyContainerSlot() {
        for (Slot slot : containerSlots()) {
            if (slot.getItem().isEmpty() && slot.mayPlace(menu.getCarried())) return slot;
        }
        return null;
    }

    /** The cursor is the actual active menu's carried stack, not a visual copy of cached items. */
    public ItemStack cursorStack() {
        return mc.player != null && canClickPlayerInventory() ? mc.player.containerMenu.getCarried() : ItemStack.EMPTY;
    }

    private boolean canClickPlayerInventory() {
        return phase == Phase.Holding || ((phase == Phase.Idle || phase == Phase.Open) && withdrawal == null && withdrawals.isEmpty() && deposit == null && deposits.isEmpty());
    }

    public void clickPlayerInventory(int inventoryIndex, int button) {
        if (mc.player == null || !canClickPlayerInventory() || inventoryIndex < 0 || inventoryIndex >= 36) return;
        if (button != 0 && button != 1) return;
        AbstractContainerMenu active = mc.player.containerMenu;
        for (Slot slot : active.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                mc.gameMode.handleContainerInput(active.containerId, slot.index, button, ContainerInput.PICKUP, mc.player);
                return;
            }
        }
    }

    /** Drops the stack on the cursor like a click outside a vanilla container: button 0 drops all of it, 1 a single item. */
    public void dropCursor(int button) {
        if (mc.player == null || !canClickPlayerInventory() || (button != 0 && button != 1)) return;
        AbstractContainerMenu active = mc.player.containerMenu;
        if (active.getCarried().isEmpty()) return;
        mc.gameMode.handleContainerInput(active.containerId, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, button, ContainerInput.PICKUP, mc.player);
    }

    // Putting items in

    /** Puts the stack on the cursor into a free inventory slot and queues storing it in the nearest chest. */
    public void depositCursor() {
        if (mc.player == null || !canClickPlayerInventory()) return;
        AbstractContainerMenu active = mc.player.containerMenu;
        ItemStack carried = active.getCarried();
        if (carried.isEmpty()) return;

        Slot free = null;
        for (Slot slot : active.slots) {
            if (slot.container != mc.player.getInventory() || slot.getContainerSlot() >= 36) continue;
            if (slot.getItem().isEmpty() && slot.mayPlace(carried)) {
                free = slot;
                break;
            }
        }

        if (free == null) {
            // without a free slot the stack can't be staged, so it goes back where it can
            if (phase == Phase.Holding) {
                status = "Inventory full, put " + carried.getHoverName().getString() + " back";
                setPhase(Phase.Settling);
            } else {
                status = "No free inventory slot to store from";
            }
            version++;
            return;
        }

        ItemStack stack = carried.copy();
        mc.gameMode.handleContainerInput(active.containerId, free.index, 0, ContainerInput.PICKUP, mc.player);
        queueDeposit(free.getContainerSlot(), stack);
    }

    /** Queues storing the stack in one of the player's inventory slots (0-8 hotbar, 9-35 main) in the nearest chest. */
    public void depositSlot(int inventoryIndex) {
        if (mc.player == null || inventoryIndex < 0 || inventoryIndex >= 36) return;
        ItemStack stack = mc.player.getInventory().getItem(inventoryIndex);
        if (stack.isEmpty()) return;
        queueDeposit(inventoryIndex, stack.copy());
    }

    private void queueDeposit(int inventoryIndex, ItemStack stack) {
        if (deposit != null && deposit.slot == inventoryIndex) return;
        for (Deposit queued : deposits) {
            if (queued.slot == inventoryIndex) return;
        }
        deposits.add(new Deposit(inventoryIndex, stack));
        version++;
    }

    private boolean depositClick() {
        Deposit d = deposit;
        if (d == null || menu == null) return false;

        Slot slot = playerSlot(d.slot);
        if (slot == null || !d.matches(slot.getItem())) return false;

        int before = slot.getItem().getCount();
        click(slot.index, 0, ContainerInput.QUICK_MOVE);
        int after = d.matches(slot.getItem()) ? slot.getItem().getCount() : 0;
        if (after >= before) return ++d.stalled < MAX_STALLED_CLICKS;

        d.stalled = 0;
        d.stored += before - after;
        return after > 0;
    }

    private Slot playerSlot(int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventoryIndex) return slot;
        }
        return null;
    }

    private void finishDeposit() {
        Deposit d = deposit;
        deposit = null;
        if (d == null) return;

        String name = d.stack.getHoverName().getString();
        ItemStack left = mc.player.getInventory().getItem(d.slot);
        int remaining = d.matches(left) ? left.getCount() : 0;
        if (d.stored == 0) status = "No chest in reach has room for " + name;
        else if (remaining > 0) status = "Stored " + d.stored + "x " + name + ", no room for " + remaining;
        else status = "Stored " + d.stored + "x " + name;
        version++;
    }

    /** Only real storage: chests (also trapped and double), barrels and shulker boxes. No ender chests, furnaces or the like. */
    private boolean acceptsDeposits(StorageContainer container, ItemStack stack) {
        BlockEntityType<?> type = container.type();
        if (type == BlockEntityTypes.SHULKER_BOX) return !isShulkerBox(stack);
        return type == BlockEntityTypes.BARREL || mc.level.getBlockState(container.key()).getBlock() instanceof ChestBlock;
    }

    private static boolean hasRoomFor(List<ItemStack> items, ItemStack stack) {
        for (ItemStack item : items) {
            if (item.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(item, stack) && item.getCount() < item.getMaxStackSize()) return true;
        }
        return false;
    }

    private void finishWithdrawal() {
        Withdrawal w = withdrawal;
        withdrawal = null;
        if (w == null) return;

        String name = w.stack.getHoverName().getString();
        if (w.toCursor) {
            status = w.cursorTaken > 0 ? "Picked up " + w.cursorTaken + "x " + (w.wholeBox ? "shulker box" : name) : "Couldn't pick up " + name;
        } else if (w.wholeBox) {
            status = w.boxesTaken > 0 ? "Took a shulker box with " + name : "Couldn't take a shulker box with " + name;
        } else {
            int taken = w.amount - Math.max(0, w.remaining());
            if (w.inventoryFull && taken < w.amount) status = "Inventory full, took " + taken + "x " + name;
            else if (taken < w.amount) status = "Only found " + taken + "x " + name;
            else status = "Took " + taken + "x " + name;
        }
        version++;
    }

    private Slot bestLooseSlot(ItemStack stack, int remaining) {
        Slot best = null;
        for (Slot slot : containerSlots()) {
            ItemStack item = slot.getItem();
            if (!ItemStack.isSameItemSameComponents(item, stack) || !slot.mayPickup(mc.player)) continue;
            if (best == null) {
                best = slot;
                continue;
            }
            int count = item.getCount(), bestCount = best.getItem().getCount();
            // prefer the biggest stack that we can take completely, otherwise the smallest one to split
            boolean fits = count <= remaining, bestFits = bestCount <= remaining;
            if (fits != bestFits ? fits : (fits ? count > bestCount : count < bestCount)) best = slot;
        }
        return best;
    }

    private Slot bestShulkerSlot(ItemStack stack) {
        Slot best = null;
        int bestCount = 0;
        for (Slot slot : containerSlots()) {
            if (!slot.mayPickup(mc.player)) continue;
            int count = countInShulker(slot.getItem(), stack);
            if (count > bestCount) {
                best = slot;
                bestCount = count;
            }
        }
        return best;
    }

    private Slot inventorySlotFor(ItemStack stack) {
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || !slot.mayPlace(stack)) continue;
            ItemStack item = slot.getItem();
            if (item.isEmpty()) {
                if (empty == null) empty = slot;
            } else if (ItemStack.isSameItemSameComponents(item, stack) && item.getCount() < slot.getMaxStackSize(item)) {
                return slot;
            }
        }
        return empty;
    }

    private List<Slot> containerSlots() {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) slots.add(slot);
        }
        return slots;
    }

    private void click(int slot, int button, ContainerInput input) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
    }

    private int countInInventory(ItemStack stack) {
        int count = 0;
        for (ItemStack item : mc.player.getInventory().getNonEquipmentItems()) {
            if (ItemStack.isSameItemSameComponents(item, stack)) count += item.getCount();
        }
        return count;
    }

    private boolean hasRoomFor(ItemStack stack) {
        for (ItemStack item : mc.player.getInventory().getNonEquipmentItems()) {
            if (item.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(item, stack) && item.getCount() < item.getMaxStackSize()) return true;
        }
        return false;
    }

    // Cache

    /**
     * Caches what the open container holds right now, as the client sees it. That includes predicted clicks, which
     * don't change the state id, so the contents themselves are compared and the terminal is only rebuilt on a change.
     */
    private void takeSnapshot() {
        if (menu == null || target == null) return;
        failed.remove(target.key());

        List<Slot> slots = containerSlots();
        List<ItemStack> cached = snapshots.get(target.key());
        if (cached != null && cached.size() == slots.size()) {
            boolean changed = false;
            for (int i = 0; i < slots.size() && !changed; i++) changed = !ItemStack.matches(cached.get(i), slots.get(i).getItem());
            if (!changed) return;
        }

        List<ItemStack> items = new ArrayList<>(slots.size());
        for (Slot slot : slots) items.add(slot.getItem().copy());
        snapshots.put(target.key(), items);
        rebuildEntries();
    }

    private void removeContainer(BlockPos key) {
        containers.remove(key);
        if (snapshots.remove(key) != null) rebuildEntries();
        version++;
    }

    void rebuildEntries() {
        Map<ItemKey, Entry> byItem = new HashMap<>();
        long total = 0;

        for (Map.Entry<BlockPos, List<ItemStack>> snapshot : snapshots.entrySet()) {
            for (ItemStack item : snapshot.getValue()) {
                if (item.isEmpty()) continue;
                total += item.getCount();

                Entry entry = byItem.computeIfAbsent(new ItemKey(item), k -> new Entry(item.copyWithCount(1)));
                entry.loose += item.getCount();
                entry.containers.add(snapshot.getKey());

                if (!config.showShulkerContents || !isShulkerBox(item)) continue;
                ItemContainerContents contents = item.get(DataComponents.CONTAINER);
                if (contents == null) continue;
                contents.nonEmptyItemCopyStream().forEach(inner -> {
                    Entry innerEntry = byItem.computeIfAbsent(new ItemKey(inner), k -> new Entry(inner.copyWithCount(1)));
                    innerEntry.inShulkers += inner.getCount();
                    innerEntry.containers.add(snapshot.getKey());
                });
            }
        }

        List<Entry> list = new ArrayList<>(byItem.values());
        Comparator<Entry> byName = Comparator.comparing(e -> e.stack.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER);
        list.sort(switch (config.sort) {
            case COUNT -> Comparator.comparingLong(Entry::total).reversed().thenComparing(byName);
            case NAME -> byName;
            case MOD -> Comparator.<Entry, String>comparing(e -> BuiltInRegistries.ITEM.getKey(e.stack.getItem()).getNamespace()).thenComparing(byName);
        });

        entries = list;
        totalItems = total;
        version++;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static int countInShulker(ItemStack box, ItemStack stack) {
        if (!isShulkerBox(box)) return 0;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) return 0;
        return contents.nonEmptyItemCopyStream()
            .filter(inner -> ItemStack.isSameItemSameComponents(inner, stack))
            .mapToInt(ItemStack::getCount)
            .sum();
    }

    // Containers

    private boolean inReach(BlockPos pos) {
        return mc.player.isWithinBlockInteractionRange(pos, 0);
    }

    private boolean inReach(StorageContainer container) {
        return container.blocks().stream().anyMatch(this::inReach);
    }

    private static StorageContainer containerAt(BlockPos pos, BlockState state, BlockEntityType<?> type) {
        pos = pos.immutable();
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = ChestBlock.getConnectedBlockPos(pos, state).immutable();
            return new StorageContainer(pos.compareTo(other) < 0 ? pos : other, List.of(pos, other), type);
        }
        return new StorageContainer(pos, List.of(pos), type);
    }

    // Accessors for the terminal

    public List<Entry> entries() {
        return entries;
    }

    /** Changes whenever the entries or the status change. */
    public int version() {
        return version;
    }

    public long totalItems() {
        return totalItems;
    }

    public int containerCount() {
        return snapshots.size();
    }

    /** What the searcher is doing right now, or the result of the last withdrawal. */
    public String status() {
        if (deposit != null) {
            int queued = deposits.size();
            return "Storing " + deposit.stack.getHoverName().getString() + "..." + (queued > 0 ? " (" + queued + " more queued)" : "");
        }
        if (withdrawal != null) {
            int queued = withdrawals.size();
            return "Taking " + withdrawal.stack.getHoverName().getString() + "..." + (queued > 0 ? " (" + queued + " more queued)" : "");
        }
        if (scanning) {
            int done = containers.size() - scanQueue.size() - (target != null ? 1 : 0);
            return "Scanning " + Math.max(0, done) + "/" + containers.size() + "...";
        }
        if (!failed.isEmpty() && status.isEmpty()) return failed.size() + " container(s) couldn't be opened";
        return status;
    }

    public String summary() {
        String summary = containerCount() + " containers · " + String.format("%,d", totalItems) + " items";
        return excludedInReach > 0 ? summary + " · " + excludedInReach + " excluded" : summary;
    }

    // Excluding containers

    private Exclusions exclusions() {
        return config.exclusions();
    }

    private void saveExclusions() {
        config.save();
    }

    /** Excludes the container the player looks at from searching, or includes it again. */
    public void toggleExclusionAtCrosshair() {
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            mc.player.sendOverlayMessage(Component.literal("Look at a container to exclude it from the Inventory Searcher"));
            return;
        }

        BlockPos pos = hit.getBlockPos();
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container) && !(blockEntity instanceof EnderChestBlockEntity)) {
            mc.player.sendOverlayMessage(Component.literal("That's not a container"));
            return;
        }

        StorageContainer container = containerAt(pos, blockEntity.getBlockState(), blockEntity.getType());
        boolean excluded = exclusions().toggle(container.key());
        saveExclusions();

        String name = blockEntity.getBlockState().getBlock().getName().getString();
        mc.player.sendOverlayMessage(Component.literal((excluded ? "Excluded " : "Included ") + name + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
            .withStyle(excluded ? ChatFormatting.RED : ChatFormatting.GREEN));
    }

    public boolean isScanning() {
        return scanning;
    }

    // Types

    private enum Phase {
        Idle,
        Opening,
        Loading,
        Working,
        Holding,
        Settling,
        /** Idle, but the last used container is kept open. */
        Open
    }

    /** One item type in the terminal, summed up over all containers. */
    public static final class Entry {
        public final ItemStack stack;
        public long loose;
        public long inShulkers;
        public final Set<BlockPos> containers = new HashSet<>();

        private Entry(ItemStack stack) {
            this.stack = stack;
        }

        public long total() {
            return loose + inShulkers;
        }
    }

    private record ItemKey(ItemStack stack) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ItemKey other && ItemStack.isSameItemSameComponents(stack, other.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    /** A container, a double chest counts as one with the smaller position as key. */
    private record StorageContainer(BlockPos key, List<BlockPos> blocks, BlockEntityType<?> type) {
        BlockPos closestBlock(Vec3 eye) {
            return blocks.stream().min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye))).orElse(key);
        }

        double distanceTo(Vec3 eye) {
            return Vec3.atCenterOf(closestBlock(eye)).distanceToSqr(eye);
        }

        boolean isStillThere() {
            for (BlockPos pos : blocks) {
                BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(pos);
                if (blockEntity == null || blockEntity.getType() != type) return false;
            }
            return true;
        }
    }

    private final class Withdrawal {
        final ItemStack stack;
        final int amount;
        final boolean wholeBox;
        final boolean toCursor;
        boolean cursorReady;
        int cursorTaken;
        int carriedAtStart;
        final Set<BlockPos> tried = new HashSet<>();
        int heldFrom = -1;
        int splitTo = -1;
        int stalled;
        int boxesTaken;
        boolean inventoryFull;

        Withdrawal(ItemStack stack, int amount, boolean wholeBox, boolean toCursor) {
            this.stack = stack;
            this.amount = amount;
            this.wholeBox = wholeBox;
            this.toCursor = toCursor;
        }

        int remaining() {
            if (toCursor) return amount - (menu == null ? 0 : menu.getCarried().getCount());
            if (wholeBox) return amount - boxesTaken;
            return amount - (countInInventory(stack) - carriedAtStart);
        }

        /** The container with the most of the item that we haven't tried yet, or null when done. */
        StorageContainer nextSource() {
            if (cursorReady) return null;
            if (remaining() <= 0 || inventoryFull) return null;

            // stay with the open container while it still has some
            StorageContainer open = openContainer();
            if (open != null && !tried.contains(open.key()) && countIn(snapshots.get(open.key())) > 0) {
                tried.add(open.key());
                stalled = 0;
                return open;
            }

            StorageContainer best = null;
            int bestCount = 0;
            for (Map.Entry<BlockPos, List<ItemStack>> snapshot : snapshots.entrySet()) {
                if (tried.contains(snapshot.getKey()) || failed.contains(snapshot.getKey())) continue;
                StorageContainer container = containers.get(snapshot.getKey());
                if (container == null) continue;

                int count = countIn(snapshot.getValue());
                if (count > bestCount) {
                    best = container;
                    bestCount = count;
                }
            }

            if (best != null) {
                tried.add(best.key());
                stalled = 0;
            }
            return best;
        }

        int countIn(List<ItemStack> items) {
            if (items == null) return 0;
            int count = 0;
            for (ItemStack item : items) {
                count += wholeBox ? countInShulker(item, stack) : (ItemStack.isSameItemSameComponents(item, stack) ? item.getCount() : 0);
            }
            return count;
        }
    }

    private final class Deposit {
        /** The player inventory slot the stack is stored from. */
        final int slot;
        final ItemStack stack;
        final Set<BlockPos> tried = new HashSet<>();
        int stored;
        int stalled;

        Deposit(int slot, ItemStack stack) {
            this.slot = slot;
            this.stack = stack;
        }

        boolean matches(ItemStack item) {
            return !item.isEmpty() && ItemStack.isSameItemSameComponents(item, stack);
        }

        /** The nearest chest that may have room for the stack, or null when done. */
        StorageContainer nextDestination() {
            if (!matches(mc.player.getInventory().getItem(slot))) return null;

            Vec3 eye = mc.player.getEyePosition();
            StorageContainer open = openContainer();
            StorageContainer best = null;
            for (StorageContainer container : containers.values()) {
                if (tried.contains(container.key()) || failed.contains(container.key()) || !acceptsDeposits(container, stack)) continue;
                // unscanned containers are opened anyway, their snapshot is taken on the way
                List<ItemStack> items = snapshots.get(container.key());
                if (items != null && !hasRoomFor(items, stack)) continue;
                // stay with the open container while it has room
                if (open != null && container.key().equals(open.key())) {
                    best = container;
                    break;
                }
                if (best == null || container.distanceTo(eye) < best.distanceTo(eye)) best = container;
            }

            if (best != null) {
                tried.add(best.key());
                stalled = 0;
            }
            return best;
        }
    }
}
