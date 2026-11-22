package io.github.julian0021.inventorysearcher;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class InventorySearcherMod implements ClientModInitializer {
    public static final String MOD_ID = "inventory-searcher";

    private static SearcherConfig config;
    private static InventorySearcher searcher;
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        config = SearcherConfig.load();
        searcher = new InventorySearcher(config);

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.inventory-searcher.open", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_4, category));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            // the key only fires without a screen, the terminal closes itself on it
            while (openKey.consumeClick()) {
                if (client.gui.screen() == null) searcher.activate();
            }
            searcher.tick();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(searcher::deactivate));
    }

    public static SearcherConfig config() {
        return config;
    }

    public static InventorySearcher searcher() {
        return searcher;
    }

    public static KeyMapping openKey() {
        return openKey;
    }
}
