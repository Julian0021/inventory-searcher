package io.github.julian0021.inventorysearcher;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Works out what a recipe in the recipe book still needs from the containers. */
final class RecipeMaterials {
    private RecipeMaterials() {
    }

    /**
     * The items to take out of the containers so the recipe can be placed, the ingredients minus what the inventory and
     * the crafting grid already hold. Null when the stored items don't make the recipe craftable either.
     *
     * @param useMax as many crafts as fit, like a shift click in the recipe book
     */
    static Map<Holder<Item>, Integer> missing(List<Ingredient> ingredients, boolean useMax, List<ItemStack> owned, List<InventorySearcher.Entry> stored) {
        StackedContents<Holder<Item>> available = new StackedContents<>();
        Map<Holder<Item>, Integer> ownedCounts = new HashMap<>();
        for (ItemStack stack : owned) {
            // the same items the recipe book and the server use for crafting
            if (stack.isEmpty() || !Inventory.isUsableForCrafting(stack)) continue;
            available.account(stack.typeHolder(), stack.getCount());
            ownedCounts.merge(stack.typeHolder(), stack.getCount(), Integer::sum);
        }
        for (InventorySearcher.Entry entry : stored) {
            if (entry.loose <= 0 || !Inventory.isUsableForCrafting(entry.stack)) continue;
            available.account(entry.stack.typeHolder(), (int) Math.min(entry.loose, Integer.MAX_VALUE));
        }

        // the picker reports the chosen item once per ingredient, for every craft at once
        Map<Holder<Item>, Integer> perCraft = new HashMap<>();
        if (!available.tryPick(ingredients, 1, item -> perCraft.merge(item, 1, Integer::sum))) return null;

        int crafts = 1;
        if (useMax) {
            // every grid slot holds one stack at most, like the server places it
            int limit = perCraft.keySet().stream().mapToInt(item -> item.value().getDefaultMaxStackSize()).min().orElse(1);
            perCraft.clear();
            crafts = available.tryPickAll(ingredients, limit, item -> perCraft.merge(item, 1, Integer::sum));
            if (crafts <= 0) return null;
        }

        Map<Holder<Item>, Integer> missing = new HashMap<>();
        for (Map.Entry<Holder<Item>, Integer> needed : perCraft.entrySet()) {
            int amount = needed.getValue() * crafts - ownedCounts.getOrDefault(needed.getKey(), 0);
            if (amount > 0) missing.put(needed.getKey(), amount);
        }
        return missing;
    }
}
