package com.example.slenderflashlight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * While a player holds a switched-on flashlight, a fan of 5 light blocks follows the beam.
 * The fan slowly rotates counter-clockwise around the beam axis, so the "rays" gently swirl.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID)
public class FlashlightLight {

    private static final double RANGE = 22;

    /** Distances along the beam (fraction of the hit distance). */
    private static final double[] FRACTION = {0.15, 0.35, 0.55, 0.78, 1.00};
    /** Light level for each of the five spots (thick near->soft far). */
    private static final int[] LEVEL = {7, 10, 12, 13, 12};
    /** Perpendicular offset from the beam axis (blocks), makes the spots sit "on a ray" not on the axis. */
    private static final double[] OFFSET = {0.45, 0.55, 0.60, 0.45, 0.00};
    /** Angle offset for each spot around the beam axis (radians). */
    private static final double[] ANGLE = {0.0, 1.2566, 2.5133, 3.7699, 0.0};

    /** Counter-clockwise rotation speed (as the player sees it), radians per tick. Very slow: a full turn takes ~52 s. */
    private static final double SPIN = 0.0060;

    private static class Track {
        final ServerLevel level;
        final Map<BlockPos, Integer> spots = new HashMap<>();
        Track(ServerLevel level) { this.level = level; }
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null || srv.getTickCount() % 2 != 0) return;

        long now = srv.getTickCount();
        Set<UUID> active = new HashSet<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            if (!FlashlightItem.isOn(p.getMainHandItem()) && !FlashlightItem.isOn(p.getOffhandItem())) continue;
            active.add(p.getUUID());
            update(p, now);
        }
        for (Iterator<Map.Entry<UUID, Track>> it = TRACKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Track> en = it.next();
            if (!active.contains(en.getKey())) {
                clear(en.getValue());
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        for (Track t : TRACKS.values()) clear(t);
        TRACKS.clear();
    }

    private static void update(ServerPlayer p, long now) {
        ServerLevel lvl = p.getLevel();
        Track tr = TRACKS.get(p.getUUID());
        if (tr != null && tr.level != lvl) {
            clear(tr);
            TRACKS.remove(p.getUUID());
            tr = null;
        }
        if (tr == null) {
            tr = new Track(lvl);
            TRACKS.put(p.getUUID(), tr);
        }

        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle().normalize();
        BlockHitResult hit = lvl.clip(new ClipContext(eye, eye.add(look.scale(RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        boolean miss = hit.getType() == HitResult.Type.MISS;
        double dist = miss ? 16 : eye.distanceTo(hit.getLocation());

        // Build the orthonormal basis perpendicular to the beam axis.
        Vec3 up = new Vec3(0, 1, 0);
        if (Math.abs(look.dot(up)) > 0.95) up = new Vec3(1, 0, 0);
        Vec3 right = look.cross(up).normalize();
        Vec3 axisUp = right.cross(look).normalize();

        // The whole fan rotates counter-clockwise very slowly.
        double spin = now * SPIN; // growing angle = right -> up = counter-clockwise for the player

        Map<BlockPos, Integer> wanted = new HashMap<>();
        for (int i = 0; i < FRACTION.length; i++) {
            double d = dist * FRACTION[i];
            if (d < 1.4) continue;
            double angle = ANGLE[i] + spin;
            double off = OFFSET[i];
            Vec3 v = eye
                    .add(look.scale(d))
                    .add(right.scale(Math.cos(angle) * off))
                    .add(axisUp.scale(Math.sin(angle) * off));
            BlockPos pos = new BlockPos(v.x, v.y, v.z);
            if (!lvl.isLoaded(pos)) continue;
            wanted.merge(pos.immutable(), LEVEL[i], Math::max);
        }

        if (!miss) {
            BlockPos surface = hit.getBlockPos().relative(hit.getDirection());
            if (lvl.isLoaded(surface)) wanted.merge(surface, 14, Math::max);
        }

        for (Iterator<Map.Entry<BlockPos, Integer>> it = tr.spots.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Integer> en = it.next();
            if (!wanted.containsKey(en.getKey())) {
                removeLight(lvl, en.getKey());
                it.remove();
            }
        }
        for (Map.Entry<BlockPos, Integer> en : wanted.entrySet()) {
            BlockPos pos = en.getKey();
            int lv = en.getValue();
            Integer cur = tr.spots.get(pos);
            BlockState st = lvl.getBlockState(pos);
            if (cur != null && !st.is(Blocks.LIGHT)) {
                tr.spots.remove(pos);
                continue;
            }
            if (cur != null && cur == lv) continue;
            if (cur == null && !st.isAir()) continue;
            lvl.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(BlockStateProperties.LEVEL, lv), 2);
            tr.spots.put(pos, lv);
        }
    }

    private static void clear(Track t) {
        for (BlockPos pos : t.spots.keySet()) removeLight(t.level, pos);
        t.spots.clear();
    }

    private static void removeLight(ServerLevel lvl, BlockPos pos) {
        if (lvl.getBlockState(pos).is(Blocks.LIGHT)) {
            lvl.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }
}