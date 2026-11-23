package io.github.julian0021.inventorysearcher.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
    /** Counts the inventory, the crafting grid and with the panel open the stored items again. */
    @Invoker("updateStackedContents")
    void inventorySearcher$updateStackedContents();
}
