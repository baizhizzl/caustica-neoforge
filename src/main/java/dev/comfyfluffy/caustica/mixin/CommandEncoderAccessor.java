package dev.comfyfluffy.caustica.mixin;

import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendCommandEncoder.class)
public interface CommandEncoderAccessor {
	@Accessor("backend")
	CommandEncoderBackend caustica$getBackend();
}
