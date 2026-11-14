package com.example.slenderflashlight.client;

/**
 * The movement of the hand, built the way the original game (UFPS: vp_FPSWeapon + vp_Spring) does it.
 *
 * Two springs hold the hand: one for the POSITION (x right, y up, z forward, in blocks) and one for the ROTATION
 * (three turning angles in degrees, in the "Euler" order of the original). Nothing moves the hand directly: every tick
 * the walking, the turning of the head, the strafing, the falling and the landing only PUSH the springs (AddForce), and the
 * springs pull the hand back to its rest pose. The changing of the rest pose (walk -> run) is also done by the springs,
 * so every change is smooth by itself.
 *
 * This class knows nothing about Minecraft: the caller gives it the numbers, and takes the pose back. That is why it can be
 * tested alone.
 *
 * The axes of the ROTATION spring (the weapon of the original is turned by 271.49 degrees, so its own axes are):
 *   X = roll about the forward axis (positive = the top goes to the LEFT, as seen by the player)
 *   Y = turning left-right about the vertical axis (positive = to the RIGHT)
 *   Z = tilting up-down about the left-right axis (positive = the front goes UP)
 */
final class HandMotion {
    private HandMotion() { }

    /** the springs of the original run in the fixed step of the game physics: 60 steps per second (guess: not in the files). */
    static final float TICK = 1f / 60f;

    // ---------------------------------------------------------------------------------------------
    //  vp_Spring, exactly as in the original file (Calculate, AddForce, Move, Reset)
    // ---------------------------------------------------------------------------------------------
    static final class Spring {
        final float[] state = new float[3], rest = new float[3], vel = new float[3];
        float stiffness = 0.01f;
        float damping = 0.75f;           // the original stores 1 - "damping" (0.25 in the presets)
        static final float MAX_VELOCITY = 10000f, MIN_VELOCITY = 1e-7f;

        void addForce(float x, float y, float z) {
            vel[0] += x; vel[1] += y; vel[2] += z;
            move();                      // yes: the original moves the state at once, on every AddForce
        }

        private void move() {
            state[0] += vel[0]; state[1] += vel[1]; state[2] += vel[2];
        }

