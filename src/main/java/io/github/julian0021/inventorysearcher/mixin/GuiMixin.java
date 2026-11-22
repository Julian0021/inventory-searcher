package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$onSetScreen(Screen screen, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null && InventorySearcherMod.searcher().onSetScreen(screen)) ci.cancel();
    }
}
