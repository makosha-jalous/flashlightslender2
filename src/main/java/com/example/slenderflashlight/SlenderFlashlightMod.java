package com.example.slenderflashlight;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(SlenderFlashlightMod.ID)
public class SlenderFlashlightMod {
    public static final String ID = "slenderflashlight";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ID);

    public static final RegistryObject<Item> FLASHLIGHT = ITEMS.register("flashlight",
            () -> new FlashlightItem(new Item.Properties().stacksTo(1).tab(CreativeModeTab.TAB_TOOLS)));

    /** Toggle sound; the .ogg lives at assets/slenderflashlight/sounds/flashlightclick.ogg. */
    public static final RegistryObject<SoundEvent> FLASHLIGHT_CLICK = SOUNDS.register("flashlightclick",
            () -> new SoundEvent(new ResourceLocation(ID, "flashlightclick")));

    public SlenderFlashlightMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        SOUNDS.register(bus);
    }
}