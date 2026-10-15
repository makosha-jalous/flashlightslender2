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
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * First-person flashlight, laid out like the reference picture (Slender: The Arrival style).
 *
 *  - OFF: nothing is drawn (no flashlight, no hand).
 *  - ON : the flashlight sits in the lower right corner, the player's skin-coloured arm comes in from the
 *         right edge, a finger holds the flashlight from below. The arm / finger / flashlight / rays are ONE rigid
 *         object (one transform), so they always move together.
 *  - Rays: 5 soft, thick, transparent "petals" around the lens, slowly turning counter-clockwise.
 *          They are drawn in the same space as the flashlight, so they are visible ALWAYS while it is on.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightClient {

    // ======== tweakables: flashlight (view space: x + right, y + up, z - forward; roughly blocks) ========
    private static final float FLASH_X = 0.937f;     // centre of the flashlight
    private static final float FLASH_Y = -0.605f;
    private static final float FLASH_Z = -1.0f;
    private static final float FLASH_SCALE = 0.794f; // size of the flashlight
    private static final float FLASH_YAW = -2.861f;  // + = lens turns left
    private static final float FLASH_PITCH = 13.275f;// + = lens turns up
    private static final float FLASH_ROLL = 0.586f;
    private static final float LENS_Z = -0.475f;     // lens position inside the flashlight model

    // ======== tweakables: skin-coloured arm (a plain rectangle of the player's skin) ========
    // start point of the arm, in flashlight-model blocks measured from the flashlight centre (+z = tail, +x = right)
    private static final double ARM_GX = 0.4546, ARM_GY = -0.1456, ARM_GZ = -0.0765;
    private static final double ARM_YAW = 32.8546;   // where the arm goes: angle in the horizontal plane (towards the camera)
    private static final double ARM_ELEV = -33.1316; // where the arm goes: up (+) / down (-)
    private static final double ARM_WIDTH = 0.2937;  // thickness of the arm
    private static final double ARM_LENGTH = 1.6;    // long enough to leave the screen

    // the finger that holds the flashlight from below
    private static final double FIN_GX = -0.0466, FIN_GY = 0.2063, FIN_GZ = 0.2015;
    private static final double FIN_YAW = 6.6132;
    private static final double FIN_ELEV = 29.0373;
    private static final double FIN_WIDTH = 0.206;
    private static final double FIN_LENGTH = 0.0672;

    // UV rectangle for the base right-arm texture on a standard 64x64 Minecraft skin.
    // Sampling the texture (instead of one pixel) preserves the player's skin, sleeve and details.
    private static final float SKIN_U0 = 40f / 64f;
    private static final float SKIN_U1 = 48f / 64f;
    private static final float SKIN_V0 = 16f / 64f;
    private static final float SKIN_V1 = 32f / 64f;

    // slide-in / slide-out animation (offset when fully hidden: the rig leaves through the right edge)
    private static final float SLIDE_X = 0.80f;
    private static final float SLIDE_Y = -0.35f;
    private static final float SLIDE_Z = 0.10f;
    private static final float SLIDE_SPEED = 6.5f;   // bigger = faster

    // ======== tweakables: hand motion ========
    private static final float SPRING_K = 70f;       // stiffness
    private static final float SPRING_C = 11f;       // damping (higher = calmer)
    private static final float IMPULSE = 0.045f;     // how much mouse movement pushes the hand
    private static final float MAX_OFFSET = 0.10f;

    // ======== tweakables: rays ========
    private static final int RAYS = 5;               // number of rays
    private static final int RING = 20;              // smoothness of every ray
    private static final float RAY_OFFSET = 28.2f;   // how far every ray is turned away from the centre line (degrees)
    private static final float RAY_HALF_ANGLE = 13.9f;// how fast a ray gets thicker (degrees)
    private static final float RAY_START_RADIUS = 0.095f; // thickness of a ray at the lens
    private static final float RAY_LENGTH = 1.4f;    // length of a ray (it fades out smoothly)
    private static final float RAY_MID = 0.35f;      // where the ray is brightest (fraction of its length)
    private static final float RAY_ALPHA = 0.10f;    // brightness (smaller = more transparent)
    private static final double RAY_PHASE = 1.771;   // start angle of the first ray (radians)
    private static final double SPIN = 0.12;         // radians per second, counter-clockwise, very slow

    // ---- computed once from the numbers above (everything is in "rig space", same space as the flashlight)
    private static final Vec3 LENS = toRig(0.0, 0.0, LENS_Z);
    private static final Box ARM = buildBox(toRig(ARM_GX, ARM_GY, ARM_GZ), dirOf(ARM_YAW, ARM_ELEV), ARM_LENGTH, ARM_WIDTH);
    private static final Box FINGER = buildBox(toRig(FIN_GX, FIN_GY, FIN_GZ), dirOf(FIN_YAW, FIN_ELEV), FIN_LENGTH, FIN_WIDTH);

    /** The flashlight transform (position, aim, size). */
    private static void applyFlashTransform(PoseStack ps) {
        ps.translate(FLASH_X, FLASH_Y, FLASH_Z);
        ps.mulPose(Vector3f.YP.rotationDegrees(FLASH_YAW));
        ps.mulPose(Vector3f.XP.rotationDegrees(FLASH_PITCH));
        ps.mulPose(Vector3f.ZP.rotationDegrees(FLASH_ROLL));
        ps.scale(FLASH_SCALE, FLASH_SCALE, FLASH_SCALE);
    }

    /** A point given in flashlight-model blocks -> rig space. */
    private static Vec3 toRig(double x, double y, double z) {
        PoseStack tmp = new PoseStack();
        applyFlashTransform(tmp);
        Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1f);
        v.transform(tmp.last().pose());
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static Vec3 dirOf(double yawDeg, double elevDeg) {
        double yaw = Math.toRadians(yawDeg), el = Math.toRadians(elevDeg);
        return new Vec3(Math.cos(el) * Math.cos(yaw), Math.sin(el), Math.cos(el) * Math.sin(yaw));
    }

    /** A rectangular box (square cross-section) made of 6 quads, wound counter-clockwise when seen from outside. */
    private static final class Box {
        final float[][] verts = new float[6][12];
        final float[][] normals = new float[6][3];
    }

    private static Box buildBox(Vec3 start, Vec3 dirIn, double length, double width) {
        Vec3 d = dirIn.normalize();
        Vec3 helper = Math.abs(d.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = d.cross(helper).normalize();
        Vec3 v = d.cross(u);
        Vec3[] c = new Vec3[8];
        int n = 0;
        for (int s = 0; s < 2; s++) {
            for (int a = -1; a <= 1; a += 2) {
                for (int b = -1; b <= 1; b += 2) {
                    c[n++] = start.add(d.scale(s * length)).add(u.scale(a * width / 2)).add(v.scale(b * width / 2));
                }
            }
        }
        Vec3 centre = start.add(d.scale(length / 2));
        int[][] faces = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        Box box = new Box();
        for (int f = 0; f < 6; f++) {
            Vec3 p0 = c[faces[f][0]], p1 = c[faces[f][1]], p2 = c[faces[f][2]], p3 = c[faces[f][3]];
            Vec3 nrm = p1.subtract(p0).cross(p2.subtract(p1)).normalize();
            Vec3 faceCentre = p0.add(p1).add(p2).add(p3).scale(0.25);
            if (nrm.dot(faceCentre.subtract(centre)) < 0) { // wound the wrong way: flip
                Vec3 t = p1; p1 = p3; p3 = t;
                nrm = nrm.scale(-1);
            }
            Vec3[] q = {p0, p1, p2, p3};
            for (int i = 0; i < 4; i++) {
                box.verts[f][i * 3] = (float) q[i].x;
                box.verts[f][i * 3 + 1] = (float) q[i].y;
                box.verts[f][i * 3 + 2] = (float) q[i].z;
            }
            box.normals[f][0] = (float) nrm.x;
            box.normals[f][1] = (float) nrm.y;
            box.normals[f][2] = (float) nrm.z;
        }
        return box;
    }

    // ---- state
    private static float swX, swY, swVX, swVY, kick, lastYaw, lastPitch, time, appear;
    private static boolean swInit, lastOn;
    private static long lastNs;
    private static final long START_NS = System.nanoTime();

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
        e.setCanceled(true); // vanilla never draws the flashlight: we draw everything ourselves

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
            light = LightTexture.pack(Math.max(LightTexture.block(light), 11), Math.max(LightTexture.sky(light), 11));
        }
        float equip = e.getEquipProgress();
        float breatheX = (float) Math.sin(time * 1.1f) * 0.002f;
        float breatheY = (float) Math.sin(time * 1.7f) * 0.003f;
        float ease = appear * appear * (3f - 2f * appear);  // 0 = hidden, 1 = fully visible
        float slide = 1f - ease;

        // ---- the rig: rays + flashlight + arm share THIS transform, so they always move together
        ps.pushPose();
        ps.translate(swX + breatheX, swY + breatheY - equip * 0.6f, 0.0);
        ps.mulPose(Vector3f.ZP.rotationDegrees(swX * -35f));
        ps.mulPose(Vector3f.XP.rotationDegrees(kick * 4f));
        ps.translate(slide * SLIDE_X, slide * SLIDE_Y, slide * SLIDE_Z);

        // ---- 1) rays (behind the flashlight)
        if (on) {
            drawRays(ps.last().pose(), ease);
        }

        // ---- 2) flashlight
        ps.pushPose();
        applyFlashTransform(ps);
        mc.getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.NONE, light,
                OverlayTexture.NO_OVERLAY, ps, buf, 0);
        ps.popPose();

        // ---- 3) arm and finger: use the player's actual skin texture, drawn on top of the flashlight.
        // Flush the flashlight batch first. Do NOT clear the depth buffer here: that affects the whole scene
        // and causes the world to look smeared/incorrect while the flashlight is on.
        if (buf instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }
        VertexConsumer vc = buf.getBuffer(RenderType.entitySolid(pl.getSkinTextureLocation()));
        drawBox(vc, ps, ARM, light);
        drawBox(vc, ps, FINGER, light);

        ps.popPose();
    }

    private static void drawBox(VertexConsumer vc, PoseStack ps, Box box, int light) {
        Matrix4f m = ps.last().pose();
        Matrix3f nm = ps.last().normal();
        for (int f = 0; f < 6; f++) {
            float[] v = box.verts[f];
            float[] n = box.normals[f];
            for (int i = 0; i < 4; i++) {
                float u = (i == 0 || i == 3) ? SKIN_U0 : SKIN_U1;
                float texV = (i < 2) ? SKIN_V0 : SKIN_V1;
                vc.vertex(m, v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
                        .color(1f, 1f, 1f, 1f)
                        .uv(u, texV)
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(light)
                        .normal(nm, n[0], n[1], n[2])
                        .endVertex();
            }
        }
    }

    // ------------------------------------------------------------------ rays

    private static float pulse(int i, double t) {
        return (float) (0.9 + 0.1 * Math.sin(t * 0.8 + i * 1.7));
    }

    private static void tri(BufferBuilder bb, Matrix4f m, Vec3 a, float aa, Vec3 b, float ba, Vec3 c, float ca) {
        bb.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(1f, 0.96f, 0.84f, aa).endVertex();
        bb.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(1f, 0.96f, 0.84f, ba).endVertex();
        bb.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(1f, 0.96f, 0.84f, ca).endVertex();
    }

    /** One soft thick ray: starts at the lens, is brightest at RAY_MID, fades out smoothly at the end. */
    private static void drawRay(BufferBuilder bb, Matrix4f m, Vec3 dir, float alpha) {
        Vec3 helper = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 p = dir.cross(helper).normalize();
        Vec3 q = dir.cross(p);
        double tan = Math.tan(Math.toRadians(RAY_HALF_ANGLE));
        double[] at = {0.0, RAY_MID, 1.0};
        float[] al = {alpha * 0.3f, alpha, 0f};
        Vec3[][] ring = new Vec3[3][RING];
        for (int s = 0; s < 3; s++) {
            Vec3 centre = LENS.add(dir.scale(RAY_LENGTH * at[s]));
            double radius = RAY_START_RADIUS + RAY_LENGTH * at[s] * tan;
            for (int k = 0; k < RING; k++) {
                double a = 2 * Math.PI * k / RING;
                ring[s][k] = centre.add(p.scale(Math.cos(a) * radius)).add(q.scale(Math.sin(a) * radius));
            }
        }
        for (int s = 0; s < 2; s++) {
            for (int k = 0; k < RING; k++) {
                int k2 = (k + 1) % RING;
                tri(bb, m, ring[s][k], al[s], ring[s + 1][k], al[s + 1], ring[s + 1][k2], al[s + 1]);
                tri(bb, m, ring[s][k], al[s], ring[s + 1][k2], al[s + 1], ring[s][k2], al[s]);
            }
        }
    }

    private static void drawRays(Matrix4f m, float visible) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        double t = (System.nanoTime() - START_NS) / 1e9;
        double spin = RAY_PHASE + t * SPIN; // growing angle = right -> up = counter-clockwise for the player
        double off = Math.tan(Math.toRadians(RAY_OFFSET));
        for (int i = 0; i < RAYS; i++) {
            double phi = 2 * Math.PI * i / RAYS + spin;
            Vec3 dir = new Vec3(off * Math.cos(phi), off * Math.sin(phi), -1.0).normalize();
            drawRay(bb, m, dir, RAY_ALPHA * visible * pulse(i, t));
        }
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
