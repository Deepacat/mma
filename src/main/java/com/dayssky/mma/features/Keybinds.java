package com.dayssky.mma.features;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.util.ChatUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

public class Keybinds {
    private static final KeyMapping keyBindingMeow = new KeyMapping("key.mma.meow", InputConstants.Type.KEYSYM, -1, "category.mma");
    private static final KeyMapping keyBindingPS = new KeyMapping("key.mma.playerstats", InputConstants.Type.MOUSE, 2, "category.mma");
    private static final KeyMapping togglePlayerHpIndicator = new KeyMapping("key.mma.togglePlayerHpIndicator", InputConstants.Type.KEYSYM, 66, "category.mma");
    private static final KeyMapping removeWaypointKey = new KeyMapping("key.mma.removeWaypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, "category.mma");
    private static final KeyMapping toggleLootStateKey = new KeyMapping("key.mma.toggleLootState", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "category.mma");
    private static final KeyMapping routeAddRemoveKey = new KeyMapping("key.mma.routeAddRemove", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "category.mma");
    private static final KeyMapping resetLootStateKey = new KeyMapping("key.mma.resetLootState", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "category.mma");

    private static long meowMsNext = System.currentTimeMillis();

    public static void init() {
        KeyBindingHelper.registerKeyBinding(keyBindingMeow);
        KeyBindingHelper.registerKeyBinding(keyBindingPS);
        KeyBindingHelper.registerKeyBinding(togglePlayerHpIndicator);
        KeyBindingHelper.registerKeyBinding(removeWaypointKey);
        KeyBindingHelper.registerKeyBinding(toggleLootStateKey);
        KeyBindingHelper.registerKeyBinding(routeAddRemoveKey);
        KeyBindingHelper.registerKeyBinding(resetLootStateKey);
    }

    public static void tick() {
        if (keyBindingMeow.consumeClick()) {
            onPressedMeow();
        }
        if (keyBindingPS.consumeClick()) {
            onPressedPS();
        }
        if (togglePlayerHpIndicator.consumeClick()) {
            boolean value = MMAClient.config().hpIndicator.enableGlowingPlayer = !MMAClient.config().hpIndicator.enableGlowingPlayer;
            ChatUtil.send(Component.literal("player HP glowing: " + (value ? "enabled" : "disabled")));
        }

        // ---------- Remove waypoint (double press) ----------
        if (removeWaypointKey.consumeClick()) {
            WaypointManager wm = MMAClient.WAYPOINT;
            wm.handleRemoveWaypoint();
        }

        // ---------- Toggle loot state of looked-at waypoint ----------
        if (toggleLootStateKey.consumeClick()) {
            WaypointManager wm = MMAClient.WAYPOINT;
            BlockPos target = wm.findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
            if (target == null) {
                ChatUtil.send(Component.literal("No waypoint in sight."));
                return;
            }
            var level = MMAClient.level();
            if (level != null) {
                wm.toggleLooted(level.dimension().location(), target);
            }
        }

        // ---------- Route edit looked-at waypoint keybind (add to end, or remove if newest) ----------
        if (routeAddRemoveKey.consumeClick()) {
            WaypointManager wm = MMAClient.WAYPOINT;
            BlockPos target = wm.findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
            if (target != null) {
                wm.toggleRouteWaypoint(target);
            } else {
                wm.removeLastWaypointFromRoute();
            }
        }

        // ---------- Reset all loot states for current world ----------
        if (resetLootStateKey.consumeClick()) {
            var level = MMAClient.level();
            if (level != null) {
                MMAClient.WAYPOINT.resetLooted(level.dimension().location());
            }
        }
    }

    private static void onPressedMeow() {
        long current = System.currentTimeMillis();
        if (meowMsNext <= current) {
            meowMsNext = current + 3000L;
            ChatUtil.sendCommand(String.format("chat say %s %s",
                    MMAClient.config().chat.meowingChannel,
                    MMAClient.config().chat.meowingText));
        }
    }

    private static void onPressedPS() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.crosshairPickEntity == null) {
            mc.gameRenderer.pick(0.0F);
        }
        if (mc.crosshairPickEntity instanceof Player player) {
            ChatUtil.sendCommand("ps " + player.getScoreboardName());
        }
    }
}