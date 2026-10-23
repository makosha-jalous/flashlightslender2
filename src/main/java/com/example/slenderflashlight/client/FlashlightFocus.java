package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Long-range light: hold the LEFT mouse button while a lit flashlight is in the main hand.
 * - the beam and the cone concentrate (the hand and the flashlight stay still);
 * - the click sound plays when it starts and when it ends (a plain, non-positional sound);
 * - while any flashlight is in the main hand the left button never breaks or hits anything.
 *
 * The shader pack cannot read the mod, so the focus is passed to it through the OFFHAND item on the CLIENT only
 * (never sent to the server): a vanilla marker item whose id the pack maps to 9001..9004 (the stage of the focus).
 * The marker is not drawn in the hand and its hotbar icon is covered; the real offhand item comes back at the end.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightFocus {

    private static final Item[] MARKERS = {Items.BARRIER, Items.STRUCTURE_VOID, Items.LIGHT, Items.BEDROCK};
    private static final float SECONDS_TO_FOCUS = 0.30f;

    private static float amount;
    private static boolean wasHeld;
    private static long lastNs;
    private static ItemStack saved = ItemStack.EMPTY;
    private static boolean hasSaved;
    private static int lastStage;

    /** 0 = normal beam, 1 = fully concentrated (smooth, per rendered frame). */
    public static float amount() {
        return amount;
    }

    /** The plain click (not positional, not panned): used for on/off and for the long-range light. */
    public static void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SlenderFlashlightMod.FLASHLIGHT_CLICK.get(), 1.0F, 0.7F));
    }

    private static boolean isMarker(ItemStack s) {
        if (s.isEmpty()) return false;
        for (Item m : MARKERS) if (s.getItem() == m) return true;
        return false;
    }

    private static boolean flashlightInHand(LocalPlayer p) {
        return p.getMainHandItem().getItem() instanceof FlashlightItem;
    }

    /** Before the game handles the keys: the left button must do nothing while the flashlight is held. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || mc.gameMode == null) return;
        if (flashlightInHand(p)) {
            mc.options.keyAttack.setDown(false);
            if (mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        }
    }

    /** A click of the left button does not hit or break anything while the flashlight is held. */
    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.isAttack() && mc.player != null && flashlightInHand(mc.player)) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;

        boolean held = p != null && mc.screen == null && mc.mouseHandler.isMouseGrabbed()
                && mc.mouseHandler.isLeftPressed() && p.isAlive()
                && FlashlightItem.isOn(p.getMainHandItem());
        if (held != wasHeld) {
            wasHeld = held;
            click();
        }
        if (p == null) {
            amount = 0f;
            hasSaved = false;
            return;
        }

        float target = held ? 1f : 0f;
        amount += Mth.clamp(target - amount, -dt / SECONDS_TO_FOCUS, dt / SECONDS_TO_FOCUS);

        int stage = Math.round(amount * MARKERS.length);
        if (!FlashlightItem.isOn(p.getMainHandItem())) {
            stage = 0;
            amount = 0f;
        }
        if (stage > 0) {
            if (!isMarker(p.getOffhandItem()) && !hasSaved) {
                saved = p.getOffhandItem().copy();
                hasSaved = true;
            }
            p.getInventory().offhand.set(0, new ItemStack(MARKERS[Math.min(stage, MARKERS.length) - 1]));
        } else if (hasSaved || isMarker(p.getOffhandItem())) {
            if (isMarker(p.getOffhandItem())) {
                p.getInventory().offhand.set(0, hasSaved ? saved : ItemStack.EMPTY);
            }
            hasSaved = false;
            saved = ItemStack.EMPTY;
        }
        lastStage = stage;
    }

    /** The marker is never drawn in the left hand. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent e) {
        if (e.getHand() == InteractionHand.OFF_HAND && isMarker(e.getItemStack())) e.setCanceled(true);
    }

    /** Covers the hotbar icon of the marker with an empty offhand slot. */
    @SubscribeEvent
    public static void onHotbar(RenderGuiOverlayEvent.Post e) {
        if (e.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !isMarker(p.getOffhandItem())) return;
        PoseStack ps = e.getPoseStack();
        int w = e.getWindow().getGuiScaledWidth();
        int h = e.getWindow().getGuiScaledHeight();
        int i = w / 2;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, new ResourceLocation("textures/gui/widgets.png"));
        if (p.getMainArm().getOpposite() == HumanoidArm.LEFT) {
            GuiComponent.blit(ps, i - 91 - 29, h - 23, 24, 22, 29, 24);
        } else {
            GuiComponent.blit(ps, i + 91, h - 23, 53, 22, 29, 24);
        }
        RenderSystem.disableBlend();
    }
}
