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
 *
 * EXACT CHANNEL: while the flashlight is lit, the marker is one of 208 plain items (the pack maps them to ids 9100..9307):
 * index = focus * 104 + level, where level 0..103 is the sideways offset of the hand (-0.20 .. +0.20), the very number the hand
 * is drawn with (FlashlightClient.frameUpdate). So the circle of light follows the head of the flashlight frame by frame.
 * The order of EXACT must be the same as item.9100 .. item.9307 in the pack's item.properties.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightFocus {

    private static final Item[] MARKERS = {Items.BARRIER, Items.STRUCTURE_VOID, Items.LIGHT, Items.BEDROCK,
            Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK, Items.JIGSAW};
    private static final float SECONDS_TO_FOCUS = 1.0f;

    /** the 208 items of the exact channel, in the order of item.9100 .. item.9307 of the shader pack. */
    private static final String[] EXACT_NAMES = {
            "white_wool", "orange_wool", "magenta_wool", "light_blue_wool",
            "yellow_wool", "lime_wool", "pink_wool", "gray_wool",
            "light_gray_wool", "cyan_wool", "purple_wool", "blue_wool",
            "brown_wool", "green_wool", "red_wool", "black_wool",
            "white_carpet", "orange_carpet", "magenta_carpet", "light_blue_carpet",
            "yellow_carpet", "lime_carpet", "pink_carpet", "gray_carpet",
            "light_gray_carpet", "cyan_carpet", "purple_carpet", "blue_carpet",
            "brown_carpet", "green_carpet", "red_carpet", "black_carpet",
            "white_concrete", "orange_concrete", "magenta_concrete", "light_blue_concrete",
            "yellow_concrete", "lime_concrete", "pink_concrete", "gray_concrete",
            "light_gray_concrete", "cyan_concrete", "purple_concrete", "blue_concrete",
            "brown_concrete", "green_concrete", "red_concrete", "black_concrete",
            "white_concrete_powder", "orange_concrete_powder", "magenta_concrete_powder", "light_blue_concrete_powder",
            "yellow_concrete_powder", "lime_concrete_powder", "pink_concrete_powder", "gray_concrete_powder",
            "light_gray_concrete_powder", "cyan_concrete_powder", "purple_concrete_powder", "blue_concrete_powder",
            "brown_concrete_powder", "green_concrete_powder", "red_concrete_powder", "black_concrete_powder",
            "white_glazed_terracotta", "orange_glazed_terracotta", "magenta_glazed_terracotta", "light_blue_glazed_terracotta",
            "yellow_glazed_terracotta", "lime_glazed_terracotta", "pink_glazed_terracotta", "gray_glazed_terracotta",
            "light_gray_glazed_terracotta", "cyan_glazed_terracotta", "purple_glazed_terracotta", "blue_glazed_terracotta",
            "brown_glazed_terracotta", "green_glazed_terracotta", "red_glazed_terracotta", "black_glazed_terracotta",
            "white_stained_glass", "orange_stained_glass", "magenta_stained_glass", "light_blue_stained_glass",
            "yellow_stained_glass", "lime_stained_glass", "pink_stained_glass", "gray_stained_glass",
            "light_gray_stained_glass", "cyan_stained_glass", "purple_stained_glass", "blue_stained_glass",
            "brown_stained_glass", "green_stained_glass", "red_stained_glass", "black_stained_glass",
            "white_stained_glass_pane", "orange_stained_glass_pane", "magenta_stained_glass_pane", "light_blue_stained_glass_pane",
            "yellow_stained_glass_pane", "lime_stained_glass_pane", "pink_stained_glass_pane", "gray_stained_glass_pane",
            "light_gray_stained_glass_pane", "cyan_stained_glass_pane", "purple_stained_glass_pane", "blue_stained_glass_pane",
            "brown_stained_glass_pane", "green_stained_glass_pane", "red_stained_glass_pane", "black_stained_glass_pane",
            "white_bed", "orange_bed", "magenta_bed", "light_blue_bed",
            "yellow_bed", "lime_bed", "pink_bed", "gray_bed",
            "light_gray_bed", "cyan_bed", "purple_bed", "blue_bed",
            "brown_bed", "green_bed", "red_bed", "black_bed",
            "white_banner", "orange_banner", "magenta_banner", "light_blue_banner",
            "yellow_banner", "lime_banner", "pink_banner", "gray_banner",
            "light_gray_banner", "cyan_banner", "purple_banner", "blue_banner",
            "brown_banner", "green_banner", "red_banner", "black_banner",
            "white_shulker_box", "orange_shulker_box", "magenta_shulker_box", "light_blue_shulker_box",
            "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box", "gray_shulker_box",
            "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box",
            "brown_shulker_box", "green_shulker_box", "red_shulker_box", "black_shulker_box",
            "white_dye", "orange_dye", "magenta_dye", "light_blue_dye",
            "yellow_dye", "lime_dye", "pink_dye", "gray_dye",
            "light_gray_dye", "cyan_dye", "purple_dye", "blue_dye",
            "brown_dye", "green_dye", "red_dye", "black_dye",
            "white_terracotta", "orange_terracotta", "magenta_terracotta", "light_blue_terracotta",
            "yellow_terracotta", "lime_terracotta", "pink_terracotta", "gray_terracotta",
            "light_gray_terracotta", "cyan_terracotta", "purple_terracotta", "blue_terracotta",
            "brown_terracotta", "green_terracotta", "red_terracotta", "black_terracotta",
            "white_candle", "orange_candle", "magenta_candle", "light_blue_candle",
            "yellow_candle", "lime_candle", "pink_candle", "gray_candle",
            "light_gray_candle", "cyan_candle", "purple_candle", "blue_candle",
            "brown_candle", "green_candle", "red_candle", "black_candle"
    };
    private static final String TAG = "slf_marker";
    private static final float EXACT_RANGE = 0.20f;     // the same as in flashlight.glsl (GetFlashExact)
    private static final int EXACT_LEVELS = 104;
    private static Item[] exact;
    private static java.util.Set<Item> markerSet;

    private static void initExact() {
        if (exact != null) return;
        Item[] arr = new Item[EXACT_NAMES.length];
        java.util.Set<Item> set = new java.util.HashSet<>();
        for (Item m : MARKERS) set.add(m);
        for (int i = 0; i < arr.length; i++) {
            Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", EXACT_NAMES[i]));
            if (it == null || it == Items.AIR) it = Items.BARRIER;      // never happens in 1.19.2; keeps the code safe
            arr[i] = it;
            set.add(it);
        }
        markerSet = set;
        exact = arr;
    }

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

    private static boolean isMarker(ItemStack s) {
        if (s.isEmpty()) return false;
        // our marker carries a tag, so a real wool block, carpet and so on in the off hand is never taken for it (and never lost)
        return s.hasTag() && s.getTag().getBoolean(TAG) && markerSet != null && markerSet.contains(s.getItem());
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
        amount = target;      // the change is instant (one frame), both ways

        int stage = amount > 0.5f ? 1 : 0;
        boolean lit = FlashlightItem.isOn(p.getMainHandItem());
        if (!lit) {
            stage = 0;
            amount = 0f;
        }
        // the exact channel: the marker is there the whole time the flashlight is lit (focus or not)
        Item marker = null;
        if (lit && flashlightInHand(p)) {
            initExact();
            float hx = FlashlightClient.frameUpdate(p, true, e.renderTickTime);
            float t = Mth.clamp((hx + EXACT_RANGE) / (2f * EXACT_RANGE), 0f, 1f);
            int level = Math.round(t * (EXACT_LEVELS - 1));
            marker = exact[(stage > 0 ? EXACT_LEVELS : 0) + level];
        }
        if (marker != null) {
            if (!isMarker(p.getOffhandItem()) && !hasSaved) {
                saved = p.getOffhandItem().copy();
                hasSaved = true;
            }
            ItemStack ms = new ItemStack(marker);
            ms.getOrCreateTag().putBoolean(TAG, true);
            p.getInventory().offhand.set(0, ms);
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
            GuiComponent.blit(ps, i - 91 - 29, h - 23, 0, 24f, 22f, 29, 24, 256, 256);
        } else {
            GuiComponent.blit(ps, i + 91, h - 23, 0, 53f, 22f, 29, 24, 256, 256);
        }
        RenderSystem.disableBlend();
    }
}
