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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * First-person flashlight with a Minecraft-style box hand, walking/running arc and idle breathing.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightClient {

    // ---------- положение фонарика (как было) ----------
    // fitted to the screenshot of the original game (head, thumb and forearm line up with it)
    private static final float FLASH_X = 0.793f;
    private static final float FLASH_Y = -0.569f;
    private static final float FLASH_Z = -1.0f;
    private static final float FLASH_SCALE = 0.815f;
    private static final float FLASH_YAW = 16.25f;
    private static final float FLASH_PITCH = 0.13f;
    private static final float FLASH_ROLL = 23.04f;
    /** running: how far the head swings from side to side (degrees about the wrist). Minus sign = the other direction. */
    private static final float RUN_HEAD_YAW = 13f;
    private static final float RUN_HEAD_ROLL = 6f;
    private static final float LENS_Z = -0.475f;

    // ---------- РУКА ИЗ МОДЕЛИ (Blockbench) ----------
    /** the hand as an ordinary item model: assets/slenderflashlight/models/item/flashlight_hand.json (open it in Blockbench). */
    public static final ResourceLocation HAND_MODEL = new ResourceLocation(SlenderFlashlightMod.ID, "item/flashlight_hand");
    /** true = the hand comes from the model file; false (or the file is missing) = the old hand made of two boxes below. */
    private static final boolean HAND_FROM_MODEL = true;

    // ---------- РУКА: цвет и размеры (можно крутить) ----------
    // Цвет кожи (бежевый как на твоём рисунке). Значения 0..1.
    private static final float HAND_R = 0.93f;
    private static final float HAND_G = 0.80f;
    private static final float HAND_B = 0.68f;
    // Верхний прямоугольник (рука): откуда начинается на фонарике, куда идёт, толщина и длина.
    private static final double ARM_GX = 0.20, ARM_GY = -0.05, ARM_GZ = 0.10;
    private static final double ARM_YAW = 20.0, ARM_ELEV = -15.0;
    private static final double ARM_WIDTH = 0.26;
    private static final double ARM_LENGTH = 1.5;
    // Нижний маленький прямоугольник (три пальца под фонариком), координаты в модели фонарика.
    // Центр и ПОЛОВИНЫ размеров по X (ширина), Y (толщина), Z (длина вдоль фонарика).
    private static final double FING_CX = 0.0, FING_CY = -0.19, FING_CZ = 0.04;
    private static final double FING_HX = 0.17, FING_HY = 0.05, FING_HZ = 0.11;

    // ---------- ходьба / бег ----------
    // Walking (with the camera: Slowness II): the hand goes along a wave - side to side, with a snake-like up/down wave
    // (1.5 waves from one end to the other). Running (Slowness I): a wide swing from side to side along an UPPER arc.
    // The speed of the steps follows the REAL speed of the player (so Slowness 1 / 2 changes it by itself).
    private static final float WALK_AMP_X = 0.036f;
    private static final float RUN_AMP_X = 0.070f;
    private static final float WALK_AMP_Y = 0.020f;
    private static final float RUN_AMP_Y = 0.040f;
    /** One full swing (right and back) = two steps. Walking: a step every 0.8 s (a swing 1.6 s); running: a step every 0.6 s (a swing 1.2 s). */
    private static final float WALK_RATE = 6.2831853f / 1.6f;
    private static final float RUN_RATE = 6.2831853f / 1.2f;

    // ---------- плавное «дыхание» в покое ----------
    private static final float IDLE_X = 0.013f;
    private static final float IDLE_Y = 0.016f;

    private static final float SLIDE_X = 0.80f;
    private static final float SLIDE_Y = -0.35f;
    private static final float SLIDE_Z = 0.10f;
    private static final float SLIDE_SPEED = 6.5f;

    private static final float SPRING_K = 70f;
    private static final float SPRING_C = 11f;
    private static final float IMPULSE = 0.045f;
    private static final float MAX_OFFSET = 0.10f;

    private static final int RAYS = 5;
    private static final int RING = 20;
    private static final float RAY_OFFSET = 28.2f;
    private static final float RAY_HALF_ANGLE = 13.9f;
    private static final float RAY_START_RADIUS = 0.095f;
    private static final float RAY_LENGTH = 1.4f;
    private static final float RAY_MID = 0.35f;
    private static final float RAY_ALPHA = 0.10f;
    private static final double RAY_PHASE = 1.771;
    private static final double SPIN = 0.12;

    private static final Vec3 LENS = toRig(0.0, 0.0, LENS_Z);
    /** the beam leaves the HEAD and aims at the point straight ahead of the camera (the crosshair), like in the original. */
    private static final double AIM_DIST = 6.0;
    private static final Vec3 AXIS = new Vec3(0.0, 0.0, -AIM_DIST).subtract(LENS).normalize();
    /** running: the cone turns this much MORE than the head (degrees), so it sweeps wider than the flashlight itself. */
    private static final float RUN_CONE_YAW = 7f;
    /** the picture of the cone's cross-section (used in the long-range mode). */
    private static final ResourceLocation BEAM_TEX = new ResourceLocation(SlenderFlashlightMod.ID, "textures/misc/flashlight_beam.png");
    /** the pivot of the running swing: the wrist, behind the grip (coordinates in the flashlight model). */
    private static final Vec3 WRIST = toRig(0.15, 0.05, 0.2);

    // ---------- сетка руки ----------
    private static final Vec3 LIGHT_DIR = new Vec3(-0.35, 0.75, 0.55).normalize();
    /** Вершины: по 4 числа (x, y, z, затенение). */
    private static final float[] ARM = buildArm();
    private static final float[] FINGERS = buildFingers();

    private static void applyFlashTransform(PoseStack ps) {
        ps.translate(FLASH_X, FLASH_Y, FLASH_Z);
        ps.mulPose(Vector3f.YP.rotationDegrees(FLASH_YAW));
        ps.mulPose(Vector3f.XP.rotationDegrees(FLASH_PITCH));
        ps.mulPose(Vector3f.ZP.rotationDegrees(FLASH_ROLL));
        ps.scale(FLASH_SCALE, FLASH_SCALE, FLASH_SCALE);
    }

    private static Vec3 toRig(double x, double y, double z) {
        PoseStack tmp = new PoseStack();
        applyFlashTransform(tmp);
        com.mojang.math.Vector4f v = new com.mojang.math.Vector4f((float) x, (float) y, (float) z, 1f);
        v.transform(tmp.last().pose());
        return new Vec3(v.x(), v.y(), v.z());
    }

    // =====================================================================
    //  Рука из двух прямоугольников (в стиле Minecraft)
    // =====================================================================

    private static Vec3 dirOf(double yawDeg, double elevDeg) {
        double yaw = Math.toRadians(yawDeg), el = Math.toRadians(elevDeg);
        return new Vec3(Math.cos(el) * Math.cos(yaw), Math.sin(el), Math.cos(el) * Math.sin(yaw));
    }

    /** Длинный прямоугольник сверху. Начало спрятано внутри фонарика, чтобы не было зазоров. */
    private static float[] buildArm() {
        List<float[]> out = new ArrayList<>();
        Vec3 d = dirOf(ARM_YAW, ARM_ELEV).normalize();
        double hide = 0.12;
        Vec3 start = toRig(ARM_GX, ARM_GY, ARM_GZ).subtract(d.scale(hide));
        double length = ARM_LENGTH + hide;
        Vec3 helper = Math.abs(d.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 un = d.cross(helper).normalize();
        Vec3 u = un.scale(ARM_WIDTH / 2);
        Vec3 v = d.cross(un).normalize().scale(ARM_WIDTH / 2);
        addBox(out, start.add(d.scale(length / 2)), u, v, d.scale(length / 2));
        return flatten(out);
    }

    /** Маленький прямоугольник под фонариком («три пальца»). */
    private static float[] buildFingers() {
        List<float[]> out = new ArrayList<>();
        addBox(out, new Vec3(FING_CX, FING_CY, FING_CZ),
                new Vec3(FING_HX, 0, 0), new Vec3(0, FING_HY, 0), new Vec3(0, 0, FING_HZ));
        return flatten(out);
    }

    private static float[] flatten(List<float[]> out) {
        float[] arr = new float[out.size() * 4];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, arr, i * 4, 4);
        return arr;
    }

    /** Коробка: центр c и три «полу-оси» ax, ay, az. */
    private static void addBox(List<float[]> out, Vec3 c, Vec3 ax, Vec3 ay, Vec3 az) {
        Vec3[] axes = {ax, ay, az};
        for (int i = 0; i < 3; i++) {
            Vec3 a = axes[i], b = axes[(i + 1) % 3], d = axes[(i + 2) % 3];
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Vec3 fc = c.add(a.scale(sgn));
                Vec3 n = a.scale(sgn).normalize();
                float shade = 0.72f + 0.28f * (float) Math.max(0.0, n.dot(LIGHT_DIR));
                Vec3 p0 = fc.add(b).add(d), p1 = fc.subtract(b).add(d);
                Vec3 p2 = fc.subtract(b).subtract(d), p3 = fc.add(b).subtract(d);
                pushTri(out, p0, p1, p2, shade);
                pushTri(out, p0, p2, p3, shade);
            }
        }
    }

    private static void pushTri(List<float[]> out, Vec3 a, Vec3 b, Vec3 c, float shade) {
        out.add(new float[]{(float) a.x, (float) a.y, (float) a.z, shade});
        out.add(new float[]{(float) b.x, (float) b.y, (float) b.z, shade});
        out.add(new float[]{(float) c.x, (float) c.y, (float) c.z, shade});
    }

    private static void drawMesh(float[] d, Matrix4f m, float lightFactor) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < d.length; i += 4) {
            float sh = d[i + 3] * lightFactor;
            bb.vertex(m, d[i], d[i + 1], d[i + 2]).color(HAND_R * sh, HAND_G * sh, HAND_B * sh, 1f).endVertex();
        }
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.enableCull();
    }

    // =====================================================================
    //  Анимация
    // =====================================================================

    private static float swX, swY, swVX, swVY, kick, lastYaw, lastPitch, time, appear;
    private static float moveAmt, runAmt, phase;
    private static boolean swInit, lastOn;
    private static long lastNs;
    private static final long START_NS = System.nanoTime();

    private static void updateSway(LocalPlayer p, float pt, boolean on) {
        long now = System.nanoTime();
        float dt = swInit ? Math.min(0.05f, (now - lastNs) / 1e9f) : 0.016f;
        float dtGait = swInit ? Math.min(0.25f, (now - lastNs) / 1e9f) : 0.016f; // the step phase never slows down at low fps
        lastNs = now;
        float yaw = p.getViewYRot(pt), pitch = p.getViewXRot(pt);
        if (!swInit) { lastYaw = yaw; lastPitch = pitch; lastOn = on; swInit = true; }
        float dy = Mth.wrapDegrees(yaw - lastYaw), dp = pitch - lastPitch;
        lastYaw = yaw; lastPitch = pitch;

        swVX += -dy * IMPULSE;
        swVY += dp * IMPULSE;
        // small fixed steps: the spring stays smooth at any frame rate, and the limit is soft (no sudden stop)
        int steps = Math.max(1, (int) Math.ceil(dt / 0.008f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            swVX += (-SPRING_K * swX - SPRING_C * swVX) * h;
            swVY += (-SPRING_K * swY - SPRING_C * swVY) * h;
            swX += swVX * h;
            swY += swVY * h;
        }
        swX = (float) (MAX_OFFSET * Math.tanh(swX / MAX_OFFSET));
        swY = (float) (MAX_OFFSET * Math.tanh(swY / MAX_OFFSET));

        if (on != lastOn) { kick = 1f; lastOn = on; }
        kick *= (float) Math.exp(-dt * 10f);
        time += dt;

        float target = on ? 1f : 0f;
        appear += Mth.clamp(target - appear, -dt * SLIDE_SPEED, dt * SLIDE_SPEED);

        // Ходьба / бег: амплитуда и скорость плавно нарастают и затухают
        double speed = p.getDeltaMovement().horizontalDistance();
        boolean moving = p.isOnGround() && speed > 0.04;
        boolean running = moving && p.isSprinting();
        float k = 1f - (float) Math.exp(-dt * 7f);
        moveAmt += ((moving ? 1f : 0f) - moveAmt) * k;
        runAmt += ((running ? 1f : 0f) - runAmt) * k;
        // fixed pace (the camera always gives Slowness II walking / Slowness I running): 1.5 s and 0.5 s per swing
        if (moving) phase += dtGait * Mth.lerp(runAmt, WALK_RATE, RUN_RATE);
        if (phase > 6.2831853f) phase -= 6.2831853f;
        gaitNs = now;
    }

    private static long gaitNs, lastHeldNs;
    private static float heldTime;

    /**
     * Read by the camera mod (by reflection) so the view and the hand swing in step:
     * {phase, how much the player walks 0..1, how much he runs 0..1, 1 if this is live}.
     */
    public static float[] gaitState() {
        boolean live = (System.nanoTime() - gaitNs) < 250_000_000L;
        return new float[]{phase, moveAmt, runAmt, live ? 1f : 0f};
    }

    /** Убирает ванильное покачивание руки (оно «перевёрнутой дугой»), чтобы остался только наш ход. */
    private static void cancelVanillaBob(PoseStack ps, Player pl, float pt) {
        float f = pl.walkDist - pl.walkDistO;
        float f1 = -(pl.walkDist + f * pt);
        float f2 = Mth.lerp(pt, pl.oBob, pl.bob);
        float pi = (float) Math.PI;
        float tx = Mth.sin(f1 * pi) * f2 * 0.5f;
        float ty = -Math.abs(Mth.cos(f1 * pi) * f2);
        float rz = Mth.sin(f1 * pi) * f2 * 3.0f;
        float rx = Math.abs(Mth.cos(f1 * pi - 0.2f) * f2) * 5.0f;
        ps.mulPose(Vector3f.XP.rotationDegrees(-rx));
        ps.mulPose(Vector3f.ZP.rotationDegrees(-rz));
        ps.translate(-tx, -ty, 0.0);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent e) {
        ItemStack stack = e.getItemStack();
        if (!(stack.getItem() instanceof FlashlightItem)) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (pl == null) return;
        e.setCanceled(true);

        if (e.getHand() != InteractionHand.MAIN_HAND
                && pl.getMainHandItem().getItem() instanceof FlashlightItem) return;

        boolean on = FlashlightItem.isOn(stack);
        updateSway(pl, e.getPartialTick(), on);
        long nowH = System.nanoTime();
        if (nowH - lastHeldNs > 200_000_000L) heldTime = 0f;
        heldTime += Math.min(0.05f, (nowH - lastHeldNs) / 1e9f);
        lastHeldNs = nowH;

        if (!on && appear <= 0.001f) return;

        PoseStack ps = e.getPoseStack();
        MultiBufferSource buf = e.getMultiBufferSource();
        int light = e.getPackedLight();
        if (on) {
            light = LightTexture.pack(Math.max(LightTexture.block(light), 11), Math.max(LightTexture.sky(light), 11));
        }
        float brightness = Math.max(LightTexture.block(light), LightTexture.sky(light)) / 15f;
        float lightFactor = Mth.clamp(0.55f + 0.45f * brightness, 0.55f, 1f);

        // only the real equip animation counts: pressing the left mouse button (the "hit") must not move the hand
        float equip = heldTime < 0.6f ? e.getEquipProgress() : 0f;
        float ease = appear * appear * (3f - 2f * appear);
        float slide = 1f - ease;

        // Ход: дуга выпуклая ВВЕРХ (∩). x = sin(фаза), y = cos(2*фаза) = 1 - 2x²
        float wx = (float) Math.sin(phase) * Mth.lerp(runAmt, WALK_AMP_X, RUN_AMP_X) * moveAmt;
        // walking and running: an UPPER arc (the middle is the highest point), smaller when walking
        float waveY = (float) Math.cos(2 * phase) * WALK_AMP_Y;
        float arcY = (float) Math.cos(2 * phase) * RUN_AMP_Y;
        float wy = Mth.lerp(runAmt, waveY, arcY) * moveAmt;
        float wRoll = (float) Math.sin(phase) * Mth.lerp(runAmt, 2.0f, 3.4f) * moveAmt;
        float wPitch = Mth.lerp(runAmt, waveY * 20f, arcY * 20f) * moveAmt;

        // Покой: мягкое волнообразное «дыхание» (слабее при ходьбе)
        float idle = 1f - moveAmt * 0.6f;
        float bx = ((float) Math.sin(time * 0.9f) * IDLE_X + (float) Math.sin(time * 0.37f + 1.3f) * IDLE_X * 0.5f) * idle;
        float by = ((float) Math.sin(time * 1.25f) * IDLE_Y + (float) Math.sin(time * 0.55f) * IDLE_Y * 0.45f) * idle;
        float bRoll = (float) Math.sin(time * 0.8f) * 1.3f * idle;
        float bPitch = (float) Math.sin(time * 1.25f + 0.8f) * 1.0f * idle;

        ps.pushPose();
        if (mc.options.bobView().get()) {
            cancelVanillaBob(ps, pl, e.getPartialTick());
        }
        // long-range light: the hand and the flashlight stand still
        // the hand keeps its normal motion in the long-range mode too
        ps.translate(swX + bx + wx, swY + by + wy - equip * 0.6f, 0.0);
        ps.mulPose(Vector3f.ZP.rotationDegrees(swX * -35f + bRoll + wRoll));
        ps.mulPose(Vector3f.XP.rotationDegrees(kick * 4f + bPitch + wPitch));
        // RUNNING only (walking stays as it was): the whole rig also turns about the WRIST, so the HEAD of the flashlight
        // swings wide from side to side, and the cone of light (drawn below with the same matrix) follows it
        float headK = runAmt * moveAmt;
        float sw = (float) Math.sin(phase) * headK;
        if (headK > 0.001f) {
            ps.translate(WRIST.x, WRIST.y, WRIST.z);
            ps.mulPose(Vector3f.YP.rotationDegrees(-sw * RUN_HEAD_YAW));
            ps.mulPose(Vector3f.ZP.rotationDegrees(sw * RUN_HEAD_ROLL));
            ps.translate(-WRIST.x, -WRIST.y, -WRIST.z);
        }
        ps.translate(slide * SLIDE_X, slide * SLIDE_Y, slide * SLIDE_Z);

        if (on) {
            // the cone is fixed to the head: it turns with it (same matrix) and, running, sweeps a little wider than the head
            ps.pushPose();
            ps.translate(LENS.x, LENS.y, LENS.z);
            ps.mulPose(Vector3f.YP.rotationDegrees(-sw * RUN_CONE_YAW));
            ps.translate(-LENS.x, -LENS.y, -LENS.z);
            drawRays(ps.last().pose(), ease);
            ps.popPose();
        }

        // Фонарик и рука — в одной системе координат, поэтому двигаются синхронно
        ps.pushPose();
        applyFlashTransform(ps);
        mc.getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.NONE, light,
                OverlayTexture.NO_OVERLAY, ps, buf, 0);
        if (buf instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }
        // the hand: from the model (everything, arm and fingers, in the coordinates of the flashlight model), or the old boxes
        BakedModel handModel = HAND_FROM_MODEL ? handModel(mc) : null;
        if (handModel != null) {
            mc.getItemRenderer().render(stack, ItemTransforms.TransformType.NONE, false, ps, buf, light,
                    OverlayTexture.NO_OVERLAY, handModel);
            if (buf instanceof MultiBufferSource.BufferSource bs2) {
                bs2.endBatch();
            }
        } else {
            drawMesh(FINGERS, ps.last().pose(), lightFactor);
        }
        ps.popPose();

        if (handModel == null) drawMesh(ARM, ps.last().pose(), lightFactor);

        ps.popPose();
    }

    /** the baked hand model, or null when the file is missing (then the old boxes are used). */
    private static BakedModel handModel(Minecraft mc) {
        BakedModel m = mc.getModelManager().getModel(HAND_MODEL);
        return m == mc.getModelManager().getMissingModel() ? null : m;
    }

    private static float pulse(int i, double t) {
        return (float) (0.9 + 0.1 * Math.sin(t * 0.8 + i * 1.7));
    }

    private static void tri(BufferBuilder bb, Matrix4f m, Vec3 a, float aa, Vec3 b, float ba, Vec3 c, float ca) {
        bb.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(1f, 0.96f, 0.84f, aa).endVertex();
        bb.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(1f, 0.96f, 0.84f, ba).endVertex();
        bb.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(1f, 0.96f, 0.84f, ca).endVertex();
    }

    private static void drawRay(BufferBuilder bb, Matrix4f m, Vec3 dir, float alpha) {
        Vec3 helper = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 p = dir.cross(helper).normalize();
        Vec3 q = dir.cross(p);
        float foc = FlashlightFocus.amount();
        double tan = Math.tan(Math.toRadians(Mth.lerp(foc, RAY_HALF_ANGLE, 4.5f)));
        float rayLen = Mth.lerp(foc, RAY_LENGTH, 2.8f);
        double[] at = {0.0, RAY_MID, 1.0};
        float[] al = {alpha * 0.3f, alpha, 0f};
        Vec3[][] ring = new Vec3[3][RING];
        for (int s = 0; s < 3; s++) {
            Vec3 centre = LENS.add(dir.scale(rayLen * at[s]));
            double radius = RAY_START_RADIUS + rayLen * at[s] * tan;
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

    /** Long-range mode: the picture of the cone's cross-section, a few slices along the beam (smaller near the head, bigger away from it). */
    private static void drawBeamDiscs(Matrix4f m, float amount) {
        Vec3 helper = Math.abs(AXIS.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 p = AXIS.cross(helper).normalize();
        Vec3 q = AXIS.cross(p);
        float foc = FlashlightFocus.amount();
        double fullTan = Math.tan(Math.toRadians(Mth.lerp(foc, RAY_HALF_ANGLE, 4.5f) + Mth.lerp(foc, RAY_OFFSET, 7.0f)));
        float rayLen = Mth.lerp(foc, RAY_LENGTH, 2.8f);
        float[] at = {0.35f, 0.60f, 0.82f, 1.0f};
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        RenderSystem.setShaderTexture(0, BEAM_TEX);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        for (float f : at) {
            Vec3 c = LENS.add(AXIS.scale(rayLen * f));
            double r = RAY_START_RADIUS + rayLen * f * fullTan;
            float a = Mth.clamp(amount * 0.22f * (0.6f + 0.4f * f), 0f, 1f);
            Vec3 pr = p.scale(r), qr = q.scale(r);
            Vec3 v0 = c.subtract(pr).subtract(qr), v1 = c.add(pr).subtract(qr), v2 = c.add(pr).add(qr), v3 = c.subtract(pr).add(qr);
            bb.vertex(m, (float) v0.x, (float) v0.y, (float) v0.z).color(1f, 0.96f, 0.84f, a).uv(0f, 1f).endVertex();
            bb.vertex(m, (float) v1.x, (float) v1.y, (float) v1.z).color(1f, 0.96f, 0.84f, a).uv(1f, 1f).endVertex();
            bb.vertex(m, (float) v2.x, (float) v2.y, (float) v2.z).color(1f, 0.96f, 0.84f, a).uv(1f, 0f).endVertex();
            bb.vertex(m, (float) v3.x, (float) v3.y, (float) v3.z).color(1f, 0.96f, 0.84f, a).uv(0f, 0f).endVertex();
        }
        BufferUploader.drawWithShader(bb.end());
    }

    private static void drawRays(Matrix4f m, float visible) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        double t = (System.nanoTime() - START_NS) / 1e9;
        double spin = RAY_PHASE + t * SPIN;
        double off = Math.tan(Math.toRadians(Mth.lerp(FlashlightFocus.amount(), RAY_OFFSET, 7.0f)));
        for (int i = 0; i < RAYS; i++) {
            double phi = 2 * Math.PI * i / RAYS + spin;
            Vec3 helper = Math.abs(AXIS.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 ap = AXIS.cross(helper).normalize();
            Vec3 aq = AXIS.cross(ap);
            Vec3 dir = AXIS.add(ap.scale(off * Math.cos(phi))).add(aq.scale(off * Math.sin(phi))).normalize();
            drawRay(bb, m, dir, RAY_ALPHA * visible * pulse(i, t) * (1f + 0.6f * FlashlightFocus.amount()));
        }
        BufferUploader.drawWithShader(bb.end());

        float foc = FlashlightFocus.amount();
        if (foc > 0.01f) {
            drawBeamDiscs(m, visible * foc);
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(515);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
