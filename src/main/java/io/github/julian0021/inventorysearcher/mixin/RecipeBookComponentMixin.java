package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.CraftingPanel;
import io.github.julian0021.inventorysearcher.InventorySearcher;
import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With the panel next to a crafting table open, the recipe book counts the stored items and fetches them for a recipe. */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
    @Shadow @Final protected RecipeBookMenu menu;
    @Shadow @Final private StackedItemContents stackedContents;
    @Shadow private boolean widthTooNarrow;
    @Shadow private int width;
    @Shadow private int xOffset;
    @Shadow private EditBox searchBox;
    @Shadow private String lastSearch;
    @Shadow private RecipeBookTabButton selectedTab;
    @Shadow @Final private RecipeBookPage recipeBookPage;

    /** The crafting window's width, known once the screen asked for its position. */
    @Unique private int inventorySearcher$imageWidth = 176;

    @Shadow
    public abstract boolean isVisible();


    // The window moves left together with the recipe book when the panel needs the room. The recipe book places its parts
    // before the screen asks for the window's position, so both work it out from the same state.
    @Inject(method = "updateScreenPosition", at = @At("RETURN"), cancellable = true)
    private void inventorySearcher$makeRoomForPanel(int width, int imageWidth, CallbackInfoReturnable<Integer> cir) {
        inventorySearcher$imageWidth = imageWidth;
        cir.setReturnValue(cir.getReturnValue() - inventorySearcher$shift(isVisible()));
    }

    // The panel opens the crafting table again for every recipe and every item it takes, each time with a new recipe book.
    // Before the new one builds its parts, it takes over the search, tab and page of the old one, which keeps them the
    // same way when the window is resized.
    @Inject(method = "initVisuals", at = @At("HEAD"))
    private void inventorySearcher$keepSearch(CallbackInfo ci) {
        InventorySearcher searcher = InventorySearcherMod.searcher();
        if (searchBox != null || searcher == null) return;
        RecipeBookComponentAccessor previous = (RecipeBookComponentAccessor) searcher.takePreviousRecipeBook(menu);
        if (previous == null) return;
        searchBox = previous.inventorySearcher$searchBox();
        lastSearch = previous.inventorySearcher$lastSearch();
        selectedTab = previous.inventorySearcher$selectedTab();
        int page = ((RecipeBookPageAccessor) previous.inventorySearcher$recipeBookPage()).inventorySearcher$currentPage();
        ((RecipeBookPageAccessor) recipeBookPage).inventorySearcher$setCurrentPage(page);
    }

    // the recipe book, its tabs and everything else on it are placed from this offset
    @Inject(method = "initVisuals", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;xOffset:I", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void inventorySearcher$moveWithWindow(CallbackInfo ci) {
        // only placed while it's shown, it may not count as visible yet
        xOffset += inventorySearcher$shift(true);
    }

    @Unique
    private int inventorySearcher$shift(boolean visible) {
        InventorySearcher searcher = InventorySearcherMod.searcher();
        if (searcher == null || !searcher.hasCraftingPanel(menu)) return 0;
        return CraftingPanel.shift(width, inventorySearcher$imageWidth, visible, widthTooNarrow);
    }

    // both places the recipe book counts what's there, when it opens and when the inventory changes
    @Inject(method = {"updateStackedContents", "initVisuals"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/RecipeBookMenu;fillCraftSlotsStackedContents(Lnet/minecraft/world/entity/player/StackedItemContents;)V", shift = At.Shift.AFTER))
    private void inventorySearcher$countStoredItems(CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null) InventorySearcherMod.searcher().accountStoredItems(menu, stackedContents);
    }

    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$placeWithStoredItems(RecipeCollection collection, RecipeDisplayId id, boolean useMax, CallbackInfoReturnable<Boolean> cir) {
        if (InventorySearcherMod.searcher() != null && InventorySearcherMod.searcher().onPlaceRecipe(menu, collection, id, useMax)) cir.setReturnValue(true);
    }
}
