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
 * While a player holds a switched-on flashlight, a few vanilla invisible "light" blocks
 * follow the beam (near, middle, and where it hits a surface) and are removed again after.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID)
public class FlashlightLight {

    private static final double RANGE = 22;
    private static final double[] FRACTION = {0.30, 0.60, 1.00};
    private static final int[] LEVEL = {6, 9, 13};

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

        Set<UUID> active = new HashSet<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            if (!FlashlightItem.isOn(p.getMainHandItem()) && !FlashlightItem.isOn(p.getOffhandItem())) continue;
            active.add(p.getUUID());
            update(p);
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

    private static void update(ServerPlayer p) {
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
        Vec3 look = p.getLookAngle();
        BlockHitResult hit = lvl.clip(new ClipContext(eye, eye.add(look.scale(RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        boolean miss = hit.getType() == HitResult.Type.MISS;
        double dist = miss ? 16 : eye.distanceTo(hit.getLocation());

        Map<BlockPos, Integer> wanted = new HashMap<>();
        for (int i = 0; i < FRACTION.length; i++) {
            BlockPos pos;
            if (i == FRACTION.length - 1 && !miss) {
                pos = hit.getBlockPos().relative(hit.getDirection()); // air next to the lit surface
            } else {
                double d = dist * FRACTION[i];
                if (d < 1.6) continue; // never right in your face
                Vec3 v = eye.add(look.scale(d));
                pos = new BlockPos(v.x, v.y, v.z);
            }
            if (!lvl.isLoaded(pos)) continue;
            wanted.merge(pos.immutable(), LEVEL[i], Math::max);
        }

        // remove spots the beam left
        for (Iterator<Map.Entry<BlockPos, Integer>> it = tr.spots.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Integer> en = it.next();
            if (!wanted.containsKey(en.getKey())) {
                removeLight(lvl, en.getKey());
                it.remove();
            }
        }
        // add / refresh
        for (Map.Entry<BlockPos, Integer> en : wanted.entrySet()) {
            BlockPos pos = en.getKey();
            int lv = en.getValue();
            Integer cur = tr.spots.get(pos);
            BlockState st = lvl.getBlockState(pos);
            if (cur != null && !st.is(Blocks.LIGHT)) { // something replaced it; let go
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
