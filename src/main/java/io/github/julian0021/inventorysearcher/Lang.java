package io.github.julian0021.inventorysearcher;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The mod's texts in the game's language, from the lang files. */
final class Lang {
    private static final String PREFIX = "inventory-searcher.";

    private Lang() {
    }

    /** For texts kept as strings, like the status line: they are translated once, when they are made. */
    static String get(String key, Object... args) {
        return I18n.get(PREFIX + key, args);
    }

    static MutableComponent text(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }
}
