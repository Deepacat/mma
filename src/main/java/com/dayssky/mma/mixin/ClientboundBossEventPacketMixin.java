package com.dayssky.mma.mixin;

import com.dayssky.mma.events.ClientBossBarUpdateEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.UUID;

@Mixin(ClientboundBossEventPacket.class)
public class ClientboundBossEventPacketMixin {

    @Inject(method = "dispatch", at = @At("RETURN"))
    private void afterDispatch(ClientboundBossEventPacket.Handler handler, CallbackInfo ci) {
        Minecraft.getInstance().execute(() -> {
            BossHealthOverlay overlay = Minecraft.getInstance().gui.getBossOverlay();
            Map<UUID, BossEvent> events = ((BossHealthOverlayAccessor) overlay).getEvents();
            for (Map.Entry<UUID, BossEvent> entry : events.entrySet()) {
                ClientBossBarUpdateEvent.EVENT.invoker().onBossBarUpdate(entry.getKey(), entry.getValue());
            }
        });
    }
}