package io.github.julian0021.inventorysearcher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.*;

/**
 * Containers the player excluded from searching. They are kept per world (singleplayer save folder) or server
 * address and identified by dimension and position, a double chest by its smaller half.
 */
public class Exclusions {
    private final Map<String, Set<String>> byWorld = new TreeMap<>();

    /** Identifies the current singleplayer world or server. */
    public static String worldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "world:" + root.getFileName();
        }
        ServerData server = mc.getCurrentServer();
        return server != null ? "server:" + server.ip.toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String posKey(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        String dimension = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
        return dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public boolean isExcluded(BlockPos key) {
        Set<String> excluded = byWorld.get(worldKey());
        return excluded != null && excluded.contains(posKey(key));
    }

    /** Excludes the container or includes it again, returns whether it is excluded now. */
    public boolean toggle(BlockPos key) {
        String world = worldKey();
        Set<String> excluded = byWorld.computeIfAbsent(world, k -> new TreeSet<>());
        String pos = posKey(key);
        boolean nowExcluded = excluded.add(pos);
        if (!nowExcluded) excluded.remove(pos);
        if (excluded.isEmpty()) byWorld.remove(world);
        return nowExcluded;
    }

    /** How many containers are excluded in the current world, in all dimensions. */
    public int countInWorld() {
        Set<String> excluded = byWorld.get(worldKey());
        return excluded == null ? 0 : excluded.size();
    }

    public int countAll() {
        return byWorld.values().stream().mapToInt(Set::size).sum();
    }

    public void clearWorld() {
        byWorld.remove(worldKey());
    }

    public void clearAll() {
        byWorld.clear();
    }

    /** A copy for saving, world key to "dimension x y z" entries. */
    public Map<String, List<String>> toMap() {
        Map<String, List<String>> map = new TreeMap<>();
        byWorld.forEach((world, positions) -> map.put(world, List.copyOf(positions)));
        return map;
    }

    public void fromMap(Map<String, ? extends Collection<String>> map) {
        byWorld.clear();
        if (map == null) return;
        map.forEach((world, positions) -> {
            if (positions != null && !positions.isEmpty()) byWorld.put(world, new TreeSet<>(positions));
        });
    }
}
