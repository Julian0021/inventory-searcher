package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    // HEAD runs on the network thread, before the packet gets queued for the main thread
    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$onContainerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null && InventorySearcherMod.searcher().onContainerClosePacket(packet.getContainerId())) ci.cancel();
    }
}
