package io.github.julian0021.inventorysearcher;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The settings in a vanilla options screen. */
public class SettingsScreen extends OptionsSubScreen {
    private final SearcherConfig config = InventorySearcherMod.config();
    private Button clearWorld;
    private Button clearAll;
    private Button excludeKeyButton;
    private boolean listeningForKey;

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
        // the layout keeps its contents, so the buttons are updated in place instead of rebuilding the screen
        clearWorld = Button.builder(Component.empty(), button -> {
            config.exclusions().clearWorld();
            config.save();
            updateExclusionButtons();
        }).tooltip(Tooltip.create(Component.translatable("inventory-searcher.settings.excluded.tooltip"))).build();
        clearAll = Button.builder(Component.empty(), button -> {
            config.exclusions().clearAll();
            config.save();
            updateExclusionButtons();
        }).build();
        updateExclusionButtons();
        list.addSmall(clearWorld, clearAll);
        excludeKeyButton = Button.builder(Component.empty(), button -> {
            listeningForKey = true;
            updateExcludeKeyButton();
        }).tooltip(Tooltip.create(Component.translatable("inventory-searcher.settings.exclude_key.tooltip"))).build();
        updateExcludeKeyButton();
        list.addBig(excludeKeyButton);

        list.addHeader(Component.translatable("inventory-searcher.settings.general"));
        list.addSmall(
            bool("rotate", config.rotate, value -> config.rotate = value),
            bool("swing", config.swing, value -> config.swing = value),
            bool("extra_reach", config.extraReach, value -> config.extraReach = value)
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

    private void updateExclusionButtons() {
        boolean inWorld = minecraft.level != null;
        clearWorld.setMessage(inWorld
            ? Component.translatable("inventory-searcher.settings.excluded.clear_world", config.exclusions().countInWorld())
            : Component.translatable("inventory-searcher.settings.excluded.clear_world_none"));
        clearWorld.active = inWorld && config.exclusions().countInWorld() > 0;
        clearAll.setMessage(Component.translatable("inventory-searcher.settings.excluded.clear_all", config.exclusions().countAll()));
        clearAll.active = config.exclusions().countAll() > 0;
    }

    private void updateExcludeKeyButton() {
        Component key = InventorySearcherMod.excludeKey().getTranslatedKeyMessage();
        if (listeningForKey) key = Component.literal("> ").append(key.copy().withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE)).append(" <").withStyle(ChatFormatting.YELLOW);
        excludeKeyButton.setMessage(Component.translatable("inventory-searcher.settings.exclude_key", key));
    }

    private void bindExcludeKey(InputConstants.Key key) {
        listeningForKey = false;
        InventorySearcherMod.excludeKey().setKey(key);
        KeyMapping.resetMapping();
        options.save();
        updateExcludeKeyButton();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (!listeningForKey) return super.keyPressed(input);
        // like the vanilla controls, escape unbinds
        bindExcludeKey(input.key() == InputConstants.KEY_ESCAPE ? InputConstants.UNKNOWN : InputConstants.getKey(input));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!listeningForKey) return super.mouseClicked(click, doubled);
        bindExcludeKey(InputConstants.Type.MOUSE.getOrCreate(click.button()));
        return true;
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
