package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * First-person flashlight: the player's right arm holds the flashlight (compact, lower right),
 * the hand floats with a soft spring instead of jerking, and a high-resolution cone of rays
 * is drawn from the lens along the view direction.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightClient {

    // ======== tweakables: position / size of the arm and flashlight (view space, blocks) ========
    private static final float BASE_X = -0.02f;      // whole arm: + right
    private static final float BASE_Y = 0.10f;       // whole arm: + up
    private static final float BASE_Z = 0.00f;       // whole arm: + towards you
    private static final float ARM_SCALE = 0.80f;    // arm size (smaller = more compact)
    private static final float HAND_X = -0.375f;     // grip point inside the arm model
    private static final float HAND_Y = 0.64f;
    private static final float FLASH_SCALE = 0.72f;  // flashlight size
    private static final float GRIP_Z = 0.10f;       // where along the flashlight the hand holds it (+ = towards tail)
    private static final float FLASH_YAW = 14f;      // aim: + = lens turns left
    private static final float FLASH_PITCH = 7f;     // aim: + = lens turns up

    // ======== tweakables: hand motion ========
    private static final float SPRING_K = 70f;       // stiffness
    private static final float SPRING_C = 11f;       // damping (higher = calmer)
    private static final float IMPULSE = 0.045f;     // how much mouse movement pushes the hand
    private static final float MAX_OFFSET = 0.14f;

    // ======== tweakables: beam ========
    private static final int SEGMENTS = 96;          // rays around the cone (resolution)
    private static final float[] HALF_ANGLE = {5.5f, 9f, 13.5f};
    private static final float[] BASE_ALPHA = {0.16f, 0.11f, 0.07f};
    private static final float BEAM_MAX = 22f;

    // ---- state
    private static float swX, swY, swVX, swVY, kick, lastYaw, lastPitch, time;
    private static boolean swInit, lastOn;
    private static long lastNs;
    private static Vec3 lensView = new Vec3(0.25, -0.25, -0.70); // lens position in view space, updated each frame
    private static float beamLen = 8f;

    private static void updateSway(LocalPlayer p, float pt, boolean on) {
        long now = System.nanoTime();
        float dt = swInit ? Math.min(0.05f, (now - lastNs) / 1e9f) : 0.016f;
        lastNs = now;
        float yaw = p.getViewYRot(pt), pitch = p.getViewXRot(pt);
        if (!swInit) { lastYaw = yaw; lastPitch = pitch; lastOn = on; swInit = true; }
        float dy = Mth.wrapDegrees(yaw - lastYaw), dp = pitch - lastPitch;
        lastYaw = yaw; lastPitch = pitch;

        swVX += -dy * IMPULSE;
        swVY += dp * IMPULSE;
        swVX += (-SPRING_K * swX - SPRING_C * swVX) * dt;
        swVY += (-SPRING_K * swY - SPRING_C * swVY) * dt;
        swX = Mth.clamp(swX + swVX * dt, -MAX_OFFSET, MAX_OFFSET);
        swY = Mth.clamp(swY + swVY * dt, -MAX_OFFSET, MAX_OFFSET);

        if (on != lastOn) { kick = 1f; lastOn = on; }
        kick *= (float) Math.exp(-dt * 10f);
        time += dt;
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent e) {
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack stack = e.getItemStack();
        if (!stack.is(SlenderFlashlightMod.FLASHLIGHT.get())) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (pl == null) return;
        e.setCanceled(true); // we draw the arm and the flashlight ourselves

        boolean on = FlashlightItem.isOn(stack);
        updateSway(pl, e.getPartialTick(), on);

        PoseStack ps = e.getPoseStack();
        MultiBufferSource buf = e.getMultiBufferSource();
        int light = e.getPackedLight();
        if (on) { // the flashlight lights its own hand a bit
            light = LightTexture.pack(Math.max(LightTexture.block(light), 9), Math.max(LightTexture.sky(light), 9));
        }
        float equip = e.getEquipProgress();
        float breatheX = (float) Math.sin(time * 1.1f) * 0.002f;
        float breatheY = (float) Math.sin(time * 1.7f) * 0.003f;

        // ---- arm (vanilla right-arm pose, shrunk and shifted)
        ps.pushPose();
        ps.translate(BASE_X + swX + breatheX, BASE_Y + swY + breatheY - equip * 0.6f, BASE_Z);
        ps.mulPose(Vector3f.ZP.rotationDegrees(swX * -35f));
        ps.mulPose(Vector3f.XP.rotationDegrees(kick * 4f));
        ps.translate(0.64000005f, -0.6f, -0.71999997f);
        ps.mulPose(Vector3f.YP.rotationDegrees(45f));
        ps.scale(ARM_SCALE, ARM_SCALE, ARM_SCALE);
        ps.translate(-1f, 3.6f, 3.5f);
        ps.mulPose(Vector3f.ZP.rotationDegrees(120f));
        ps.mulPose(Vector3f.XP.rotationDegrees(200f));
        ps.mulPose(Vector3f.YP.rotationDegrees(-135f));
        ps.translate(5.6f, 0f, 0f);

        Vector4f hand = new Vector4f(HAND_X, HAND_Y, 0f, 1f);
        hand.transform(ps.last().pose()); // where the hand ends up on screen

        EntityRenderer<?> r = mc.getEntityRenderDispatcher().getRenderer(pl);
        if (r instanceof PlayerRenderer pr) {
            pr.renderRightHand(ps, buf, light, pl);
        }
        ps.popPose();

        // ---- flashlight in that hand
        ps.pushPose();
        ps.translate(hand.x(), hand.y(), hand.z());
        ps.mulPose(Vector3f.YP.rotationDegrees(FLASH_YAW));
        ps.mulPose(Vector3f.XP.rotationDegrees(FLASH_PITCH + kick * 5f));
        ps.scale(FLASH_SCALE, FLASH_SCALE, FLASH_SCALE);
        ps.translate(0f, 0f, -GRIP_Z);

        Vector4f lens = new Vector4f(0f, 0f, -0.47f, 1f);
        lens.transform(ps.last().pose());
        lensView = new Vec3(lens.x(), lens.y(), lens.z());

        mc.getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.NONE, light,
                OverlayTexture.NO_OVERLAY, ps, buf, 0);
        ps.popPose();
    }

    // ------------------------------------------------------------------ beam

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !mc.options.getCameraType().isFirstPerson()) return;
        boolean mainOn = FlashlightItem.isOn(p.getMainHandItem());
        if (!mainOn && !FlashlightItem.isOn(p.getOffhandItem())) return;

        Camera cam = e.getCamera();
        Vec3 camPos = cam.getPosition();
        Vector3f lk = cam.getLookVector(), up = cam.getUpVector(), lf = cam.getLeftVector();
        Vec3 look = new Vec3(lk.x(), lk.y(), lk.z());
        Vec3 upV = new Vec3(up.x(), up.y(), up.z());
        Vec3 right = new Vec3(-lf.x(), -lf.y(), -lf.z());

        Vec3 lv = mainOn ? lensView : new Vec3(-0.30, -0.25, -0.70);
        Vec3 origin = right.scale(lv.x).add(upV.scale(lv.y)).add(look.scale(-lv.z)); // relative to camera

        BlockHitResult hit = mc.level.clip(new ClipContext(camPos, camPos.add(look.scale(BEAM_MAX)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double dist = hit.getType() == HitResult.Type.MISS ? BEAM_MAX : camPos.distanceTo(hit.getLocation());
        float target = (float) Mth.clamp(dist - origin.dot(look) + 0.1, 1.0, BEAM_MAX);
        beamLen += (target - beamLen) * 0.25f;

        drawBeam(e.getPoseStack(), origin, look, upV, right, beamLen);
    }

    private static float shimmer(int i, int layer, double t) {
        return (float) (0.65 + 0.35 * Math.sin(t * (0.9 + 0.13 * ((i * 7 + layer * 3) % 9)) + i * 1.37 + layer * 2.1));
    }

    private static Vec3 ring(Vec3 u, Vec3 v, double a, double radius) {
        return u.scale(Math.cos(a) * radius).add(v.scale(Math.sin(a) * radius));
    }

    private static void tri(BufferBuilder bb, Matrix4f m, Vec3 a, float aa, Vec3 b, float ba, Vec3 c, float ca) {
        bb.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(1f, 0.96f, 0.84f, aa).endVertex();
        bb.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(1f, 0.96f, 0.84f, ba).endVertex();
        bb.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(1f, 0.96f, 0.84f, ca).endVertex();
    }

    private static void drawBeam(PoseStack ps, Vec3 o, Vec3 axis, Vec3 u, Vec3 v, float len) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f m = ps.last().pose();
        double t = System.nanoTime() / 1e9;
        Vec3 mid = o.add(axis.scale(len * 0.5));
        Vec3 end = o.add(axis.scale(len));

        for (int layer = 0; layer < HALF_ANGLE.length; layer++) {
            double rad = Math.tan(Math.toRadians(HALF_ANGLE[layer]));
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = 2 * Math.PI * i / SEGMENTS, a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
                float n = 0.5f * (shimmer(i, layer, t) + shimmer(i + 1, layer, t));
                float aa = BASE_ALPHA[layer] * n;
                float am = aa * 0.5f;
                Vec3 m0 = mid.add(ring(u, v, a0, len * 0.5 * rad));
                Vec3 m1 = mid.add(ring(u, v, a1, len * 0.5 * rad));
                Vec3 e0 = end.add(ring(u, v, a0, len * rad));
                Vec3 e1 = end.add(ring(u, v, a1, len * rad));
                tri(bb, m, o, aa, m0, am, m1, am);
                tri(bb, m, m0, am, e0, 0f, e1, 0f);
                tri(bb, m, m0, am, e1, 0f, m1, am);
            }
        }
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
