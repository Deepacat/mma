package com.dayssky.mma.features;

import com.dayssky.mma.Graphics;
import com.dayssky.mma.MMAClient;
import com.dayssky.mma.MMAConfig.Waypoints;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.shedaniel.math.Color;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

public class WaypointRenderer {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final WaypointManager manager;

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

    public WaypointRenderer(WaypointManager manager) {
        this.manager = manager;
    }

    private Waypoints getConfig() {
        return MMAClient.config().waypoints;
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

        // bottom
        vertex(consumer, mat, x, y, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y, z + 1, r, g, b, a);
        // top
        vertex(consumer, mat, x, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z, r, g, b, a);
        // north
        vertex(consumer, mat, x, y, z, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z, r, g, b, a);
        // south
        vertex(consumer, mat, x, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x + 1, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z + 1, r, g, b, a);
        // west
        vertex(consumer, mat, x, y, z, r, g, b, a);
        vertex(consumer, mat, x, y, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z + 1, r, g, b, a);
        vertex(consumer, mat, x, y + 1, z, r, g, b, a);
        // east
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

        float baseAlpha = Mth.clamp(getConfig().filledAlpha, 0.0f, 1.0f);
        VertexConsumer vc = buffers.getBuffer(BOX_FILLED);

        for (WaypointEntry entry : manager.getEntries()) {
            BlockPos pos = entry.pos();
            int color = manager.getColorForWaypoint(entry, playerPos);
            Color col = Color.ofOpaque(color);

            float distanceAlpha = computeDistanceAlpha(playerPos, pos);
            float finalAlpha = baseAlpha * distanceAlpha;
            if (finalAlpha <= 0.01f) continue;

            matrices.pushPose();
            matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
            drawFilledUnitCube(
                    matrices,
                    vc,
                    new BlockPos(0, 0, 0),
                    norm(col.getRed()),
                    norm(col.getGreen()),
                    norm(col.getBlue()),
                    finalAlpha
            );
            matrices.popPose();
        }
    }

    public void renderOutline(WorldRenderContext context) {
        if (!getConfig().enable || !getConfig().outline) return;
        final var consumer = Objects.requireNonNull(context.consumers()).getBuffer(Graphics.OUTLINE_BOX);
        var player = minecraft.player;
        if (player == null) return;

        for (WaypointEntry entry : manager.getEntries()) {
            BlockPos pos = entry.pos();
            int color = manager.getColorForWaypoint(entry, player.position());
            Color col = Color.ofOpaque(color);

            LevelRenderer.renderLineBox(
                    context.matrixStack(),
                    consumer,
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                    col.getRed() / 255f,
                    col.getGreen() / 255f,
                    col.getBlue() / 255f,
                    1.0f
            );
        }
    }

    public void renderLabels(WorldRenderContext context) {
        if (!getConfig().enable || !getConfig().displayRouteLabels) return;
        var player = minecraft.player;
        if (player == null) return;
        List<BlockPos> route = manager.getActiveRoutePositions();
        if (route == null || route.isEmpty()) return;

        PoseStack poseStack = context.matrixStack();
        var buffers = context.consumers();
        if (buffers == null) return;

        Camera camera = context.camera();
        Vec3 cam = camera.getPosition();
        var font = minecraft.font;
        int light = 0xF000F0;

        for (WaypointEntry entry : manager.getEntries()) {
            BlockPos pos = entry.pos();

            int index = -1;
            for (int i = 0; i < route.size(); i++) {
                BlockPos routePos = route.get(i);
                if (routePos.getX() == pos.getX() &&
                        routePos.getY() == pos.getY() &&
                        routePos.getZ() == pos.getZ()) {
                    index = i;
                    break;
                }
            }
            if (index == -1) continue;

            String text = "#" + (index + 1);
            float x = pos.getX() + 0.5f;
            float y = pos.getY() + getConfig().labelYOffset;
            float z = pos.getZ() + 0.5f;

            poseStack.pushPose();
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());

            float scale = 0.025f;
            poseStack.scale(-scale, -scale, scale);

            float textWidth = font.width(text);
            float xOffset = -textWidth / 2f;
            float yOffset = -font.lineHeight / 2f;

            float bgPad = 3.0f;
            float bgX = xOffset - bgPad;
            float bgY = yOffset - bgPad;
            float bgWidth = textWidth + bgPad * 2;
            float bgHeight = font.lineHeight + bgPad * 2;
            float bgAlpha = 200;

            Matrix4f pose = poseStack.last().pose();

            VertexConsumer background = buffers.getBuffer(RenderType.textBackgroundSeeThrough());

            // Bottom‑left
            background.vertex(pose, bgX, bgY, 0)
                    .color(0, 0, 0, bgAlpha)        // translucent black (alpha bgAlpha)
                    .uv(0, 0)
                    .uv2(light)
                    .normal(0, 0, 0)
                    .endVertex();
            // Top‑left
            background.vertex(pose, bgX, bgY + bgHeight, 0)
                    .color(0, 0, 0, bgAlpha)
                    .uv(0, 1)
                    .uv2(light)
                    .normal(0, 0, 0)
                    .endVertex();
            // Top‑right
            background.vertex(pose, bgX + bgWidth, bgY + bgHeight, 0)
                    .color(0, 0, 0, bgAlpha)
                    .uv(1, 1)
                    .uv2(light)
                    .normal(0, 0, 0)
                    .endVertex();
            // Bottom‑right
            background.vertex(pose, bgX + bgWidth, bgY, 0)
                    .color(0, 0, 0, bgAlpha)
                    .uv(1, 0)
                    .uv2(light)
                    .normal(0, 0, 0)
                    .endVertex();

            font.drawInBatch(
                    text,
                    xOffset, yOffset,
                    0xFFFFFFFF,
                    false,
                    pose,
                    buffers,
                    Font.DisplayMode.SEE_THROUGH,
                    0, // Using separately rendered background
                    light
            );

            poseStack.popPose();
        }
    }
}