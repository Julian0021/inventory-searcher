package io.github.julian0021.inventorysearcher;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Needs only Fabric Loader, the tick and the key are hooked in with mixins. */
public class InventorySearcherMod implements ClientModInitializer {
    public static final String MOD_ID = "inventory-searcher";

    private static final KeyMapping OPEN_KEY = new KeyMapping(
        "key.inventory-searcher.open",
        InputConstants.Type.MOUSE,
        InputConstants.MOUSE_BUTTON_4,
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
    );

    private static SearcherConfig config;
    private static InventorySearcher searcher;

    @Override
    public void onInitializeClient() {
        config = SearcherConfig.load();
        searcher = new InventorySearcher(config);
    }

    /** Called at the start of every client tick. */
    public static void tick(Minecraft client) {
        if (searcher == null) return;
        // the key only fires without a screen, the terminal closes itself on it
        while (OPEN_KEY.consumeClick()) {
            if (client.gui.screen() == null) searcher.activate();
        }
        // also closes the terminal after leaving the world
        searcher.tick();
    }

    public static SearcherConfig config() {
        return config;
    }

    public static InventorySearcher searcher() {
        return searcher;
    }

    public static KeyMapping openKey() {
        return OPEN_KEY;
    }
}
