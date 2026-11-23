package com.example.slenderflashlight.client;

import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-game tuner for the pose of the flashlight together with the hand (one model).
 * Press F7 (changeable in Controls): a panel with sliders opens. Move the sliders and look at the hand live.
 * "Save" writes config/slenderflashlight_pose.txt (it is loaded by itself the next time the game starts),
 * copies the lines to the clipboard and prints them in the chat: paste them into FlashlightClient.java over FLASH_X ... FLASH_ROLL.
 */
public class FlashlightTuner {

    public static final KeyMapping KEY = new KeyMapping("Flashlight pose tuner", InputConstants.KEY_F7, "Slender Flashlight");

    private static final String[] NAMES = {"X", "Y", "Z", "SCALE", "YAW", "PITCH", "ROLL"};
    private static final String[] LABELS = {"Position X", "Position Y", "Distance Z", "Scale", "Yaw (left-right)", "Pitch (up-down)", "Roll (tilt)"};
    private static final float[] MIN = {-4f, -4f, -3f, 0.2f, -180f, -180f, -180f};
    private static final float[] MAX = {4f, 4f, -0.2f, 4f, 180f, 180f, 180f};
    private static final float[] STEP = {0.01f, 0.01f, 0.01f, 0.01f, 0.5f, 0.5f, 0.5f};

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("slenderflashlight_pose.txt");
    }

    private static String lines(float[] p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(String.format(Locale.ROOT, "    private static float FLASH_%s = %.4ff;%n", NAMES[i], p[i]));
        }
        return sb.toString();
    }

    private static String save() {
        float[] p = FlashlightClient.getPose();
        String text = lines(p);
        try {
            Files.writeString(file(), text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Could not write the file: " + e.getMessage();
        }
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(text);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Tuner] saved (also in the clipboard):\n" + text), false);
        }
        return "Saved: config/slenderflashlight_pose.txt + clipboard";
    }

    private static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            float[] p = FlashlightClient.getPose();
            Matcher m = Pattern.compile("FLASH_(\\w+)\\s*=\\s*(-?[0-9.]+)f").matcher(text);
            while (m.find()) {
                for (int i = 0; i < NAMES.length; i++) {
                    if (NAMES[i].equals(m.group(1))) p[i] = Float.parseFloat(m.group(2));
                }
            }
            FlashlightClient.setPose(p);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- events
    @Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModEvents {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            e.register(KEY);
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent e) {
            e.enqueueWork(FlashlightTuner::load);
        }
    }

    @Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
    public static class GameEvents {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (KEY.consumeClick()) {
                if (mc.player != null && mc.screen == null) mc.setScreen(new TunerScreen());
            }
        }
    }

    // ---------------------------------------------------------------- screen
    static class TunerScreen extends Screen {
        private final PoseSlider[] sliders = new PoseSlider[7];
        private String status = "";

        TunerScreen() {
            super(Component.literal("Flashlight pose"));
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        protected void init() {
            float[] p = FlashlightClient.getPose();
            int x0 = 6, y0 = 18;
            for (int i = 0; i < 7; i++) {
                final int idx = i;
                int y = y0 + i * 22;
                addRenderableWidget(new Button(x0, y, 20, 20, Component.literal("-"), b -> nudge(idx, -1)));
                sliders[i] = new PoseSlider(x0 + 22, y, 150, 20, i, p[i]);
                addRenderableWidget(sliders[i]);
                addRenderableWidget(new Button(x0 + 174, y, 20, 20, Component.literal("+"), b -> nudge(idx, 1)));
            }
            int by = y0 + 7 * 22 + 4;
            addRenderableWidget(new Button(x0, by, 62, 20, Component.literal("Save"), b -> status = save()));
            addRenderableWidget(new Button(x0 + 66, by, 62, 20, Component.literal("Reset"), b -> {
                FlashlightClient.setPose(FlashlightClient.defaultPose());
                for (PoseSlider s : sliders) s.sync();
                status = "Back to the values written in the code";
            }));
            addRenderableWidget(new Button(x0 + 132, by, 62, 20, Component.literal("Close"), b -> onClose()));
        }

        private void nudge(int i, int dir) {
            float[] p = FlashlightClient.getPose();
            float k = hasShiftDown() ? 10f : 1f;
            p[i] = Mth.clamp(p[i] + dir * STEP[i] * k, MIN[i], MAX[i]);
            FlashlightClient.setPose(p);
            sliders[i].sync();
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (KEY.matches(key, scan)) {
                onClose();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        public void render(PoseStack ps, int mx, int my, float pt) {
            // no dark background: the hand must stay visible
            fill(ps, 2, 2, 200, 18 + 7 * 22 + 4 + 36, 0x99000000);
            drawString(ps, this.font, "Flashlight tuner  (Shift on +/- = x10)", 6, 6, 0xFFFFFF);
            drawString(ps, this.font, status, 6, 18 + 7 * 22 + 28, 0xAAFFAA);
            super.render(ps, mx, my, pt);
        }
    }

    static class PoseSlider extends AbstractSliderButton {
        private final int idx;

        PoseSlider(int x, int y, int w, int h, int idx, float v) {
            super(x, y, w, h, Component.empty(), norm(idx, v));
            this.idx = idx;
            updateMessage();
        }

        private static double norm(int i, float v) {
            return Mth.clamp((v - MIN[i]) / (MAX[i] - MIN[i]), 0.0, 1.0);
        }

        void sync() {
            this.value = norm(idx, FlashlightClient.getPose()[idx]);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float v = FlashlightClient.getPose()[idx];
            setMessage(Component.literal(String.format(Locale.ROOT, "%s: %.3f", LABELS[idx], v)));
        }

        @Override
        protected void applyValue() {
            float[] p = FlashlightClient.getPose();
            p[idx] = MIN[idx] + (float) this.value * (MAX[idx] - MIN[idx]);
            FlashlightClient.setPose(p);
        }
    }
}