        /** FixedUpdate -> Calculate */
        void tick() {
            if (state[0] == rest[0] && state[1] == rest[1] && state[2] == rest[2]) return;
            for (int i = 0; i < 3; i++) {
                vel[i] += (rest[i] - state[i]) * stiffness;
                vel[i] *= damping;
            }
            float sq = vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2];
            if (sq > MIN_VELOCITY * MIN_VELOCITY) {
                move();
            } else {
                for (int i = 0; i < 3; i++) { vel[i] = 0f; state[i] = rest[i]; }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  The presets. RUN: Run.txt of the original. WALK: the stock defaults of vp_FPSWeapon for everything the file
    //  does not tell (look / strafe / fall sway, slide), and the bob of the same kind as the run, made weaker.
    // ---------------------------------------------------------------------------------------------
    private static final class Preset {
        final float[] bobRate;           // x,y,z,w
        final float[] bobAmp;            // x,y,z,w
        final float bobScale;
        final float[] lookSway, strafeSway, fallSway, slide;
        final float slope, retract;
        Preset(float[] bobRate, float[] bobAmp, float bobScale, float[] lookSway, float[] strafeSway,
               float[] fallSway, float slope, float[] slide, float retract) {
            this.bobRate = bobRate; this.bobAmp = bobAmp; this.bobScale = bobScale; this.lookSway = lookSway;
            this.strafeSway = strafeSway; this.fallSway = fallSway; this.slope = slope; this.slide = slide;
            this.retract = retract;
        }
    }

    /** Run.txt */
    private static final Preset RUN = new Preset(
            new float[]{0.55f, 0.6f, 1.1f, 0.59f}, new float[]{100f, 20f, -20f, -0.09f}, 0.17f,
            new float[]{1.2f, 1.0f, 0.0f}, new float[]{0.3f, 1.0f, 1.5f}, new float[]{1.0f, -0.5f, -3.0f}, 0.5f,
            new float[]{0.5f, 0.75f, 0.5f}, 1.0f);
    /** WALK: GUESS (no file): stock rates and sways, the bob of the run with a smaller amplitude. Walk presets are not in the files. */
    private static final Preset WALK = new Preset(
            new float[]{0.45f, 0.9f, 0.9f, 0.45f}, new float[]{100f, 20f, -20f, -0.09f}, 0.17f,
            new float[]{0.7f, 1.0f, 0.0f}, new float[]{0.3f, 1.0f, 1.5f}, new float[]{1.0f, -0.5f, -3.0f}, 0.5f,
            new float[]{0.5f, 0.75f, 0.5f}, 1.0f);

    /** how much stronger / weaker the bob of the walk and of the run is (1 = as the formulas of the original give). */
    static final float WALK_BOB_GAIN = 1.0f;
    static final float RUN_BOB_GAIN = 1.0f;

    /**
     * The rest pose of the RUN against the walk (x right, y up, z forward, blocks): lower and closer to the player.
     * GUESS: the walk preset is not known; Run.txt has the offset y -0.2, z 0.3 (the walk of the stock game: y -0.15, z -0.15).
     */
    static final float[] RUN_REST = {0.0f, -0.025f, -0.03f};

    /** raw mouse counts of the original per degree of turning here (guess: the scale of the mouse is not in the files). */
    static final float MOUSE_UNITS_PER_DEG = 1.0f;
    static final float MOUSE_MAX = 15f, VELOCITY_MAX = 25f;
    static final float POSITION_KNEELING = 0.1f, CAMERA_KNEELING = 0.025f;

    // ---------------------------------------------------------------------------------------------
    //  State
    // ---------------------------------------------------------------------------------------------
    private static final Spring pos = new Spring(), rot = new Spring();
    private static float acc;                  // time not yet spent in 1/60 s steps
    private static float simTime;              // absolute time of the bob (cos(time * rate * 10))
    private static float lastBob;
    private static float mouseYawAcc, mousePitchAcc;
    private static boolean wasGround = true, init;
    private static float minVy;
    private static final float[] prev = new float[6], cur = new float[6];

    // the input of the last frame
    private static float inFwd, inRight, inUp;
    private static boolean inGround = true, inRun;

    /** forget everything (the flashlight was put away for a while, the world changed). */
    static void reset() {
        for (int i = 0; i < 3; i++) {
            pos.state[i] = pos.rest[i] = pos.vel[i] = 0f;
            rot.state[i] = rot.rest[i] = rot.vel[i] = 0f;
        }
        acc = 0f; lastBob = 0f; mouseYawAcc = mousePitchAcc = 0f; minVy = 0f; wasGround = true;
        java.util.Arrays.fill(prev, 0f); java.util.Arrays.fill(cur, 0f);
        init = false;
    }

    /**
     * One frame.
     * @param dt        seconds since the last frame
     * @param fwd       speed forward (blocks per second, negative = back)
     * @param right     speed to the right (blocks per second)
     * @param up        speed up (blocks per second; in the air the real one, on the ground only the climbing of stairs / slopes)
     * @param onGround  standing on the ground
     * @param running   the run preset is active (sprinting and moving)
     * @param yawDeg    how many degrees the view turned to the RIGHT in this frame
     * @param pitchDeg  how many degrees the view turned UP in this frame
     */
    static void update(float dt, float fwd, float right, float up, boolean onGround, boolean running,
                       float yawDeg, float pitchDeg) {
        if (!init) { init = true; wasGround = onGround; }
        inFwd = fwd; inRight = right; inUp = up; inGround = onGround; inRun = running;
        mouseYawAcc += yawDeg;
        mousePitchAcc += pitchDeg;
        acc += Math.min(dt, 0.1f);
        int steps = (int) (acc / TICK);
        float perStepYaw = steps > 0 ? mouseYawAcc / steps : 0f, perStepPitch = steps > 0 ? mousePitchAcc / steps : 0f;
        if (steps > 0) { mouseYawAcc = 0f; mousePitchAcc = 0f; }
        for (int i = 0; i < steps; i++) {
            System.arraycopy(cur, 0, prev, 0, 6);
            step(perStepYaw, perStepPitch);
            acc -= TICK;
            snapshot(cur);
        }
    }

    private static void snapshot(float[] o) {
        o[0] = pos.state[0]; o[1] = pos.state[1]; o[2] = pos.state[2];
        o[3] = rot.state[0]; o[4] = rot.state[1]; o[5] = rot.state[2];
    }

    private static float clamp(float v, float m) { return Math.max(-m, Math.min(m, v)); }
    private static float smoothstep01(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3f - 2f * x); }

