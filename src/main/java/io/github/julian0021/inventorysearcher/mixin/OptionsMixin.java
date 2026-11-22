package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

@Mixin(Options.class)
public abstract class OptionsMixin {
    @Mutable
    @Shadow
    @Final
    public KeyMapping[] keyMappings;

    // before options.txt is read, so the saved binding gets applied and the key shows up in the controls
    @Inject(method = "load", at = @At("HEAD"))
    private void inventorySearcher$addKeyMappings(CallbackInfo ci) {
        for (KeyMapping key : new KeyMapping[] {InventorySearcherMod.openKey(), InventorySearcherMod.excludeKey()}) {
            if (Arrays.asList(keyMappings).contains(key)) continue;
            keyMappings = Arrays.copyOf(keyMappings, keyMappings.length + 1);
            keyMappings[keyMappings.length - 1] = key;
        }
    }
}
