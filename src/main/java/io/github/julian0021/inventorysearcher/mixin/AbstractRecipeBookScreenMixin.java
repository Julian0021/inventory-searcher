package io.github.julian0021.inventorysearcher.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.julian0021.inventorysearcher.CraftingPanel;
import io.github.julian0021.inventorysearcher.InventorySearcher;
import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Input for the panel next to a crafting table, the methods the recipe book screens override. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenMixin {
    // with the panel open, the recipe book lies over the window when there's no room beside it, like on small screens
    @ModifyConstant(method = "init", constant = @Constant(intValue = 379))
    private int inventorySearcher$recipeBookOverWindow(int narrowerThan) {
        InventorySearcher searcher = InventorySearcherMod.searcher();
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (searcher == null || !(screen.getMenu() instanceof RecipeBookMenu menu) || !searcher.hasCraftingPanel(menu)) return narrowerThan;
        return Math.max(narrowerThan, CraftingPanel.minWidthBesideRecipeBook(((AbstractContainerScreenAccessor) screen).inventorySearcher$imageWidth()));
    }

    // a recipe book lying over the window is drawn without the window's widgets, the panel beside it still shows
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", shift = At.Shift.AFTER))
    private void inventorySearcher$drawPanelBesideRecipeBook(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel != null) panel.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    // the panel lies outside the window, a click on it must not drop the held stack
    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$panelIsInside(double mouseX, double mouseY, int left, int top, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel != null && panel.isMouseOver(mouseX, mouseY)) cir.setReturnValue(false);
    }

    // first, while the recipe book lies over a narrow window the game takes every click outside of it
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$clickPanel(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel == null) return;
        if (!panel.isMouseOver(event.x(), event.y())) panel.unfocusSearch();
        else if (panel.mouseClicked(event, doubled)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$dragScrollbar(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel != null && panel.isDraggingScrollbar()) cir.setReturnValue(panel.mouseDragged(event, dx, dy));
    }

    // while the search field has the keyboard, keys like the inventory key only type
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$typeInSearch(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel == null || !panel.capturesKeyboard() || event.key() == InputConstants.KEY_ESCAPE) return;
        panel.keyPressed(event);
        cir.setReturnValue(true);
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$typeCharInSearch(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel == null || !panel.capturesKeyboard()) return;
        panel.charTyped(event);
        cir.setReturnValue(true);
    }

    // while the crafting table is closed for the panel, its slots can't be used
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$waitForTable(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null && InventorySearcherMod.searcher().blocksCraftingInput(this)) ci.cancel();
    }
}
