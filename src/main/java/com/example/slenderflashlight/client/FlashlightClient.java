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
    // fitted by computer to the screenshot of the original game for flashlight_mesh_with_hand5 (rough: tune these 6 numbers by eye in the game)
    private static float FLASH_X = 1.9155f;
    private static float FLASH_Y = -0.9014f;
    private static float FLASH_Z = -0.8901f;
    private static float FLASH_SCALE = 1.9751f;
    private static float FLASH_YAW = -31.3521f;
    private static float FLASH_PITCH = -7.6056f;
    private static float FLASH_ROLL = 18.5916f;
    /** running: how far the head swings from side to side (degrees about the wrist). Minus sign = the other direction. */
    /** how strong the TURNS of the flashlight head are (left-right, up-down, roll): the springs and the walking / running swing. 1 = as before. */
    private static final float HEAD_TURN_SCALE = 0.8f;
    private static final float RUN_HEAD_YAW = 13f;
    private static final float RUN_HEAD_ROLL = 6f;
    /** the same swing of the head of the flashlight when WALKING: as much smaller as the old walking arc was smaller than the running one (0.036 / 0.070). */
    private static final float WALK_HEAD_YAW = RUN_HEAD_YAW * 0.036f / 0.070f;
    private static final float WALK_HEAD_ROLL = RUN_HEAD_ROLL * 0.036f / 0.070f;
    /** the old arc of the hand (kept as it was): sideways and the height of the upper arc, walking / running. */
    private static final float WALK_AMP_X = 0.036f;
    private static final float RUN_AMP_X = 0.070f;
    private static final float WALK_AMP_Y = 0.020f;
    private static final float RUN_AMP_Y = 0.040f;
    private static final float LENS_Z = -0.475f;
    /** the lens (the head) and the wrist in the coordinates of the NEW flashlight+hand model (they move with it). */
    private static final float[] LENS_LOCAL = {-0.5979f, 0.3845f, -0.9760f};

    /** the pose that is written in the code (the tuner's "Reset" goes back to it): x, y, z, scale, yaw, pitch, roll. */
    private static final float[] DEFAULT_POSE = {FLASH_X, FLASH_Y, FLASH_Z, FLASH_SCALE, FLASH_YAW, FLASH_PITCH, FLASH_ROLL};
    public static float[] defaultPose() { return DEFAULT_POSE.clone(); }
    public static float[] getPose() { return new float[]{FLASH_X, FLASH_Y, FLASH_Z, FLASH_SCALE, FLASH_YAW, FLASH_PITCH, FLASH_ROLL}; }
    /** used by FlashlightTuner: changes the pose of the flashlight with the hand and moves the lens / wrist points with it. */
    public static void setPose(float[] p) {
        FLASH_X = p[0]; FLASH_Y = p[1]; FLASH_Z = p[2]; FLASH_SCALE = p[3];
        FLASH_YAW = p[4]; FLASH_PITCH = p[5]; FLASH_ROLL = p[6];
        LENS = toRig(LENS_LOCAL[0], LENS_LOCAL[1], LENS_LOCAL[2]);
    }

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

    private static final int RAYS = 5;
    private static final int RING = 20;
    private static final float RAY_OFFSET = 28.2f;
    private static final float RAY_HALF_ANGLE = 13.9f;
    private static final float RAY_START_RADIUS = 0.095f;
    private static final float RAY_LENGTH = 1.4f;
    private static final float RAY_MID = 0.35f;
    private static final float RAY_ALPHA = 0.06f;      // was 0.10: the cone is 40% more transparent (also in the long-range mode)
    private static final double RAY_PHASE = 1.771;
    private static final double SPIN = 0.12;

    private static Vec3 LENS = toRig(LENS_LOCAL[0], LENS_LOCAL[1], LENS_LOCAL[2]);
    /** the pivot of the springs and of the swing: the wrist. A FIXED point of the screen (where the correct arc was made), it does not move when the pose of the model is tuned. */
    private static final Vec3 WRIST = new Vec3(1.8397, -0.4561, -0.8130);

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

    private static float kick, lastYaw, lastPitch, time, appear;
    private static float moveAmt, runAmt, phase;
    private static boolean swInit, lastOn;
    /** the hand springs (HandMotion): the pose of this frame {right, up, forward, roll, turn, tilt}. */
    private static final float[] hp = new float[6];
    private static long lastNs;
    private static final long START_NS = System.nanoTime();
    /** true when the motion of this frame was already updated BEFORE the world was drawn (the shader needs it early). */
    private static boolean preUpdated;

    /**
     * What the shader gets: how far (sideways, in hand units) the LENS is moved from its rest place by the whole motion of the hand
     * (the springs: walk, run, look, strafe, fall; the breathing; the swing of the head). The circle of light follows the lens.
     */
    private static float shaderX() {
        PoseStack t = new PoseStack();
        applyMotion(t, 0f);
        com.mojang.math.Vector4f v = new com.mojang.math.Vector4f((float) LENS.x, (float) LENS.y, (float) LENS.z, 1f);
        v.transform(t.last().pose());
        return v.x() - (float) LENS.x;
    }

    /**
     * Called by FlashlightFocus once per frame BEFORE the world is drawn: moves the hand one step and returns its exact
     * sideways offset (the same number the hand is drawn with). The shader pack gets it through the marker in the off hand,
     * so the circle of light moves in step with the hand, frame by frame, without guessing.
     */
    public static float frameUpdate(LocalPlayer p, boolean on, float pt) {
        updateSway(p, pt, on);
        preUpdated = true;
        return shaderX();
    }

    private static void updateSway(LocalPlayer p, float pt, boolean on) {
        long now = System.nanoTime();
        float dt = swInit ? Math.min(0.05f, (now - lastNs) / 1e9f) : 0.016f;
        boolean longPause = swInit && (now - lastNs) > 300_000_000L;
        lastNs = now;
        float yaw = p.getViewYRot(pt), pitch = p.getViewXRot(pt);
        if (!swInit) { lastYaw = yaw; lastPitch = pitch; lastOn = on; swInit = true; }
        float dy = Mth.wrapDegrees(yaw - lastYaw), dp = pitch - lastPitch;
        lastYaw = yaw; lastPitch = pitch;
        if (longPause) HandMotion.reset();

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
        if (moving) phase += dt * Mth.lerp(runAmt, WALK_RATE, RUN_RATE);
        if (phase > 6.2831853f) phase -= 6.2831853f;
        gaitNs = now;

        // the springs of the hand (see HandMotion): the speeds in blocks per second, along the view and sideways
        net.minecraft.world.phys.Vec3 dm = p.getDeltaMovement();
        double rad = Math.toRadians(yaw);
        float fwd = (float) (dm.x * -Math.sin(rad) + dm.z * Math.cos(rad)) * 20f;
        float right = (float) (dm.x * -Math.cos(rad) + dm.z * -Math.sin(rad)) * 20f;
        // up: in the air the real fall / jump speed; on the ground only the climbing (the constant pull of the gravity is not a fall)
        float up = p.isOnGround() ? (float) (p.getY() - p.yo) * 20f : (float) dm.y * 20f;
        boolean sprint = p.isSprinting() && (speed > 0.04 || !p.isOnGround());
        HandMotion.update(dt, fwd, right, up, p.isOnGround(), sprint, dy, -dp);
        HandMotion.pose(hp);
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

    /**
     * The motion of the hand as one transform (used for drawing AND for the circle of light, so they always agree):
     * the breathing at rest, then the springs of HandMotion (walk / run bob, the lag when the head turns, the lean when
     * strafing, the fall and the landing, the lower and closer pose of the run), the swing of the head of the flashlight when
     * running. The springs turn the hand about the WRIST.
     */
    private static void applyMotion(PoseStack ps, float equip) {
        float idle = 1f - moveAmt * 0.6f;
        float bx = ((float) Math.sin(time * 0.9f) * IDLE_X + (float) Math.sin(time * 0.37f + 1.3f) * IDLE_X * 0.5f) * idle;
        float by = ((float) Math.sin(time * 1.25f) * IDLE_Y + (float) Math.sin(time * 0.55f) * IDLE_Y * 0.45f) * idle;
        float bRoll = (float) Math.sin(time * 0.8f) * 1.3f * idle;
        float bPitch = (float) Math.sin(time * 1.25f + 0.8f) * 1.0f * idle;

        // the old arc of the hand, as it was (only the movement: no roll, no tilt, the springs below are not touched):
        // x = sin(phase), y = cos(2 * phase) = the middle is the highest point; smaller when walking
        float wx = (float) Math.sin(phase) * Mth.lerp(runAmt, WALK_AMP_X, RUN_AMP_X) * moveAmt;
        float wy = (float) Math.cos(2 * phase) * Mth.lerp(runAmt, WALK_AMP_Y, RUN_AMP_Y) * moveAmt;

        // the position spring: x right, y up, z FORWARD in the original = -z here
        ps.translate(bx + hp[0] + wx, by + hp[1] + wy - equip * 0.6f, -hp[2]);
        ps.mulPose(Vector3f.ZP.rotationDegrees(bRoll));
        ps.mulPose(Vector3f.XP.rotationDegrees(kick * 4f + bPitch));
        // the rotation spring about the wrist. Original's signs: Y + = turns to the right, X + = the top goes left, Z + = the front goes up
        ps.translate(WRIST.x, WRIST.y, WRIST.z);
        ps.mulPose(Vector3f.YP.rotationDegrees(-hp[4] * HEAD_TURN_SCALE));
        ps.mulPose(Vector3f.ZP.rotationDegrees(hp[3] * HEAD_TURN_SCALE));
        ps.mulPose(Vector3f.XP.rotationDegrees(hp[5] * HEAD_TURN_SCALE));
        // WALKING and RUNNING: the head of the flashlight swings from side to side (smaller when walking); the cone of light follows it
        if (moveAmt > 0.001f) {
            float sw = (float) Math.sin(phase) * moveAmt;
            ps.mulPose(Vector3f.YP.rotationDegrees(-sw * Mth.lerp(runAmt, WALK_HEAD_YAW, RUN_HEAD_YAW) * HEAD_TURN_SCALE));
            ps.mulPose(Vector3f.ZP.rotationDegrees(sw * Mth.lerp(runAmt, WALK_HEAD_ROLL, RUN_HEAD_ROLL) * HEAD_TURN_SCALE));
        }
        ps.translate(-WRIST.x, -WRIST.y, -WRIST.z);
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

    /**
     * What the shader pack gets (FlashlightLink): where the lens (the head) is on the screen RIGHT NOW, with the whole motion of
     * the hand (springs, walk, run, fall, breathing), and where the beam points. {lens x / 2, lens y / 2 (NDC of the hand
     * projection, 70 degrees), beam slope x, y}. The circle of light is put exactly at the lens, so it follows the head.
     */
    static float[] track(LocalPlayer pl, float pt) {
        Minecraft mc = Minecraft.getInstance();
        float ease0 = appear * appear * (3f - 2f * appear);
        float slide = 1f - ease0;
        PoseStack cur = new PoseStack();
        applyMotion(cur, 0f);
        cur.translate(slide * SLIDE_X, slide * SLIDE_Y, slide * SLIDE_Z);
        float tanH = (float) Math.tan(Math.toRadians(35.0));
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1f, (float) mc.getWindow().getHeight());
        com.mojang.math.Vector4f o = new com.mojang.math.Vector4f((float) LENS.x, (float) LENS.y, (float) LENS.z, 1f);
        com.mojang.math.Vector4f d = new com.mojang.math.Vector4f(0f, 0f, -1f, 0f);
        Matrix4f m = cur.last().pose();
        o.transform(m);
        d.transform(m);
        float od = Math.max(0.05f, -o.z());
        float dd = Math.max(0.05f, -d.z());
        return new float[]{
                o.x() / od / (tanH * aspect) * 0.5f, o.y() / od / tanH * 0.5f,
                d.x() / dd / (tanH * aspect), d.y() / dd / tanH};
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
        if (preUpdated) preUpdated = false;        // already moved this frame (before the world was drawn)
        else updateSway(pl, e.getPartialTick(), on);
        long nowH = System.nanoTime();
        if (nowH - lastHeldNs > 200_000_000L) heldTime = 0f;
        heldTime += Math.min(0.05f, (nowH - lastHeldNs) / 1e9f);
        lastHeldNs = nowH;

        if (!on && appear <= 0.001f) return;

        PoseStack ps = e.getPoseStack();
        MultiBufferSource buf = e.getMultiBufferSource();
        int light = e.getPackedLight();
        // the hand and the flashlight are lit by the place where the player stands (sun, shade, a dark room): no forced minimum
        float brightness = Math.max(LightTexture.block(light), LightTexture.sky(light)) / 15f;
        float lightFactor = Mth.clamp(0.55f + 0.45f * brightness, 0.55f, 1f);

        // only the real equip animation counts: pressing the left mouse button (the "hit") must not move the hand
        float equip = heldTime < 0.6f ? e.getEquipProgress() : 0f;
        float ease = appear * appear * (3f - 2f * appear);
        float slide = 1f - ease;

        ps.pushPose();
        if (mc.options.bobView().get()) {
            cancelVanillaBob(ps, pl, e.getPartialTick());
        }
        // the whole motion of the hand (springs, breathing, swing of the head); the hand keeps it in the long-range mode too
        applyMotion(ps, equip);
        ps.translate(slide * SLIDE_X, slide * SLIDE_Y, slide * SLIDE_Z);

        if (on) {
            drawRays(ps.last().pose(), ease);
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
        captureMask(mc, stack, handModel, ps);
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
    // ---------------------------------------------------------------------------------
    //  Where the hand is on the screen (other mods use it to draw something BEHIND the hand, e.g. the glow of the pages)
    // ---------------------------------------------------------------------------------
    private static float[] mask = new float[0];
    private static long maskNs;

    /** every quad of the flashlight and of the hand, projected to the screen: x0,y0,x1,y1,x2,y2,x3,y3 in NDC (-1..1), per quad. Null when the hand was not drawn just now. */
    public static float[] handMask() {
        if (System.nanoTime() - maskNs > 150_000_000L || mask.length == 0) return null;
        return mask;
    }

    private static void captureMask(Minecraft mc, ItemStack stack, BakedModel hand, PoseStack ps) {
        try {
            Matrix4f proj = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
            float[] out = new float[8 * 600];
            int n = 0;
            BakedModel[] models = {mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0), hand};
            net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(42L);
            for (BakedModel bm : models) {
                if (bm == null) continue;
                PoseStack t = new PoseStack();
                t.last().pose().multiply(ps.last().pose());
                bm.getTransforms().getTransform(ItemTransforms.TransformType.NONE).apply(false, t);
                t.translate(-0.5, -0.5, -0.5);
                Matrix4f mv = t.last().pose();
                java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads = new java.util.ArrayList<>(bm.getQuads(null, null, rnd));
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) quads.addAll(bm.getQuads(null, d, rnd));
                for (net.minecraft.client.renderer.block.model.BakedQuad q : quads) {
                    if (n + 8 > out.length) break;
                    int[] v = q.getVertices();
                    float[] tmp = new float[8];
                    boolean ok = true;
                    for (int i = 0; i < 4 && ok; i++) {
                        com.mojang.math.Vector4f p = new com.mojang.math.Vector4f(
                                Float.intBitsToFloat(v[i * 8]), Float.intBitsToFloat(v[i * 8 + 1]), Float.intBitsToFloat(v[i * 8 + 2]), 1f);
                        p.transform(mv);
                        p.transform(proj);
                        if (p.w() < 0.05f) { ok = false; break; }
                        tmp[i * 2] = p.x() / p.w();
                        tmp[i * 2 + 1] = p.y() / p.w();
                    }
                    if (!ok) continue;
                    System.arraycopy(tmp, 0, out, n, 8);
                    n += 8;
                }
            }
            mask = java.util.Arrays.copyOf(out, n);
            maskNs = System.nanoTime();
        } catch (Throwable ignored) {
            mask = new float[0];
        }
    }

    private static BakedModel handModel(Minecraft mc) {
        BakedModel m = mc.getModelManager().getModel(HAND_MODEL);
        return m == mc.getModelManager().getMissingModel() ? null : m;
    }

    private static float pulse(int i, double t) {
        return (float) (0.9 + 0.1 * Math.sin(t * 0.8 + i * 1.7));
    }

    // =====================================================================
    //  The rays of the light (like in the original: soft fibrous streaks that fan out of the head and turn slowly)
    // =====================================================================
    private static final ResourceLocation RAY_TEX = new ResourceLocation(SlenderFlashlightMod.ID, "textures/effect/flashlight_ray.png");
    /** how long a ray is, in "distances from the camera to the head": normal / long-range light. */
    private static final float RAY_LEN_NORMAL = 1.9f, RAY_LEN_FOCUS = 3.2f;
    /** how fast a ray widens (degrees): normal / long-range light. */
    private static final float RAY_WIDEN_NORMAL = 4.0f, RAY_WIDEN_FOCUS = 1.8f;
    private static final int RAY_STEPS = 10;
    /** how bright the streaks are (0..1): raise it if the rays are too faint. */
    private static final float RAY_TEX_ALPHA = 0.26f;

    private static void drawRay(BufferBuilder bb, Matrix4f m, Vec3 dir, float alpha, float foc) {
        // the ribbon lies in the plane facing the camera: its width is across the ray, seen on the screen
        double sl = Math.sqrt(dir.x * dir.x + dir.y * dir.y);
        if (sl < 1e-4) return;
        Vec3 side = new Vec3(dir.y / sl, -dir.x / sl, 0.0);
        double depth = Math.max(0.5, -LENS.z);
        double len = depth * Mth.lerp(foc, RAY_LEN_NORMAL, RAY_LEN_FOCUS);
        double tan = Math.tan(Math.toRadians(Mth.lerp(foc, RAY_WIDEN_NORMAL, RAY_WIDEN_FOCUS)));
        double w0 = 0.05 * FLASH_SCALE;
        for (int s = 0; s < RAY_STEPS; s++) {
            double t0 = (double) s / RAY_STEPS, t1 = (double) (s + 1) / RAY_STEPS;
            Vec3 c0 = LENS.add(dir.scale(len * t0)), c1 = LENS.add(dir.scale(len * t1));
            double h0 = w0 + len * t0 * tan, h1 = w0 + len * t1 * tan;
            float a0 = alpha * rayFade((float) t0), a1 = alpha * rayFade((float) t1);
            Vec3 p0 = c0.add(side.scale(-h0)), p1 = c0.add(side.scale(h0));
            Vec3 p2 = c1.add(side.scale(h1)), p3 = c1.add(side.scale(-h1));
            bb.vertex(m, (float) p0.x, (float) p0.y, (float) p0.z).color(1f, 1f, 1f, a0).uv(0f, (float) t0).endVertex();
            bb.vertex(m, (float) p1.x, (float) p1.y, (float) p1.z).color(1f, 1f, 1f, a0).uv(1f, (float) t0).endVertex();
            bb.vertex(m, (float) p2.x, (float) p2.y, (float) p2.z).color(1f, 1f, 1f, a1).uv(1f, (float) t1).endVertex();
            bb.vertex(m, (float) p3.x, (float) p3.y, (float) p3.z).color(1f, 1f, 1f, a1).uv(0f, (float) t1).endVertex();
        }
    }

    /** brightness along a ray: fades in at the head, fades out far away. */
    private static float rayFade(float t) {
        float in = Mth.clamp(t / 0.12f, 0f, 1f);
        in = in * in * (3f - 2f * in);
        return in * (float) Math.pow(1.0 - t, 1.4);
    }

    private static void drawRays(Matrix4f m, float visible) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        RenderSystem.setShaderTexture(0, RAY_TEX);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);

        float foc = FlashlightFocus.amount();
        double t = (System.nanoTime() - START_NS) / 1e9;
        double spin = RAY_PHASE + t * SPIN;
        double off = Math.tan(Math.toRadians(Mth.lerp(foc, RAY_OFFSET, 7.0f)));
        for (int i = 0; i < RAYS; i++) {
            double phi = 2 * Math.PI * i / RAYS + spin;
            Vec3 dir = new Vec3(off * Math.cos(phi), off * Math.sin(phi), -1.0).normalize();
            drawRay(bb, m, dir, RAY_TEX_ALPHA * visible * pulse(i, t) * (1f + 0.6f * foc), foc);
        }
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(515);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
