package com.enderio.conduits.common.init;

import com.enderio.EnderIO;
import com.enderio.api.conduit.ConduitTypes;
import com.enderio.api.conduit.IConduitType;
import com.enderio.conduits.common.blockentity.PowerConduitType;
import com.enderio.conduits.common.blockentity.SimpleConduitType;
import com.enderio.conduits.common.blockentity.TieredConduit;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;

public class EnderConduitTypes {

    public static final RegistryObject<IConduitType> POWER = powerConduit(1);
    public static final RegistryObject<IConduitType> POWER2 = powerConduit(2);
    public static final RegistryObject<IConduitType> POWER3 = powerConduit(3);

    public static final RegistryObject<IConduitType> REDSTONE = ConduitTypes.CONDUIT_TYPES.register("redstone", () -> new SimpleConduitType(EnderIO.loc("block/conduit/redstone")));

    private static RegistryObject<IConduitType> powerConduit(int tier) {
        return ConduitTypes.CONDUIT_TYPES.register("power" + tier, () -> new PowerConduitType(EnderIO.loc("block/conduit/power" + tier), tier, null));
    }

    public static void register() {}
}