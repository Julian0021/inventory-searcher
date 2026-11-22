package io.github.julian0021.inventorysearcher;

import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The settings in a vanilla options screen. */
public class SettingsScreen extends OptionsSubScreen {
    private final SearcherConfig config = InventorySearcherMod.config();

    public SettingsScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable("inventory-searcher.settings"));
    }

    @Override
    protected void addOptions() {
        list.addHeader(Component.translatable("inventory-searcher.settings.containers"));
        List<OptionInstance<?>> containers = new ArrayList<>();
        for (BlockEntityType<?> type : SearcherConfig.SELECTABLE_CONTAINERS) {
            String blockKey = BuiltInRegistries.BLOCK.getValue(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)).getDescriptionId();
            containers.add(OptionInstance.createBoolean(blockKey, config.isSearched(type), value -> config.setSearched(type, value)));
        }
        list.addSmall(containers.toArray(OptionInstance[]::new));

        list.addHeader(Component.translatable("inventory-searcher.settings.excluded"));
        Button clearWorld = Button.builder(clearWorldText(), button -> {
            config.exclusions().clearWorld();
            config.save();
            rebuildWidgets();
        }).tooltip(Tooltip.create(Component.translatable("inventory-searcher.settings.excluded.tooltip"))).build();
        clearWorld.active = minecraft.level != null && config.exclusions().countInWorld() > 0;
        Button clearAll = Button.builder(Component.translatable("inventory-searcher.settings.excluded.clear_all", config.exclusions().countAll()), button -> {
            config.exclusions().clearAll();
            config.save();
            rebuildWidgets();
        }).build();
        clearAll.active = config.exclusions().countAll() > 0;
        list.addSmall(clearWorld, clearAll);

        list.addHeader(Component.translatable("inventory-searcher.settings.general"));
        list.addSmall(
            bool("rotate", config.rotate, value -> config.rotate = value),
            bool("swing", config.swing, value -> config.swing = value)
        );
        list.addBig(Button.builder(Component.translatable("inventory-searcher.settings.keybind"), button -> minecraft.gui.setScreen(new KeyBindsScreen(this, options)))
            .tooltip(Tooltip.create(Component.translatable("inventory-searcher.settings.keybind.tooltip")))
            .build());

        list.addHeader(Component.translatable("inventory-searcher.settings.timing"));
        list.addSmall(
            slider("open_delay", 0, 20, config.openDelay, value -> config.openDelay = value),
            slider("open_timeout", 5, 100, config.openTimeout, value -> config.openTimeout = value),
            slider("clicks_per_tick", 1, 10, config.clicksPerTick, value -> config.clicksPerTick = value)
        );

        list.addHeader(Component.translatable("inventory-searcher.settings.display"));
        list.addSmall(
            bool("show_shulker_contents", config.showShulkerContents, value -> {
                config.showShulkerContents = value;
                refreshEntries();
            }),
            new OptionInstance<>(
                "inventory-searcher.settings.sort",
                tooltip("sort"),
                (caption, value) -> Component.literal(value.title),
                new OptionInstance.Enum<>(Arrays.asList(SearcherConfig.SortMode.values()), Codec.STRING.xmap(SearcherConfig.SortMode::valueOf, Enum::name)),
                config.sort,
                value -> {
                    config.sort = value;
                    refreshEntries();
                }
            )
        );
    }

    private Component clearWorldText() {
        if (minecraft.level == null) return Component.translatable("inventory-searcher.settings.excluded.clear_world_none");
        return Component.translatable("inventory-searcher.settings.excluded.clear_world", config.exclusions().countInWorld());
    }

    @Override
    public void removed() {
        super.removed();
        config.save();
    }

    private static void refreshEntries() {
        if (InventorySearcherMod.searcher().isActive()) InventorySearcherMod.searcher().rebuildEntries();
    }

    private static OptionInstance<Boolean> bool(String name, boolean value, OptionInstance.ValueUpdateListener<Boolean> onChange) {
        return OptionInstance.createBoolean("inventory-searcher.settings." + name, tooltip(name), value, onChange);
    }

    private static OptionInstance<Integer> slider(String name, int min, int max, int value, OptionInstance.ValueUpdateListener<Integer> onChange) {
        return new OptionInstance<>(
            "inventory-searcher.settings." + name,
            tooltip(name),
            Options::genericValueLabel,
            new OptionInstance.IntRange(min, max),
            value,
            onChange
        );
    }

    private static <T> OptionInstance.TooltipSupplier<T> tooltip(String name) {
        return OptionInstance.cachedConstantTooltip(Component.translatable("inventory-searcher.settings." + name + ".tooltip"));
    }
}
