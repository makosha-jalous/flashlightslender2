package com.example.slenderflashlight;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Flashlight: no battery, right click toggles it.
 * "Off" and "on" are two separate items (flashlight / flashlight_on), so the BSL shader
 * can tell them apart by item id and turn the spotlight on only for the lit one.
 */
public class FlashlightItem extends Item {

    private final boolean lit;

    public FlashlightItem(Properties props, boolean lit) {
        super(props);
        this.lit = lit;
    }

    public static boolean isOn(ItemStack stack) {
        return stack.getItem() instanceof FlashlightItem f && f.lit;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Item target = lit ? SlenderFlashlightMod.FLASHLIGHT.get() : SlenderFlashlightMod.FLASHLIGHT_ON.get();

        ItemStack swapped = new ItemStack(target, stack.getCount());
        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag().copy();
            tag.remove("on");
            if (!tag.isEmpty()) swapped.setTag(tag);
        }

        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
                SlenderFlashlightMod.FLASHLIGHT_CLICK.get(),
                SoundSource.PLAYERS, 0.7F, 1.0F);
        return InteractionResultHolder.sidedSuccess(swapped, level.isClientSide());
    }

    /** Both items share one name, so lang files stay unchanged. */
    @Override
    public String getDescriptionId() {
        return "item." + SlenderFlashlightMod.ID + ".flashlight";
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged;
    }
}
