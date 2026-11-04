package com.example.slenderflashlight.client;

import com.example.slenderflashlight.FlashlightItem;
import com.example.slenderflashlight.FlashlightNetwork;
import com.example.slenderflashlight.SlenderFlashlightMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Tells the server the state of the flashlight (0 = none, 1 = lit, 2 = lit and focused) when it changes and every 10 ticks
 * while it is lit, so Slenderman (the original's flashlight.isOnAndFocused()) knows about the light and the focus.
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, value = Dist.CLIENT)
public class FlashlightStateSender {
    private static int lastSent = -1;
    private static int sinceSent = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.getConnection() == null) {
            lastSent = -1;
            return;
        }
        int state = 0;
        if (p.isAlive() && FlashlightItem.isOn(p.getMainHandItem())) {
            state = FlashlightFocus.amount() > 0.5f ? 2 : 1;
        }
        sinceSent++;
        if (state != lastSent || (state > 0 && sinceSent >= 10)) {
            lastSent = state;
            sinceSent = 0;
            FlashlightNetwork.CHANNEL.sendToServer(new FlashlightNetwork.StateMessage(state));
        }
    }
}