    /** one 1/60 s step: UpdateForces (all the AddForce calls), then the FixedUpdate of the springs. */
    private static void step(float yawDeg, float pitchDeg) {
        simTime += TICK;
        Preset p = inRun ? RUN : WALK;
        float gain = inRun ? RUN_BOB_GAIN : WALK_BOB_GAIN;
        pos.rest[0] = inRun ? RUN_REST[0] : 0f;
        pos.rest[1] = inRun ? RUN_REST[1] : 0f;
        pos.rest[2] = inRun ? RUN_REST[2] : 0f;

        // ---- landing (the camera of the original detects it, and tells the weapon) ----
        if (inUp < minVy) minVy = inUp;
        if (inGround && !wasGround) {
            float impact = Math.abs(minVy);
            float num = smoothstep01(impact * CAMERA_KNEELING);
            pos.addForce(0f, -num * POSITION_KNEELING, 0f);
            minVy = 0f;
        }
        wasGround = inGround;

        // ---- bob (DoWeaponBob) ----
        float sq = inGround ? inFwd * inFwd + inRight * inRight : 0f;
        float speed = Math.min(sq * p.bobScale, 1000f);
        if (speed == 0f) speed = lastBob * 0.93f;
        float x = (float) Math.cos(simTime * (p.bobRate[1] * 10f)) * speed * (p.bobAmp[1] * -0.0001f) * gain;
        float y = (float) Math.cos(simTime * (p.bobRate[0] * 10f)) * speed * (p.bobAmp[0] * 0.0001f) * gain;
        float z = (float) Math.cos(simTime * (p.bobRate[2] * 10f)) * speed * (p.bobAmp[2] * 0.0001f) * gain;
        float z2 = (float) Math.cos(simTime * (p.bobRate[3] * 10f)) * speed * (p.bobAmp[3] * 0.0001f) * gain;
        rot.addForce(x, y, z);
        pos.addForce(0f, 0f, z2);
        lastBob = speed;

        // ---- swaying (DoWeaponSwaying) ----
        float vF = clamp(inFwd * 1f, VELOCITY_MAX), vU = clamp(inUp, VELOCITY_MAX), vL = clamp(-inRight, VELOCITY_MAX);
        // the weapon's own axes: x = forward, y = up, z = left
        float lx = vF / 60f, lz = vL / 60f;
        float mx = clamp(yawDeg * MOUSE_UNITS_PER_DEG, MOUSE_MAX), my = clamp(pitchDeg * MOUSE_UNITS_PER_DEG, MOUSE_MAX);
        rot.addForce(my * (p.lookSway[1] * 0.025f), mx * (p.lookSway[0] * -0.025f), mx * (p.lookSway[2] * -0.025f));
        float ax = p.fallSway[0] * (vU * 0.005f), ay = p.fallSway[1] * (vU * 0.005f), az = p.fallSway[2] * (vU * 0.005f);
        if (inGround) { ax *= p.slope; ay *= p.slope; az *= p.slope; }
        rot.addForce(ax, ay, Math.max(0f, az));
        pos.addForce(0f, 0f, -Math.abs(vU * (p.retract * 2.5e-5f)));
        pos.addForce(lx * (p.slide[0] * 0.0016f), -Math.abs(lx * (p.slide[1] * 0.0016f)), -lz * (p.slide[2] * 0.0016f));
        rot.addForce(-Math.abs(lx * (p.strafeSway[0] * 0.16f)), -(lx * (p.strafeSway[1] * 0.16f)), lx * (p.strafeSway[2] * 0.16f));

        pos.tick();
        rot.tick();
    }

    /**
     * The pose between the last two steps (so it is smooth at any frame rate), as
     * {right, up, forward (blocks), rollX, turnY, tiltZ (degrees, the original's signs)}.
     */
    static void pose(float[] out) {
        float a = Math.max(0f, Math.min(1f, acc / TICK));
        for (int i = 0; i < 6; i++) out[i] = prev[i] + (cur[i] - prev[i]) * a;
    }
}
