package com.dayssky.mma.features.gamestate;

import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

public interface StateTracker {
    default void onLeave() {
    }

    default void onChatMessage(Component message) {
    }

    default void onTitle(Component message) {
    }

    default void onSubtitle(Component message) {
    }

    default void onActionBar(Component message) {
    }

    default void onTick() {
    }

    default void onRender(WorldRenderContext context) {
    }

    default void onBossBar(UUID uuid, BossEvent bossEvent) {
    }

    default List<Component> getAdditionalSidebarText() {
        return List.of();
    }
}
