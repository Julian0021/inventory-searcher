package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.CraftingPanel;
import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Input and lifecycle for the panel next to a crafting table, the methods container screens don't pass to widgets. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$scrollPanel(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel != null && panel.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) cir.setReturnValue(true);
    }

    // a release after a press on the panel belongs to it, the screen would treat it as a click on its window
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$releaseOnPanel(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        CraftingPanel panel = CraftingPanel.of(this);
        if (panel != null && panel.handlesRelease()) cir.setReturnValue(panel.mouseReleased(event));
    }

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$onClose(CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null && InventorySearcherMod.searcher().onContainerScreenClose((Screen) (Object) this)) ci.cancel();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void inventorySearcher$onRemoved(CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null) InventorySearcherMod.searcher().onScreenRemoved((Screen) (Object) this);
    }
}
