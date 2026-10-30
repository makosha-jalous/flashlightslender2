package com.example.slenderflashlight.client;

import com.example.slenderflashlight.SlenderFlashlightMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Tells the game to load the hand model (it is not an item of its own, so it has to be registered by hand). */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FlashlightModels {
    private FlashlightModels() {}

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(FlashlightClient.HAND_MODEL);
    }
}
