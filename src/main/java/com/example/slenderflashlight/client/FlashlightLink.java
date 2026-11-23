package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The link between the mod and the shader pack. The pack cannot read the mod, so the mod writes six pixels of the light
 * texture of the game (the 16x16 "lightmap", row 0), once per frame, right before the world is drawn:
 *   pixel 0: a fixed check value (so the pack knows the data is real)
 *   pixel 1,2: how far the HEAD of the flashlight has moved on the screen from the resting hand (x, y)
 *   pixel 3,4: how far the BEAM has turned (x, y)
 *   pixel 5: red = the long-range light is held, green = the data is valid (the flashlight is lit in the hand)
 * A value uses 24 bits (the three colour bytes). The pack does not use the light texture of the game for anything, so nothing
 * changes in the picture. It is written ONLY while a shader pack is on (OptiFine), so plain Minecraft is never touched.
 * Nothing is put into the off hand and nothing is shown to the player.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public final class FlashlightLink {
    private FlashlightLink() { }

    private static boolean failed;
    private static Field texField;
    private static Method isShaders;
    private static boolean shadersChecked;

    private static boolean shadersOn() {
        try {
            if (!shadersChecked) {
                shadersChecked = true;
                isShaders = Class.forName("net.optifine.Config").getMethod("isShaders");
            }
            return isShaders != null && (Boolean) isShaders.invoke(null);
        } catch (Throwable t) {
            isShaders = null;
            return false;
        }
    }

    private static int abgr(int r, int g, int b) {
        return (255 << 24) | ((b & 255) << 16) | ((g & 255) << 8) | (r & 255);
    }

    /** value -1..1 -> three bytes */
    private static int enc(float v) {
        float c = Math.max(-1f, Math.min(1f, v));
        int u = Math.round((c * 0.5f + 0.5f) * 16777215f);
        return abgr((u >> 16) & 255, (u >> 8) & 255, u & 255);
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent e) {
        if (failed || e.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !shadersOn()) return;
        try {
            LightTexture lt = mc.gameRenderer.lightTexture();
            if (texField == null) {
                for (Field f : LightTexture.class.getDeclaredFields()) {
                    if (f.getType() == DynamicTexture.class) { f.setAccessible(true); texField = f; break; }
                }
                if (texField == null) throw new IllegalStateException("light texture field");
            }
            DynamicTexture dt = (DynamicTexture) texField.get(lt);
            NativeImage img = dt.getPixels();
            if (img == null) return;

            boolean lit = FlashlightItem.isOn(p.getMainHandItem()) && p.getMainHandItem().getItem() instanceof FlashlightItem;
            float[] t = lit ? FlashlightClient.track(p, e.getPartialTick()) : new float[4];
            img.setPixelRGBA(0, 0, abgr(0x5A, 0xC3, 0x3C));
            img.setPixelRGBA(1, 0, enc(t[0]));
            img.setPixelRGBA(2, 0, enc(t[1]));
            img.setPixelRGBA(3, 0, enc(t[2]));
            img.setPixelRGBA(4, 0, enc(t[3]));
            img.setPixelRGBA(5, 0, abgr(FlashlightFocus.focused() ? 255 : 0, lit ? 255 : 0, 0));
            dt.upload();
        } catch (Throwable ex) {
            failed = true;
            System.out.println("[slenderflashlight] light link is off: " + ex);
        }
    }
}
