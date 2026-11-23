package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.CraftingPanel;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingScreen.class)
public abstract class CraftingScreenMixin extends AbstractRecipeBookScreen<CraftingMenu> {
    private CraftingScreenMixin(CraftingMenu menu, RecipeBookComponent<?> recipeBook, Inventory inventory, Component title) {
        super(menu, recipeBook, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void inventorySearcher$addPanel(CallbackInfo ci) {
        addRenderableWidget(new CraftingPanel((CraftingScreen) (Object) this, () -> leftPos, () -> topPos, imageWidth, imageHeight));
    }
}
