package com.example.slenderflashlight.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.widget.ForgeSlider;

public class FlashlightTunerScreen extends Screen {

    public FlashlightTunerScreen() {
        super(Component.literal("Настройка фонарика (ViewModel)"));
    }

    @Override
    protected void init() {
        int x = 15;
        int y = 20;
        int w = 180;
        int h = 20;
        int gap = 24;

        // X (-2.0 .. 2.0)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("X: "), Component.empty(),
                -2.0, 2.0, FlashlightClient.FLASH_X, 0.01, 2, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_X = (float) getValue();
            }
        });
        y += gap;

        // Y (-2.0 .. 2.0)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("Y: "), Component.empty(),
                -2.0, 2.0, FlashlightClient.FLASH_Y, 0.01, 2, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_Y = (float) getValue();
            }
        });
        y += gap;

        // Z (-2.0 .. 2.0)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("Z: "), Component.empty(),
                -2.0, 2.0, FlashlightClient.FLASH_Z, 0.01, 2, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_Z = (float) getValue();
            }
        });
        y += gap;

        // Scale (0.1 .. 3.0)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("Scale: "), Component.empty(),
                0.1, 3.0, FlashlightClient.FLASH_SCALE, 0.01, 2, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_SCALE = (float) getValue();
            }
        });
        y += gap;

        // Yaw (-180 .. 180)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("Yaw: "), Component.literal("°"),
                -180.0, 180.0, FlashlightClient.FLASH_YAW, 0.5, 1, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_YAW = (float) getValue();
            }
        });
        y += gap;

        // Pitch (-180 .. 180)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("Pitch: "), Component.literal("°"),
                -180.0, 180.0, FlashlightClient.FLASH_PITCH, 0.5, 1, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_PITCH = (float) getValue();
            }
        });
        y += gap;

        // Roll (-180 .. 180)
        addRenderableWidget(new ForgeSlider(x, y, w, h, Component.literal("Roll: "), Component.literal("°"),
                -180.0, 180.0, FlashlightClient.FLASH_ROLL, 0.5, 1, true) {
            @Override
            protected void applyValue() {
                FlashlightClient.FLASH_ROLL = (float) getValue();
            }
        });
        y += gap + 6;

        // Кнопка копирования готового Java-кода в буфер обмена
        addRenderableWidget(new Button(x, y, w, h, Component.literal("Скопировать код (Ctrl+V)"), btn -> {
            String code = String.format(java.util.Locale.US,
                    "private static final float FLASH_X = %.2ff;
" +
                    "private static final float FLASH_Y = %.2ff;
" +
                    "private static final float FLASH_Z = %.2ff;
" +
                    "private static final float FLASH_SCALE = %.2ff;
" +
                    "private static final float FLASH_YAW = %.1ff;
" +
                    "private static final float FLASH_PITCH = %.1ff;
" +
                    "private static final float FLASH_ROLL = %.1ff;",
                    FlashlightClient.FLASH_X, FlashlightClient.FLASH_Y, FlashlightClient.FLASH_Z,
                    FlashlightClient.FLASH_SCALE, FlashlightClient.FLASH_YAW,
                    FlashlightClient.FLASH_PITCH, FlashlightClient.FLASH_ROLL
            );
            Minecraft.getInstance().keyboardHandler.setClipboard(code);
            btn.setMessage(Component.literal("Скопировано!"));
        }));
    }

    @Override
    public void render(PoseStack ps, int mouseX, int mouseY, float partialTick) {
        super.render(ps, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
