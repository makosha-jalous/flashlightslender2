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
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

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

    /**
     * THE CHANNEL TO THE SHADER. The pack reads only the id of the off hand item (item.properties), so the marker is one of
     * 208 plain items (13 families x 16 colours); the shader pack maps them to the ids 9100 .. 9307 (the same order):
     *   state = focus * 104 + level      focus 0 = normal beam, 1 = the long-range light
     *   level 0..103 = the sideways offset of the hand, from -SWAY_RANGE to +SWAY_RANGE (the number the hand is drawn with).
     */
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] FAMILIES = {"wool", "carpet", "concrete", "concrete_powder", "glazed_terracotta", "stained_glass",
            "stained_glass_pane", "bed", "banner", "shulker_box", "dye", "terracotta", "candle"};
    public static final int LEVELS = 104;
    public static final float SWAY_RANGE = 0.20f;
    private static Item[] states;
    private static Set<Item> stateSet;
    /** true while our marker stands in the off hand (the real off hand item is kept in "saved"). */
    private static boolean markerActive;

    private static Item[] states() {
        if (states == null) {
            Item[] s = new Item[FAMILIES.length * COLORS.length];
            Set<Item> set = new HashSet<>();
            for (int f = 0; f < FAMILIES.length; f++) {
                for (int c = 0; c < COLORS.length; c++) {
                    Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", COLORS[c] + "_" + FAMILIES[f]));
                    if (it == null || it == Items.AIR) it = Items.BARRIER;     // never happens in 1.19.2
                    s[f * COLORS.length + c] = it;
                    set.add(it);
                }
            }
            states = s;
            stateSet = set;
        }
        return states;
    }
    private static final float SECONDS_TO_FOCUS = 1.0f;

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

    /** What was in the off hand before the marker took its place (the camera mod needs it to keep its interface). */
    public static ItemStack savedOffhand() {
        return hasSaved ? saved : ItemStack.EMPTY;
    }

    /** The plain click (not positional, not panned): used for on/off and for the long-range light. */
    public static void click() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SlenderFlashlightMod.FLASHLIGHT_CLICK.get(), 1.0F, 0.7F));
    }

    private static boolean isStateItem(ItemStack s) {
        if (s.isEmpty()) return false;
        states();
        return stateSet.contains(s.getItem());
    }

    private static boolean isMarker(ItemStack s) {
        if (!markerActive || s.isEmpty()) return false;
        states();
        return stateSet.contains(s.getItem());
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
            markerActive = false;
            return;
        }

        float target = held ? 1f : 0f;
        amount = target;      // the change is instant (one frame), both ways

        int stage = amount > 0.5f ? 1 : 0;
        boolean lit = FlashlightItem.isOn(p.getMainHandItem()) && p.isAlive() && mc.screen == null;   // no marker while a screen (inventory) is open
        if (!lit) {
            amount = 0f;
        }
        if (lit) {
            // the hand moves one step NOW (before the world is drawn): the shader gets the same number the hand is drawn with
            float x = FlashlightClient.frameUpdate(p, true, e.renderTickTime);
            int level = Math.round(Mth.clamp((x + SWAY_RANGE) / (2f * SWAY_RANGE), 0f, 1f) * (LEVELS - 1));
            int state = stage * LEVELS + level;
            ItemStack cur = p.getOffhandItem();
            // a real item (the player put one there, or the server gave it back) is kept; our own marker is not
            if (!markerActive || !isStateItem(cur)) {
                saved = cur.copy();
                hasSaved = true;
            }
            markerActive = true;
            p.getInventory().offhand.set(0, new ItemStack(states()[state]));
        } else if (markerActive) {
            p.getInventory().offhand.set(0, hasSaved ? saved : ItemStack.EMPTY);
            markerActive = false;
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

    /** the marker is never seen in the interface: while the whole GUI is drawn, the off hand holds its REAL item (or nothing). */
    private static ItemStack hiddenMarker = ItemStack.EMPTY;

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onGuiPre(RenderGuiEvent.Pre e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !isMarker(p.getOffhandItem())) return;
        hiddenMarker = p.getOffhandItem();
        p.getInventory().offhand.set(0, hasSaved ? saved : ItemStack.EMPTY);
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onGuiPost(RenderGuiEvent.Post e) {
        if (hiddenMarker.isEmpty()) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.getInventory().offhand.set(0, hiddenMarker);
        hiddenMarker = ItemStack.EMPTY;
    }
}
