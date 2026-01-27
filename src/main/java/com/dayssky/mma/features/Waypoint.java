package com.dayssky.mma.features;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.lwjgl.glfw.GLFW;

import com.dayssky.mma.Graphics;
import com.dayssky.mma.MMAClient;
import com.dayssky.mma.MMAConfig;
import com.dayssky.mma.util.ChatUtil;
import static com.dayssky.mma.util.CommandUtil.lit;
import com.google.common.base.Preconditions;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import me.shedaniel.math.Color;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class Waypoint {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mma-waypoint.json");
    private static final Path OLD_PATH = FabricLoader.getInstance().getConfigDir().resolve("mma-waypoint.json");
    private static final Path OLD_FMA_PATH = FabricLoader.getInstance().getConfigDir().resolve("fma-waypoint.json");

    private static final Codec<Map<ResourceLocation, List<BlockPos>>> CODEC = Codec.unboundedMap(
            ResourceLocation.CODEC,
            BlockPos.CODEC.listOf());

    private final Minecraft minecraft = Minecraft.getInstance();

    private MMAConfig.Waypoints getConfig() {
        return MMAClient.config().waypoints;
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
    private CompletableFuture<Void> currCompletionToken = CompletableFuture.completedFuture(null);

    private void synchronize(Runnable runnable) {
        CompletableFuture<Void> newToken = new CompletableFuture<>();
        currCompletionToken.thenRunAsync(runnable).thenRunAsync(() -> newToken.complete(null), Minecraft.getInstance());
        currCompletionToken = newToken;
    }

    private void load() {
        if (!Files.exists(Waypoint.PATH) && Files.exists(Waypoint.OLD_FMA_PATH)) {
            try {
                Files.move(Waypoint.OLD_FMA_PATH, Waypoint.PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from fma-waypoint.json to mma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from fma-waypoint.json", e);
            }
        }

        if (!Files.exists(Waypoint.PATH)) {
            return;
        }

        try (final var in = Files.newBufferedReader(Waypoint.PATH)) {
            final var obj = MMAClient.GSON.fromJson(in, JsonElement.class);
            byWorld = CODEC.decode(JsonOps.INSTANCE, obj)
                    .result()
                    .orElseThrow()
                    .getFirst()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new HashSet<>(e.getValue())));
            brokenByWorld = byWorld.entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new HashSet<>()));

        } catch (Exception e) {
            MMAClient.LOGGER.warn(e);
        }
    }

    private void save() {
        Preconditions.checkState(minecraft.isSameThread());
        final Map<ResourceLocation, List<BlockPos>> copy = byWorld.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ArrayList<>(e.getValue())));

        synchronize(() -> {
            try {
                try (var w = Files.newBufferedWriter(Waypoint.PATH)) {
                    MMAClient.GSON.toJson(copy, w);
                }

            } catch (Exception e) {
                MMAClient.LOGGER.warn(e);
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
        if (getConfig().skipBrokenChests) {
            final var broken = brokenByWorld.get(level.dimension().location());
            if (broken != null) {
                broken.add(pos);
            }
        }

        if (!getConfig().recordChests) {
            return;
        }

        byWorld.computeIfAbsent(dimId, ignored -> new HashSet<>()).add(pos);
        save();
    }

    public void init() {
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
                }
            } else {
                add(world, hitResult.getBlockPos());
            }

            return InteractionResult.PASS;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(lit("mma", lit("waypoint", lit("clear", c -> {
                byWorld.remove(c.getSource().getWorld().dimension().location());
                save();
                return 0;
            }), lit("reload", c -> {
                load();
                return 0;
            }))));
        });
    }

    public void clientInit() {

    }

    public void tick() {
        if (toggleRecordingKey.consumeClick()) {
            getConfig().recordChests = !getConfig().recordChests;
            // TODO: i18n
            ChatUtil.send(
                    Component.literal("chest break recording: " + (getConfig().recordChests ? "enabled" : "disabled")));
        }

        if (toggleKey.consumeClick()) {
            getConfig().enable = !getConfig().enable;
            // TODO: i18n
            ChatUtil.send(Component.literal("chest waypoints: " + (getConfig().enable ? "enabled" : "disabled")));
        }
    }

    public void render(WorldRenderContext context) {
        if (!getConfig().enable) {
            return;
        }

        final var consumer = Objects.requireNonNull(context.consumers()).getBuffer(Graphics.OUTLINE_BOX);
        final var entries = byWorld.getOrDefault(MMAClient.level().dimension().location(), Set.of());
        final var broken = brokenByWorld.getOrDefault(MMAClient.level().dimension().location(), Set.of());

        final var player = minecraft.player;
        if (player == null) {
            return;
        }

        final var playerPos = player.position();
        final long radiusSquared = getConfig().radius * getConfig().radius;


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
