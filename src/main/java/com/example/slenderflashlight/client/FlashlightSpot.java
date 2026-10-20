package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Круглое мягкое световое пятно на поверхности, куда светит фонарик (как в Slender: The Arrival).
 * Это визуальное свечение поверх мира: настоящее освещение Minecraft остаётся блочным,
 * а это пятно рисуется как идеальный круг с плавным краем.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightSpot {

    private static final double RANGE = 22;
    private static final int SEGMENTS = 48;
    private static final int RINGS = 7;
    /** Яркость пятна в центре (0..1). */
    private static final float PEAK_ALPHA = 0.42f;
    /** Радиус пятна вблизи и как он растёт с расстоянием. */
    private static final double BASE_RADIUS = 0.45;
    private static final double RADIUS_PER_BLOCK = 0.20;
    private static final double MAX_RADIUS = 3.2;

    private static float fade;
    private static long lastNs;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (pl == null || mc.level == null) return;

        boolean on = FlashlightItem.isOn(pl.getMainHandItem()) || FlashlightItem.isOn(pl.getOffhandItem());
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        fade += Mth.clamp((on ? 1f : 0f) - fade, -dt * 6f, dt * 6f);
        if (fade <= 0.01f) return;

        float pt = e.getPartialTick();
        Vec3 eye = pl.getEyePosition(pt);
        Vec3 look = pl.getViewVector(pt);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pl));
        if (hit.getType() == HitResult.Type.MISS) return;

        Vec3 p = hit.getLocation();
        Vec3 n = new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ());
        double dist = eye.distanceTo(p);

        Vec3 centre = p.add(n.scale(0.012));
        Vec3 helper = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = n.cross(helper).normalize();
        Vec3 v = n.cross(u).normalize();

        double radius = Math.min(MAX_RADIUS, BASE_RADIUS + dist * RADIUS_PER_BLOCK);
        float atten = (float) Mth.clamp(1.0 - dist / RANGE * 0.6, 0.3, 1.0);
        float peak = PEAK_ALPHA * fade * atten;

        Vec3 cam = e.getCamera().getPosition();
        Matrix4f m = e.getPoseStack().last().pose();

        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int r = 0; r < RINGS; r++) {
            double r0 = (double) r / RINGS, r1 = (double) (r + 1) / RINGS;
            float a0 = falloff(r0) * peak, a1 = falloff(r1) * peak;
            for (int s = 0; s < SEGMENTS; s++) {
                double ang0 = 2 * Math.PI * s / SEGMENTS, ang1 = 2 * Math.PI * (s + 1) / SEGMENTS;
                Vec3 p00 = point(centre, u, v, radius * r0, ang0, cam);
                Vec3 p01 = point(centre, u, v, radius * r0, ang1, cam);
                Vec3 p10 = point(centre, u, v, radius * r1, ang0, cam);
                Vec3 p11 = point(centre, u, v, radius * r1, ang1, cam);
                vert(bb, m, p00, a0);
                vert(bb, m, p10, a1);
                vert(bb, m, p11, a1);
                vert(bb, m, p00, a0);
                vert(bb, m, p11, a1);
                vert(bb, m, p01, a0);
            }
        }
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** Мягкий спад яркости от центра к краю круга. */
    private static float falloff(double r) {
        double k = 1.0 - r * r;
        return (float) (k * k);
    }

    private static Vec3 point(Vec3 centre, Vec3 u, Vec3 v, double rad, double ang, Vec3 cam) {
        return centre.add(u.scale(Math.cos(ang) * rad)).add(v.scale(Math.sin(ang) * rad)).subtract(cam);
    }

    private static void vert(BufferBuilder bb, Matrix4f m, Vec3 p, float a) {
        bb.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, 0.95f, 0.78f, a).endVertex();
    }
}
