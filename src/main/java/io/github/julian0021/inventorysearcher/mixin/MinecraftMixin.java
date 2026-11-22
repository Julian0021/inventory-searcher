package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void inventorySearcher$onTick(CallbackInfo ci) {
        InventorySearcherMod.tick((Minecraft) (Object) this);
    }
}
