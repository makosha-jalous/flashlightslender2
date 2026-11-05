package com.example.slenderflashlight;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The one network channel of the flashlight mod: the CLIENT tells the SERVER in what state the flashlight is
 * (0 = no lit flashlight in the main hand, 1 = lit, 2 = lit and focused), because the focus exists only on the client.
 * The server writes the state into the player's own data; the Slenderman mod reads these two keys (without using any class of this mod):
 *   "slenderflashlight_state" (int) and "slenderflashlight_state_time" (the game time of the last message).
 * The channel registers itself (no change in SlenderFlashlightMod is needed).
 */
@Mod.EventBusSubscriber(modid = SlenderFlashlightMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FlashlightNetwork {
    private static final String PROTOCOL = "1";
    public static final String STATE_KEY = "slenderflashlight_state";
    public static final String TIME_KEY = "slenderflashlight_state_time";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SlenderFlashlightMod.ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private FlashlightNetwork() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(FlashlightNetwork::register);
    }

    /** a plain method (not a short lambda): registerMessage returns a type that is not public. */
    private static void register() {
        CHANNEL.registerMessage(0, StateMessage.class,
                StateMessage::encode, StateMessage::decode, StateMessage::handle);
    }

    /** client -> server: the state of the flashlight. */
    public static final class StateMessage {
        private final int state;

        public StateMessage(int state) {
            this.state = state;
        }

        public static void encode(StateMessage message, FriendlyByteBuf buf) {
            buf.writeByte(message.state);
        }

        public static StateMessage decode(FriendlyByteBuf buf) {
            return new StateMessage(buf.readByte());
        }

        public static void handle(StateMessage message, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                CompoundTag data = player.getPersistentData();
                data.putInt(STATE_KEY, Math.max(0, Math.min(2, message.state)));
                data.putLong(TIME_KEY, player.level.getGameTime());
            });
            context.setPacketHandled(true);
        }
    }
}
