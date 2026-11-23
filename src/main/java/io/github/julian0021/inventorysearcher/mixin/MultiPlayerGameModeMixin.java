package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    // the client doesn't know which block a crafting menu belongs to, so the used crafting table is remembered here
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void inventorySearcher$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (InventorySearcherMod.searcher() != null) InventorySearcherMod.searcher().onUseItemOn(hit);
    }
}
