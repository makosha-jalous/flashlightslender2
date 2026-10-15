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
import com.mojang.math.Quaternion;
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
 * First-person flashlight, styled after Slender: The Arrival.
 *
 *  - OFF: nothing is drawn (no flashlight, no hand).
 *  - ON : the flashlight and the player's right arm slide in from the RIGHT edge of the screen.
 *         The arm and the flashlight are ONE rigid object: they share the same sway / bobbing,
 *         so they can never drift apart. The hand is sunk into the flashlight body.
 *  - Beam: 5 thick, soft, transparent rays that slowly rotate counter-clockwise around the beam axis.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightClient {

    // ======== tweakables: the whole rig (flashlight + arm) ========
    // Everything below is expressed in "rig space"; the whole rig is then shrunk by RIG_SCALE
    // (smaller = the rig sits closer to the camera, same picture on screen).
    private static final float RIG_SCALE = 0.72f;

    // flashlight: centre position (x + right, y + up, z - forward), size and aim
    private static final float FLASH_X = 0.394f;
    private static final float FLASH_Y = -0.333f;
    private static final float FLASH_Z = -1.254f;
    private static final float FLASH_SCALE = 0.72f;  // flashlight size
    private static final float FLASH_YAW = 14f;      // aim: + = lens turns left
    private static final float FLASH_PITCH = 5f;     // aim: + = lens turns up

    // where the hand holds the flashlight, in flashlight-model blocks: (0,0,0) = centre, +z = towards the tail,
    // +x = right side. A big x would move the hand off the body, a small x sinks it deeper into the body.
    private static final float GRIP_X = 0.10f;
    private static final float GRIP_Y = -0.10f;
    private static final float GRIP_Z = 0.32f;

    // arm: direction from the hand towards the shoulder (the arm leaves the screen on the right side)
    private static final float ARM_DX = 1.0f;
    private static final float ARM_DY = -0.12f;      // more negative = the arm goes lower
    private static final float ARM_DZ = 0.30f;       // more positive = the arm comes closer to you
    private static final float ARM_ROLL = 0f;        // turns the arm around its own axis (degrees)
    private static final float ARM_WIDTH = 0.95f;    // arm thickness
    private static final float ARM_LENGTH = 1.35f;   // arm length (long enough to reach past the screen edge)
    private static final float ARM_GRIP_Y = 0.72f;   // grip point along the arm (0 = shoulder, 0.75 = fingertips)

    // slide-in / slide-out animation (rig space offset when fully hidden)
    private static final float SLIDE_X = 0.70f;
    private static final float SLIDE_Y = -0.25f;
    private static final float SLIDE_Z = 0.15f;
    private static final float SLIDE_SPEED = 6.5f;   // bigger = faster

    // ======== tweakables: hand motion ========
    private static final float SPRING_K = 70f;       // stiffness
    private static final float SPRING_C = 11f;       // damping (higher = calmer)
    private static final float IMPULSE = 0.045f;     // how much mouse movement pushes the hand
    private static final float MAX_OFFSET = 0.12f;

    // ======== tweakables: beam ========
    private static final int RAYS = 5;               // number of rays
    private static final int RING = 20;              // smoothness of every ray
    private static final float RAY_HALF_ANGLE = 3.6f;// thickness of one ray (degrees)
    private static final float RAY_OFFSET = 6.0f;    // how far every ray sits from the beam axis (degrees)
    private static final float RAY_ALPHA = 0.10f;    // transparency (smaller = more transparent)
    private static final float RAY_MID = 0.30f;      // where the ray is brightest (fraction of its length)
    private static final double SPIN = 0.12;         // radians per second, counter-clockwise, very slow
    private static final float BEAM_MAX = 22f;

    // ---- computed once: where the hand is and how the arm is turned
    private static final float[] HAND = computeHand();
    private static final Quaternion ARM_ALIGN = computeArmAlign();

    private static float[] computeHand() {
        PoseStack tmp = new PoseStack();
        tmp.translate(FLASH_X, FLASH_Y, FLASH_Z);
        tmp.mulPose(Vector3f.YP.rotationDegrees(FLASH_YAW));
        tmp.mulPose(Vector3f.XP.rotationDegrees(FLASH_PITCH));
        tmp.scale(FLASH_SCALE, FLASH_SCALE, FLASH_SCALE);
        Vector4f g = new Vector4f(GRIP_X, GRIP_Y, GRIP_Z, 1f);
        g.transform(tmp.last().pose());
        return new float[] {g.x(), g.y(), g.z()};
    }

    /** Turns the arm model (its long axis is +Y, shoulder -> fingers) so that it points from the shoulder to the hand. */
    private static Quaternion computeArmAlign() {
        Vector3f t = new Vector3f(-ARM_DX, -ARM_DY, -ARM_DZ);
        t.normalize();
        Vector3f axis = new Vector3f(0f, 1f, 0f);
        axis.cross(t);
        if (axis.dot(axis) < 1.0e-6f) {
            return new Quaternion(0f, 0f, 0f, 1f);
        }
        axis.normalize();
        float angle = (float) Math.acos(Mth.clamp(t.y(), -1f, 1f));
        return new Quaternion(axis, angle, false);
    }

    // ---- state
    private static float swX, swY, swVX, swVY, kick, lastYaw, lastPitch, time, appear;
    private static boolean swInit, lastOn;
    private static long lastNs;
    private static final long START_NS = System.nanoTime();
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

        // slide-in / slide-out progress
        float target = on ? 1f : 0f;
        appear += Mth.clamp(target - appear, -dt * SLIDE_SPEED, dt * SLIDE_SPEED);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent e) {
        ItemStack stack = e.getItemStack();
        if (!stack.is(SlenderFlashlightMod.FLASHLIGHT.get())) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (pl == null) return;
        e.setCanceled(true); // vanilla never draws the flashlight: we draw the arm and the flashlight ourselves

        // the main hand wins; a second flashlight in the off-hand is just hidden
        if (e.getHand() != InteractionHand.MAIN_HAND
                && pl.getMainHandItem().is(SlenderFlashlightMod.FLASHLIGHT.get())) return;

        boolean on = FlashlightItem.isOn(stack);
        updateSway(pl, e.getPartialTick(), on);

        // OFF: the flashlight and the hand are not visible at all (after the short slide-out)
        if (!on && appear <= 0.001f) return;

        PoseStack ps = e.getPoseStack();
        MultiBufferSource buf = e.getMultiBufferSource();
        int light = e.getPackedLight();
        if (on) { // the flashlight lights its own hand a bit
            light = LightTexture.pack(Math.max(LightTexture.block(light), 9), Math.max(LightTexture.sky(light), 9));
        }
        float equip = e.getEquipProgress();
        float breatheX = (float) Math.sin(time * 1.1f) * 0.002f;
        float breatheY = (float) Math.sin(time * 1.7f) * 0.003f;
        float slide = 1f - appear * appear * (3f - 2f * appear); // 0 = fully visible, 1 = hidden at the right edge

        // ---- the rig: arm + flashlight share THIS transform, so they always move together
        ps.pushPose();
        ps.translate(swX + breatheX, swY + breatheY - equip * 0.6f, 0.0);
        ps.mulPose(Vector3f.ZP.rotationDegrees(swX * -35f));
        ps.mulPose(Vector3f.XP.rotationDegrees(kick * 4f));
        ps.scale(RIG_SCALE, RIG_SCALE, RIG_SCALE);
        ps.translate(slide * SLIDE_X, slide * SLIDE_Y, slide * SLIDE_Z);

        // ---- arm: comes from the right edge of the screen and goes into the flashlight
        EntityRenderer<?> r = mc.getEntityRenderDispatcher().getRenderer(pl);
        if (r instanceof PlayerRenderer pr) {
            ps.pushPose();
            ps.translate(HAND[0], HAND[1], HAND[2]);
            ps.mulPose(ARM_ALIGN);
            ps.mulPose(Vector3f.YP.rotationDegrees(ARM_ROLL));
            ps.scale(ARM_WIDTH, ARM_LENGTH, ARM_WIDTH);
            ps.translate(0.375, -ARM_GRIP_Y, 0.0); // puts the grip point of the arm model at the origin
            pr.renderRightHand(ps, buf, light, pl);
            ps.popPose();
        }

        // ---- flashlight
        ps.pushPose();
        ps.translate(FLASH_X, FLASH_Y, FLASH_Z);
        ps.mulPose(Vector3f.YP.rotationDegrees(FLASH_YAW));
        ps.mulPose(Vector3f.XP.rotationDegrees(FLASH_PITCH + kick * 5f));
        ps.scale(FLASH_SCALE, FLASH_SCALE, FLASH_SCALE);

        Vector4f lens = new Vector4f(0f, 0f, -0.475f, 1f);
        lens.transform(ps.last().pose());
        lensView = new Vec3(lens.x(), lens.y(), lens.z());

        mc.getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.NONE, light,
                OverlayTexture.NO_OVERLAY, ps, buf, 0);
        ps.popPose();

        ps.popPose();
    }

    // ------------------------------------------------------------------ beam

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !mc.options.getCameraType().isFirstPerson()) return;

        // the same flashlight that is drawn in the hand: the main hand wins
        ItemStack held = p.getMainHandItem();
        if (!held.is(SlenderFlashlightMod.FLASHLIGHT.get())) held = p.getOffhandItem();
        if (!FlashlightItem.isOn(held)) return;

        Camera cam = e.getCamera();
        Vec3 camPos = cam.getPosition();
        Vector3f lk = cam.getLookVector(), up = cam.getUpVector(), lf = cam.getLeftVector();
        Vec3 look = new Vec3(lk.x(), lk.y(), lk.z());
        Vec3 upV = new Vec3(up.x(), up.y(), up.z());
        Vec3 right = new Vec3(-lf.x(), -lf.y(), -lf.z());

        Vec3 lv = lensView;
        Vec3 origin = right.scale(lv.x).add(upV.scale(lv.y)).add(look.scale(-lv.z)); // relative to camera

        BlockHitResult hit = mc.level.clip(new ClipContext(camPos, camPos.add(look.scale(BEAM_MAX)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double dist = hit.getType() == HitResult.Type.MISS ? BEAM_MAX : camPos.distanceTo(hit.getLocation());
        float target = (float) Mth.clamp(dist - origin.dot(look) + 0.1, 1.0, BEAM_MAX);
        beamLen += (target - beamLen) * 0.25f;

        // (u, v) = (right, up): the angle grows from right to up, which is counter-clockwise for the player
        drawBeam(e.getPoseStack(), origin, look, right, upV, beamLen);
    }

    private static float pulse(int i, double t) {
        return (float) (0.88 + 0.12 * Math.sin(t * 0.8 + i * 1.7));
    }

    private static Vec3 ring(Vec3 p, Vec3 q, double a, double radius) {
        return p.scale(Math.cos(a) * radius).add(q.scale(Math.sin(a) * radius));
    }

    private static void tri(BufferBuilder bb, Matrix4f m, Vec3 a, float aa, Vec3 b, float ba, Vec3 c, float ca) {
        bb.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(1f, 0.96f, 0.84f, aa).endVertex();
        bb.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(1f, 0.96f, 0.84f, ba).endVertex();
        bb.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(1f, 0.96f, 0.84f, ca).endVertex();
    }

    /** One soft ray: a thin cone that fades in from the lens, is brightest at RAY_MID and fades out at the end. */
    private static void drawRay(BufferBuilder bb, Matrix4f m, Vec3 o, Vec3 dir, Vec3 helper, float len, float alpha) {
        Vec3 p = dir.cross(helper).normalize();
        Vec3 q = dir.cross(p);
        double tan = Math.tan(Math.toRadians(RAY_HALF_ANGLE));
        Vec3 mid = o.add(dir.scale(len * RAY_MID));
        Vec3 end = o.add(dir.scale(len));
        double rm = len * RAY_MID * tan;
        double re = len * tan;
        for (int k = 0; k < RING; k++) {
            double a0 = 2 * Math.PI * k / RING, a1 = 2 * Math.PI * (k + 1) / RING;
            Vec3 m0 = mid.add(ring(p, q, a0, rm));
            Vec3 m1 = mid.add(ring(p, q, a1, rm));
            Vec3 e0 = end.add(ring(p, q, a0, re));
            Vec3 e1 = end.add(ring(p, q, a1, re));
            tri(bb, m, o, alpha * 0.25f, m0, alpha, m1, alpha);
            tri(bb, m, m0, alpha, e0, 0f, e1, 0f);
            tri(bb, m, m0, alpha, e1, 0f, m1, alpha);
        }
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
        double t = (System.nanoTime() - START_NS) / 1e9;
        double spin = t * SPIN; // growing angle = counter-clockwise as the player sees it (right -> up)
        double off = Math.tan(Math.toRadians(RAY_OFFSET));

        for (int i = 0; i < RAYS; i++) {
            double phi = 2 * Math.PI * i / RAYS + spin;
            Vec3 side = u.scale(Math.cos(phi)).add(v.scale(Math.sin(phi)));
            Vec3 dir = axis.add(side.scale(off)).normalize();
            Vec3 helper = Math.abs(dir.dot(v)) > 0.95 ? u : v;
            drawRay(bb, m, o, dir, helper, len, RAY_ALPHA * pulse(i, t));
        }
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
