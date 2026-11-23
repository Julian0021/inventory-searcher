package io.github.julian0021.inventorysearcher.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {
    @Accessor("currentPage")
    int inventorySearcher$currentPage();

    @Accessor("currentPage")
    void inventorySearcher$setCurrentPage(int page);
}
