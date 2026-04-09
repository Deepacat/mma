package com.dayssky.mma.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.world.BossEvent;
import java.util.UUID;

public interface ClientBossBarUpdateEvent {
    Event<ClientBossBarUpdateEvent> EVENT = EventFactory.createArrayBacked(
            ClientBossBarUpdateEvent.class,
            listeners -> (uuid, bossEvent) -> {
                for (ClientBossBarUpdateEvent listener : listeners) {
                    listener.onBossBarUpdate(uuid, bossEvent);
                }
            }
    );

    void onBossBarUpdate(UUID uuid, BossEvent bossEvent);
}