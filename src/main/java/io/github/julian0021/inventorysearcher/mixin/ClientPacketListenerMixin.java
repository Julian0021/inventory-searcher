package io.github.julian0021.inventorysearcher.mixin;

import io.github.julian0021.inventorysearcher.InventorySearcherMod;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    /** After this call a handler runs on the main thread, before it the call hands the packet over and leaves. */
    private static final String ON_MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    // HEAD runs on the network thread, before the packet gets queued for the main thread
    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void inventorySearcher$onContainerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null && InventorySearcherMod.searcher().onContainerClosePacket(packet.getContainerId())) ci.cancel();
    }

    @Inject(method = "handleOpenScreen", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
    private void inventorySearcher$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null) InventorySearcherMod.searcher().onServerOpenedContainer();
    }

    @Inject(method = "handleContainerSetSlot", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
    private void inventorySearcher$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null) InventorySearcherMod.searcher().onSlotUpdate(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At(value = "INVOKE", target = ON_MAIN_THREAD, shift = At.Shift.AFTER))
    private void inventorySearcher$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (InventorySearcherMod.searcher() != null) InventorySearcherMod.searcher().onContentUpdate(packet.containerId(), packet.items());
    }
}
