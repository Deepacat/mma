package com.dayssky.mma.features;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.dayssky.mma.Graphics;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.MMAConfig;
import com.dayssky.mma.util.ChatUtil;

import static com.dayssky.mma.MMAClient.config;

import com.google.common.base.Preconditions;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import me.shedaniel.math.Color;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class Waypoint {

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("mma");
    private static final Path WAYPOINTS_DIR = CONFIG_DIR.resolve("waypoints");
    private static final Path SELECTIONS_FILE = CONFIG_DIR.resolve("waypoint_selections.json");
    private static final Path OLD_PATH = FabricLoader.getInstance().getConfigDir().resolve("mma-waypoint.json");
    private static final Path OLD_FMA_PATH = FabricLoader.getInstance().getConfigDir().resolve("fma-waypoint.json");

    private static final Codec<Map<ResourceLocation, List<BlockPos>>> CODEC = Codec.unboundedMap(
            ResourceLocation.CODEC,
            BlockPos.CODEC.listOf());

    private final Minecraft minecraft = Minecraft.getInstance();

    private MMAConfig.Waypoints getConfig() {
        return config().waypoints;
    }

    private final KeyMapping toggleRecordingKey = new KeyMapping(
            "key.mma.toggleChestWaypointRecording",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.mma");

    private final KeyMapping toggleKey = new KeyMapping(
            "key.mma.toggleChestWaypoint",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "category.mma");

    // note: only access from main thread!
    private Map<ResourceLocation, Set<BlockPos>> byWorld = new HashMap<>();
    private Map<ResourceLocation, Set<BlockPos>> brokenByWorld = new HashMap<>();
    private Map<ResourceLocation, String> currentWaypointFiles = new HashMap<>(); // world -> filename
    private CompletableFuture<Void> currCompletionToken = CompletableFuture.completedFuture(null);

    // Helper method to get world-specific directory
    private Path getWorldDir(ResourceLocation worldId) {
        // Use worldId.toString() but replace ':' with '_' for valid filename
        String worldFileName = worldId.toString().replace(':', '_');
        return WAYPOINTS_DIR.resolve(worldFileName);
    }

    // Helper method to get waypoint file path
    private Path getWaypointFilePath(ResourceLocation worldId, String filename) {
        Path worldDir = getWorldDir(worldId);
        String safeFilename = filename.endsWith(".json") ? filename : filename + ".json";
        return worldDir.resolve(safeFilename);
    }

    // Helper method to get default waypoint file path
    private Path getDefaultWaypointFilePath(ResourceLocation worldId) {
        return getWaypointFilePath(worldId, "default.json");
    }

    // Helper method to get current waypoint file path for world
    private Path getCurrentWaypointFilePath(ResourceLocation worldId) {
        String filename = currentWaypointFiles.get(worldId);
        if (filename == null || filename.isEmpty()) {
            return getDefaultWaypointFilePath(worldId);
        }
        return getWaypointFilePath(worldId, filename);
    }

    // Helper method to get list of waypoint files for a world
    private List<String> getWaypointFileNames(ResourceLocation worldId) {
        Path worldDir = getWorldDir(worldId);
        if (!Files.exists(worldDir)) {
            return new ArrayList<>();
        }

        List<String> filenames = new ArrayList<>();
        try {
            Files.list(worldDir)
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json"))
                    .forEach(path -> {
                        String filename = path.getFileName().toString();
                        filenames.add(filename.substring(0, filename.length() - 5)); // Remove .json
                    });
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to list waypoint files for world: " + worldId, e);
        }
        Collections.sort(filenames);
        return filenames;
    }

    private void synchronize(Runnable runnable) {
        CompletableFuture<Void> newToken = new CompletableFuture<>();
        currCompletionToken.thenRunAsync(runnable).thenRunAsync(() -> newToken.complete(null), Minecraft.getInstance());
        currCompletionToken = newToken;
    }

    private void load() {
        // Try to migrate old data first
        migrateOldData();

        // Load selections
        loadSelections();

        // Load data for current world (if needed)
        // Note: We'll load on-demand when switching worlds
        byWorld.clear();
        brokenByWorld.clear();
    }

    private void loadSelections() {
        if (!Files.exists(SELECTIONS_FILE)) {
            return;
        }

        try (final var in = Files.newBufferedReader(SELECTIONS_FILE)) {
            Map<String, String> selections = MMAClient.GSON.fromJson(in,
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {
                    }.getType());

            if (selections != null) {
                currentWaypointFiles.clear();
                for (Map.Entry<String, String> entry : selections.entrySet()) {
                    currentWaypointFiles.put(new ResourceLocation(entry.getKey()), entry.getValue());
                }
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load waypoint selections", e);
        }
    }

    private void saveSelections() {
        Map<String, String> selections = new HashMap<>();
        for (Map.Entry<ResourceLocation, String> entry : currentWaypointFiles.entrySet()) {
            selections.put(entry.getKey().toString(), entry.getValue());
        }

        synchronize(() -> {
            try {
                Files.createDirectories(SELECTIONS_FILE.getParent());
                try (var w = Files.newBufferedWriter(SELECTIONS_FILE)) {
                    MMAClient.GSON.toJson(selections, w);
                }
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to save waypoint selections", e);
            }
        });
    }

    private void migrateOldData() {
        // Check old FMA path first
        if (Files.exists(OLD_FMA_PATH)) {
            try {
                migrateFromOldFile(OLD_FMA_PATH);
                Files.deleteIfExists(OLD_FMA_PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from fma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from fma-waypoint.json", e);
            }
        }

        // Check old MMA path
        if (Files.exists(OLD_PATH)) {
            try {
                migrateFromOldFile(OLD_PATH);
                Files.deleteIfExists(OLD_PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from mma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from mma-waypoint.json", e);
            }
        }
    }

    private void migrateFromOldFile(Path oldPath) {
        try (final var in = Files.newBufferedReader(oldPath)) {
            final var obj = MMAClient.GSON.fromJson(in, JsonElement.class);
            Map<ResourceLocation, List<BlockPos>> oldData = CODEC.decode(JsonOps.INSTANCE, obj)
                    .result()
                    .orElseThrow()
                    .getFirst();

            // Save each world's data to its own default file
            for (Map.Entry<ResourceLocation, List<BlockPos>> entry : oldData.entrySet()) {
                Path worldPath = getDefaultWaypointFilePath(entry.getKey());
                Files.createDirectories(worldPath.getParent());

                try (var w = Files.newBufferedWriter(worldPath)) {
                    MMAClient.GSON.toJson(entry.getValue(), w);
                }

                // Set default file as selected
                currentWaypointFiles.put(entry.getKey(), "default");
            }
            saveSelections();
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to migrate from old file", e);
        }
    }

    private void save(ResourceLocation worldId) {
        Preconditions.checkState(minecraft.isSameThread());

        final List<BlockPos> worldWaypoints = new ArrayList<>(
                byWorld.getOrDefault(worldId, Collections.emptySet())
        );

        synchronize(() -> {
            try {
                String currentFilename = getCurrentFilename(worldId);
                Path worldPath = getWaypointFilePath(worldId, currentFilename);
                Files.createDirectories(worldPath.getParent());

                try (var w = Files.newBufferedWriter(worldPath)) {
                    MMAClient.GSON.toJson(worldWaypoints, w);
                }
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to save waypoints for world: " + worldId, e);
            }
        });
    }

    private void add(Level level, BlockPos pos) {
        if (!getConfig().enable) {
            return;
        }

        if (level.getBlockState(pos).getBlock() != Blocks.CHEST) {
            return;
        }

        final var dimId = level.dimension().location();

        if (getConfig().disableInPlots && dimId.getPath().contains("plot")) {
            return;
        }

        if (getConfig().disabledWorlds.contains(dimId.toString())) {
            return;
        }

        // Ensure we have the current file loaded
        if (!byWorld.containsKey(dimId)) {
            loadWorldData(dimId);
        }

        if (getConfig().skipBrokenChests) {
            brokenByWorld.computeIfAbsent(dimId, k -> new HashSet<>()).add(pos);
        }

        if (!getConfig().recordChests) {
            return;
        }

        byWorld.computeIfAbsent(dimId, k -> new HashSet<>()).add(pos);
        save(dimId); // Save immediately to the current file
    }

    public void init() {
        try {
            Files.createDirectories(WAYPOINTS_DIR);
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to create waypoints directory", e);
        }

        load();

        KeyBindingHelper.registerKeyBinding(toggleRecordingKey);
        KeyBindingHelper.registerKeyBinding(toggleKey);

        AttackBlockCallback.EVENT.register((player, level, interactionHand, blockPos, direction) -> {
            if (!level.isClientSide()) {
                return InteractionResult.PASS;
            }

            add(level, blockPos);
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) {
                return InteractionResult.PASS;
            }

            if (player.isCrouching()) {
                final var entries = byWorld.get(world.dimension().location());
                if (entries != null) {
                    entries.remove(hitResult.getBlockPos());
                    save(world.dimension().location());
                }
            } else {
                add(world, hitResult.getBlockPos());
            }

            return InteractionResult.PASS;
        });

        // Note: Command registration has been moved to Commands.java
    }

    public void clearCurrentWorld() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        // Clear from memory
        byWorld.put(worldId, new HashSet<>());
        brokenByWorld.put(worldId, new HashSet<>());

        // Save empty file
        save(worldId);

        ChatUtil.send(Component.literal("Cleared all waypoints for current world."));
    }

    public void reloadCurrentWorld() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        // Clear current in-memory data and reload from file
        byWorld.remove(worldId);
        brokenByWorld.remove(worldId);
        loadWorldData(worldId);

        ChatUtil.send(Component.literal("Reloaded waypoints from current file."));
    }

    public void listWaypointFiles() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();
        List<String> files = getWaypointFileNames(worldId);
        if (files.isEmpty()) {
            ChatUtil.send(Component.literal("No waypoint files found for this world."));
        } else {
            String current = currentWaypointFiles.getOrDefault(worldId, "default");
            ChatUtil.send(Component.literal("Waypoint files for " + worldId + ":"));
            for (String file : files) {
                String prefix = file.equals(current) ? "> " : "  ";
                ChatUtil.send(Component.literal(prefix + file));
            }
        }
    }

    private void ensureSavedIfNeeded(ResourceLocation worldId) {
        // This ensures data is saved to the correct file before switching
        if (byWorld.containsKey(worldId) && !byWorld.get(worldId).isEmpty()) {
            save(worldId);
        }
    }

    public void createWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot create file named 'default' - this is reserved for the default file."));
            return;
        }

        Path filePath = getWaypointFilePath(worldId, filename);
        if (Files.exists(filePath)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' already exists."));
            return;
        }

        try {
            Files.createDirectories(filePath.getParent());

            // Create empty file (don't copy current data)
            try (var w = Files.newBufferedWriter(filePath)) {
                MMAClient.GSON.toJson(new ArrayList<BlockPos>(), w);
            }

            // Switch to the newly created file
            currentWaypointFiles.put(worldId, filename);
            saveSelections();

            // Load the new empty file
            loadWorldData(worldId);

            ChatUtil.send(Component.literal("Created and switched to waypoint file: " + filename));
        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to create waypoint file: " + e.getMessage()));
        }
    }

    public void loadWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        Path filePath = getWaypointFilePath(worldId, filename);
        if (!Files.exists(filePath)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' does not exist."));
            return;
        }

        // Switch to new file
        currentWaypointFiles.put(worldId, filename);
        saveSelections();

        // Load new data from the new file
        loadWorldData(worldId);
        ChatUtil.send(Component.literal("Loaded waypoint file: " + filename));
    }

    // Add these methods to the Waypoint class:

    public void mergeWaypointFiles(String sourceFilename, String destinationFilename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        // Validate filenames
        if (sourceFilename.equalsIgnoreCase(destinationFilename)) {
            ChatUtil.send(Component.literal("Cannot merge a file into itself."));
            return;
        }

        if (destinationFilename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot merge into the default file for safety. Please specify a different destination file."));
            return;
        }

        Path sourcePath = getWaypointFilePath(worldId, sourceFilename);
        Path destinationPath = getWaypointFilePath(worldId, destinationFilename);

        if (!Files.exists(sourcePath)) {
            ChatUtil.send(Component.literal("Source file '" + sourceFilename + "' does not exist."));
            return;
        }

        if (!Files.exists(destinationPath)) {
            ChatUtil.send(Component.literal("Destination file '" + destinationFilename + "' does not exist."));
            return;
        }

        try {
            // Load source waypoints
            List<BlockPos> sourceWaypoints;
            try (final var in = Files.newBufferedReader(sourcePath)) {
                sourceWaypoints = MMAClient.GSON.fromJson(in,
                        new com.google.gson.reflect.TypeToken<List<BlockPos>>() {
                        }.getType());
            }

            if (sourceWaypoints == null || sourceWaypoints.isEmpty()) {
                ChatUtil.send(Component.literal("Source file '" + sourceFilename + "' is empty. Nothing to merge."));
                return;
            }

            // Load destination waypoints
            List<BlockPos> destinationWaypoints;
            try (final var in = Files.newBufferedReader(destinationPath)) {
                destinationWaypoints = MMAClient.GSON.fromJson(in,
                        new com.google.gson.reflect.TypeToken<List<BlockPos>>() {
                        }.getType());
            }

            if (destinationWaypoints == null) {
                destinationWaypoints = new ArrayList<>();
            }

            // Create a set to track unique waypoints (avoid duplicates)
            Set<BlockPos> mergedSet = new HashSet<>(destinationWaypoints);
            int initialCount = mergedSet.size();

            // Add all source waypoints (HashSet automatically handles duplicates)
            mergedSet.addAll(sourceWaypoints);

            int addedCount = mergedSet.size() - initialCount;

            // Convert back to list
            List<BlockPos> mergedWaypoints = new ArrayList<>(mergedSet);

            // Save merged waypoints back to destination file
            try (var w = Files.newBufferedWriter(destinationPath)) {
                MMAClient.GSON.toJson(mergedWaypoints, w);
            }

            // If we're currently using the destination file, reload it
            String currentFilename = getCurrentFilename(worldId);
            if (destinationFilename.equals(currentFilename)) {
                loadWorldData(worldId);
            }

            ChatUtil.send(Component.literal("Successfully merged " + addedCount + " waypoints from '" +
                    sourceFilename + "' into '" + destinationFilename + "'. Total waypoints: " + mergedWaypoints.size()));

        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to merge waypoint files: " + e.getMessage()));
            MMAClient.LOGGER.warn("Failed to merge waypoint files", e);
        }
    }

    public void mergeWaypointFilesForce(String sourceFilename, String destinationFilename, boolean replaceDuplicates) {
        // Similar to above but with different merge logic
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        // Validate filenames
        if (sourceFilename.equalsIgnoreCase(destinationFilename)) {
            ChatUtil.send(Component.literal("Cannot merge a file into itself."));
            return;
        }

        Path sourcePath = getWaypointFilePath(worldId, sourceFilename);
        Path destinationPath = getWaypointFilePath(worldId, destinationFilename);

        if (!Files.exists(sourcePath)) {
            ChatUtil.send(Component.literal("Source file '" + sourceFilename + "' does not exist."));
            return;
        }

        if (!Files.exists(destinationPath)) {
            ChatUtil.send(Component.literal("Destination file '" + destinationFilename + "' does not exist."));
            return;
        }

        try {
            // Load source waypoints
            List<BlockPos> sourceWaypoints;
            try (final var in = Files.newBufferedReader(sourcePath)) {
                sourceWaypoints = MMAClient.GSON.fromJson(in,
                        new com.google.gson.reflect.TypeToken<List<BlockPos>>() {
                        }.getType());
            }

            if (sourceWaypoints == null || sourceWaypoints.isEmpty()) {
                ChatUtil.send(Component.literal("Source file is empty. Nothing to merge."));
                return;
            }

            // Load destination waypoints
            List<BlockPos> destinationWaypoints;
            try (final var in = Files.newBufferedReader(destinationPath)) {
                destinationWaypoints = MMAClient.GSON.fromJson(in,
                        new com.google.gson.reflect.TypeToken<List<BlockPos>>() {
                        }.getType());
            }

            if (destinationWaypoints == null) {
                destinationWaypoints = new ArrayList<>();
            }

            // Create a map for quick lookup if we need to handle replacements
            Map<String, BlockPos> destinationMap = new HashMap<>();
            for (BlockPos pos : destinationWaypoints) {
                destinationMap.put(posToString(pos), pos);
            }

            List<BlockPos> mergedWaypoints = new ArrayList<>(destinationWaypoints);
            int replacedCount = 0;
            int addedCount = 0;

            for (BlockPos sourcePos : sourceWaypoints) {
                String posKey = posToString(sourcePos);
                if (destinationMap.containsKey(posKey)) {
                    if (replaceDuplicates) {
                        // Replace the existing waypoint
                        destinationMap.put(posKey, sourcePos);
                        replacedCount++;
                    }
                    // If not replacing, skip duplicates
                } else {
                    destinationMap.put(posKey, sourcePos);
                    addedCount++;
                }
            }

            // Convert map values back to list
            mergedWaypoints = new ArrayList<>(destinationMap.values());

            // Save merged waypoints
            try (var w = Files.newBufferedWriter(destinationPath)) {
                MMAClient.GSON.toJson(mergedWaypoints, w);
            }

            // Reload if current file is the destination
            String currentFilename = getCurrentFilename(worldId);
            if (destinationFilename.equals(currentFilename)) {
                loadWorldData(worldId);
            }

            ChatUtil.send(Component.literal("Merged: Added " + addedCount + " new waypoints, " +
                    (replaceDuplicates ? "replaced " + replacedCount + " duplicates" : "skipped " + replacedCount + " duplicates") +
                    ". Total: " + mergedWaypoints.size()));

        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to merge waypoint files: " + e.getMessage()));
            MMAClient.LOGGER.warn("Failed to merge waypoint files", e);
        }
    }

    // Helper method to convert BlockPos to string key
    private String posToString(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void deleteWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }

        final var worldId = level.dimension().location();

        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot delete the default waypoint file."));
            return;
        }

        String current = currentWaypointFiles.getOrDefault(worldId, "default");
        if (filename.equals(current)) {
            ChatUtil.send(Component.literal("Cannot delete the currently selected waypoint file. Switch to another file first."));
            return;
        }

        Path filePath = getWaypointFilePath(worldId, filename);
        if (!Files.exists(filePath)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' does not exist."));
            return;
        }

        try {
            Files.delete(filePath);
            ChatUtil.send(Component.literal("Deleted waypoint file: " + filename));
        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to delete waypoint file: " + e.getMessage()));
        }
    }

    // Helper method for command suggestions
    public List<String> getCurrentWorldWaypointFiles() {
        var level = MMAClient.level();
        if (level == null) {
            return new ArrayList<>();
        }
        return getWaypointFileNames(level.dimension().location());
    }

    private String getCurrentFilename(ResourceLocation worldId) {
        return currentWaypointFiles.getOrDefault(worldId, "default");
    }

    private void loadWorldData(ResourceLocation worldId) {
        String currentFilename = getCurrentFilename(worldId);
        Path worldPath = getWaypointFilePath(worldId, currentFilename);

        if (!Files.exists(worldPath)) {
            // Create empty file if it doesn't exist
            try {
                Files.createDirectories(worldPath.getParent());
                try (var w = Files.newBufferedWriter(worldPath)) {
                    MMAClient.GSON.toJson(new ArrayList<BlockPos>(), w);
                }
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to create empty waypoint file: " + worldPath, e);
            }

            byWorld.put(worldId, new HashSet<>());
            brokenByWorld.put(worldId, new HashSet<>());
            return;
        }

        try (final var in = Files.newBufferedReader(worldPath)) {
            List<BlockPos> positions = MMAClient.GSON.fromJson(in,
                    new com.google.gson.reflect.TypeToken<List<BlockPos>>() {
                    }.getType());

            if (positions != null) {
                // Completely replace the data for this world
                byWorld.put(worldId, new HashSet<>(positions));
                brokenByWorld.put(worldId, new HashSet<>());
            } else {
                byWorld.put(worldId, new HashSet<>());
                brokenByWorld.put(worldId, new HashSet<>());
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load waypoints for world: " + worldId + " from file: " + currentFilename, e);
            byWorld.put(worldId, new HashSet<>());
            brokenByWorld.put(worldId, new HashSet<>());
        }
    }

    public void clientInit() {

    }

    public void tick() {
        if (toggleRecordingKey.consumeClick()) {
            getConfig().recordChests = !getConfig().recordChests;
            ChatUtil.send(
                    Component.literal("chest break recording: " + (getConfig().recordChests ? "enabled" : "disabled")));
        }

        if (toggleKey.consumeClick()) {
            getConfig().enable = !getConfig().enable;
            ChatUtil.send(Component.literal("chest waypoints: " + (getConfig().enable ? "enabled" : "disabled")));
        }
    }

    private Iterable<BlockPos> getEntries(LocalPlayer player) {
        var level = MMAClient.level();
        if (level == null) return Set.of();

        var worldKey = level.dimension().location();

        // Always ensure we have data loaded for this world
        if (!byWorld.containsKey(worldKey)) {
            loadWorldData(worldKey);
        }

        var entries = byWorld.getOrDefault(worldKey, Set.of());
        if (entries.isEmpty()) return Set.of();

        // Ensure brokenByWorld has an entry for this world
        var broken = brokenByWorld.computeIfAbsent(worldKey, k -> new HashSet<>());

        Vec3 playerPos = player.position();
        long radiusSq = (long) getConfig().radius * getConfig().radius;

        return entries.stream()
                .filter(pos -> {
                    if (getConfig().skipBrokenChests && broken.contains(pos)) {
                        return false;
                    }

                    if (getConfig().radius <= 0) {
                        return true;
                    }

                    double dx = pos.getX() - playerPos.x;
                    double dy = pos.getY() - playerPos.y;
                    double dz = pos.getZ() - playerPos.z;

                    return dx * dx + dy * dy + dz * dz <= radiusSq;
                })
                .toList();
    }

    private static float norm(int c) {
        return c / 255f;
    }

    private static void drawFilledUnitCube(
            PoseStack poseStack,
            VertexConsumer consumer,
            BlockPos pos,
            float r, float g, float b, float a
    ) {
        Matrix4f mat = poseStack.last().pose();

        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();

        // Bottom face (y = y)
        vertex(consumer, mat, x, y, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y, z + 1, r, g, b, a);

        // Top face (y = y + 1)
        vertex(consumer, mat, x, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z, r, g, b, a);

        // North face (z = z)
        vertex(consumer, mat, x, y, z, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z, r, g, b, a);

        // South face (z = z + 1)
        vertex(consumer, mat, x, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z + 1, r, g, b, a);

        // West face (x = x)
        vertex(consumer, mat, x, y, z, r, g, b, a);
        vertex(consumer, mat, x, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z, r, g, b, a);

        // East face (x = x + 1)
        vertex(consumer, mat, x + 1, y, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z + 1, r, g, b, a);
    }

    private static void vertex(
            VertexConsumer vc,
            Matrix4f mat,
            float x, float y, float z,
            float r, float g, float b, float a
    ) {
        vc.vertex(mat, x, y, z)
                .color(r, g, b, a)
                .uv2(0xF000F0)
                .normal(0, 1, 0)
                .endVertex();
    }

    private float computeDistanceAlpha(Vec3 player, BlockPos pos) {
        if (!getConfig().distanceFade || getConfig().radius <= 0) return 1.0f;

        double d = player.distanceTo(Vec3.atCenterOf(pos));
        double r = getConfig().radius;

        if (d >= r) return 0.0f;

        return (float) (1.0 - d / r);
    }

    private static final RenderType BOX_FILLED = RenderType.create(
            "mma_box_filled",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_LIGHTMAP_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .createCompositeState(true)
    );

    private void drawLine(VertexConsumer vc, Matrix4f pose,
                          float x1, float y1, float z1,
                          float x2, float y2, float z2,
                          float r, float g, float b, float a) {
        vc.vertex(pose, x1, y1, z1).color(r, g, b, a).endVertex();
        vc.vertex(pose, x2, y2, z2).color(r, g, b, a).endVertex();
    }

    public void renderFilled(WorldRenderContext context) {
        if (!getConfig().enable || !getConfig().filled) return;

        var player = minecraft.player;
        if (player == null) return;

        PoseStack matrices = context.matrixStack();
        var buffers = context.consumers();
        if (buffers == null) return;

        Camera camera = context.camera();
        Vec3 cam = camera.getPosition();
        Vec3 playerPos = player.position();

        var color = Color.ofOpaque(getConfig().color);
        float baseAlpha = Mth.clamp(getConfig().filledAlpha, 0.0f, 1.0f);

        VertexConsumer vc = buffers.getBuffer(BOX_FILLED);

        for (BlockPos pos : getEntries(player)) {
            float distanceAlpha = computeDistanceAlpha(playerPos, pos);
            float finalAlpha = baseAlpha * distanceAlpha;

            if (finalAlpha <= 0.01f) continue;

            matrices.pushPose();
            matrices.translate(
                    pos.getX() - cam.x,
                    pos.getY() - cam.y,
                    pos.getZ() - cam.z
            );

            drawFilledUnitCube(
                    matrices,
                    vc,
                    new BlockPos(0, 0, 0),
                    norm(color.getRed()),
                    norm(color.getGreen()),
                    norm(color.getBlue()),
                    finalAlpha
            );

            matrices.popPose();
        }
    }

    public void renderOutline(WorldRenderContext context) {
        if (!getConfig().enable || !getConfig().outline) return;

        final var consumer = Objects.requireNonNull(context.consumers()).getBuffer(Graphics.OUTLINE_BOX);

        var level = MMAClient.level();
        if (level == null) return;

        var worldKey = level.dimension().location();

        // Load if not already loaded
        if (!byWorld.containsKey(worldKey)) {
            loadWorldData(worldKey);
        }

        final var entries = byWorld.getOrDefault(worldKey, Set.of());
        final var broken = brokenByWorld.computeIfAbsent(worldKey, k -> new HashSet<>());

        final var player = minecraft.player;
        if (player == null) {
            return;
        }

        final var playerPos = player.position();
        final long radiusSquared = (long) getConfig().radius * getConfig().radius;

        for (final var entry : entries) {
            if (getConfig().skipBrokenChests && broken.contains(entry)) {
                continue;
            }

            int x = entry.getX();
            int y = entry.getY();
            int z = entry.getZ();

            double dx = x - playerPos.x;
            double dy = y - playerPos.y;
            double dz = z - playerPos.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;

            if (distanceSquared > radiusSquared && getConfig().radius != 0) {
                continue;
            }

            final var color = Color.ofOpaque(getConfig().color);
            LevelRenderer.renderLineBox(
                    context.matrixStack(),
                    consumer,
                    x, y, z, x + 1, y + 1, z + 1,
                    color.getRed() / 255f,
                    color.getGreen() / 255f,
                    color.getBlue() / 255f,
                    1);
        }
        context.consumers();
    }
}