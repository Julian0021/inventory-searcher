package io.github.julian0021.inventorysearcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The settings, stored as JSON in config/inventory-searcher.json. */
public class SearcherConfig {
    private static final Logger LOG = LoggerFactory.getLogger("inventory-searcher");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("inventory-searcher.json");

    /** Every block entity with a container menu, in the order the settings screen lists them. */
    public static final List<BlockEntityType<?>> SELECTABLE_CONTAINERS = List.of(
        BlockEntityTypes.CHEST,
        BlockEntityTypes.TRAPPED_CHEST,
        BlockEntityTypes.BARREL,
        BlockEntityTypes.SHULKER_BOX,
        BlockEntityTypes.ENDER_CHEST,
        BlockEntityTypes.FURNACE,
        BlockEntityTypes.BLAST_FURNACE,
        BlockEntityTypes.SMOKER,
        BlockEntityTypes.HOPPER,
        BlockEntityTypes.DROPPER,
        BlockEntityTypes.DISPENSER,
        BlockEntityTypes.CRAFTER,
        BlockEntityTypes.BREWING_STAND
    );

    // General
    public List<String> containers = List.of(
        "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box", "minecraft:ender_chest", "minecraft:furnace"
    );
    public boolean rotate = true;
    public boolean swing = true;

    // Timing
    public int openDelay = 0;
    public int openTimeout = 20;
    public int clicksPerTick = 4;

    // Display
    public boolean showShulkerContents = true;
    public SortMode sort = SortMode.COUNT;

    // Excluded containers, world key to "dimension x y z"
    public Map<String, List<String>> excluded = new TreeMap<>();
    private transient Exclusions exclusions;

    public static SearcherConfig load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                SearcherConfig config = GSON.fromJson(reader, SearcherConfig.class);
                if (config != null) return config.clamped();
            } catch (IOException | JsonParseException e) {
                LOG.warn("Couldn't read {}, using the defaults", FILE, e);
            }
        }
        return new SearcherConfig();
    }

    public void save() {
        excluded = exclusions().toMap();
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOG.warn("Couldn't save {}", FILE, e);
        }
    }

    private SearcherConfig clamped() {
        if (containers == null) containers = new SearcherConfig().containers;
        if (sort == null) sort = SortMode.COUNT;
        exclusions().fromMap(excluded);
        openDelay = Math.clamp(openDelay, 0, 20);
        openTimeout = Math.clamp(openTimeout, 5, 100);
        clicksPerTick = Math.clamp(clicksPerTick, 1, 10);
        return this;
    }

    public Exclusions exclusions() {
        if (exclusions == null) {
            exclusions = new Exclusions();
            exclusions.fromMap(excluded);
        }
        return exclusions;
    }

    public Set<BlockEntityType<?>> containerTypes() {
        Set<BlockEntityType<?>> types = new LinkedHashSet<>();
        for (String id : containers) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier != null) BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(identifier).ifPresent(types::add);
        }
        return types;
    }

    public boolean isSearched(BlockEntityType<?> type) {
        return containers.contains(id(type));
    }

    public void setSearched(BlockEntityType<?> type, boolean searched) {
        Set<String> ids = new LinkedHashSet<>(containers);
        if (searched) ids.add(id(type));
        else ids.remove(id(type));
        containers = List.copyOf(ids);
    }

    public static String id(BlockEntityType<?> type) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString();
    }

    public enum SortMode {
        COUNT("Count"),
        NAME("Name"),
        MOD("Mod");

        public final String title;

        SortMode(String title) {
            this.title = title;
        }
    }
}
