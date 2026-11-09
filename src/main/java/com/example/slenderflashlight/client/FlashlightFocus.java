package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.SlenderFlashlightMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Long-range light: hold the LEFT mouse button while a lit flashlight is in the main hand.
 * - the beam and the cone concentrate (the hand and the flashlight stay still);
 * - the click sound plays when it starts and when it ends (a plain, non-positional sound);
 * - while any flashlight is in the main hand the left button never breaks or hits anything.
 *
 * NOTHING is put in the off hand any more (no marker items at all). The shader pack gets the position of the head of the
 * flashlight and the focus from FlashlightLink (a few pixels of the light texture of the game).
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightFocus {

    private static float amount;
    private static boolean wasHeld;
    private static boolean focus;

    /** 0 = normal beam, 1 = fully concentrated (per rendered frame). */
    public static float amount() {
        return amount;
    }

    /** true while the long-range light is held and the flashlight is lit. */
    static boolean focused() {
        return focus;
    }

    /** Kept for the camera mod (it reads this by reflection): the off hand is never touched now, so there is nothing saved. */
    public static ItemStack savedOffhand() {
        return ItemStack.EMPTY;
    }

    /** The plain click (not positional, not panned): used for on/off and for the long-range light. */
    public static void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SlenderFlashlightMod.FLASHLIGHT_CLICK.get(), 1.0F, 0.7F));
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

        boolean held = p != null && mc.screen == null && mc.mouseHandler.isMouseGrabbed()
                && mc.mouseHandler.isLeftPressed() && p.isAlive()
                && FlashlightItem.isOn(p.getMainHandItem());
        if (held != wasHeld) {
            wasHeld = held;
            click();
        }
        if (p == null) {
            amount = 0f;
            focus = false;
            return;
        }

        boolean lit = FlashlightItem.isOn(p.getMainHandItem());
        amount = (held && lit) ? 1f : 0f;      // the change is instant (one frame), both ways
        focus = amount > 0.5f;

        // the hand moves one step BEFORE the world is drawn (the light link needs the head of the flashlight early)
        if (lit && flashlightInHand(p)) {
            FlashlightClient.frameUpdate(p, true, e.renderTickTime);
        }
    }
}
