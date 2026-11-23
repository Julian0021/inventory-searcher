package io.github.julian0021.inventorysearcher.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
    /** Counts the inventory, the crafting grid and with the panel open the stored items again. */
    @Invoker("updateStackedContents")
    void inventorySearcher$updateStackedContents();

    /** Null until the recipe book was shown once. */
    @Accessor("searchBox")
    EditBox inventorySearcher$searchBox();

    @Accessor("lastSearch")
    String inventorySearcher$lastSearch();

    @Accessor("selectedTab")
    RecipeBookTabButton inventorySearcher$selectedTab();

    @Accessor("recipeBookPage")
    RecipeBookPage inventorySearcher$recipeBookPage();
}
