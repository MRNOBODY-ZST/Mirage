package xyz.tofumc.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerWorldAccessor {
    @Accessor("noSave")
    boolean getNoSave();

    @Accessor("noSave")
    void setNoSave(boolean noSave);
}
