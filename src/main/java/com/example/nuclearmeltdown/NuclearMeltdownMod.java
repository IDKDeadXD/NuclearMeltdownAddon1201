package com.example.nuclearmeltdown;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(NuclearMeltdownMod.MOD_ID)
public class NuclearMeltdownMod {

    public static final String MOD_ID = "nuclearmeltdownaddon";

    public NuclearMeltdownMod() {
        MinecraftForge.EVENT_BUS.register(NuclearExplosionHandler.class);
    }
}
