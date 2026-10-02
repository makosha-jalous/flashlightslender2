package com.example.slenderflashlight;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Flashlight: no battery, right click toggles it with a click. State lives in the stack's "on" tag. */
public class FlashlightItem extends Item {

    public FlashlightItem(Properties props) {
        super(props);
    }

    public static boolean isOn(ItemStack stack) {
        return stack.getItem() instanceof FlashlightItem && stack.hasTag() && stack.getTag().getBoolean("on");
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean on = !isOn(stack);
        stack.getOrCreateTag().putBoolean("on", on);
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
                on ? SoundEvents.STONE_BUTTON_CLICK_ON : SoundEvents.STONE_BUTTON_CLICK_OFF,
                SoundSource.PLAYERS, 0.6f, on ? 1.9f : 1.7f);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** Toggling changes NBT; without this the hand would "bounce" (re-equip) on every click. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged;
    }
}
