package dev.comfyfluffy.caustica;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = CausticaMod.MOD_ID, dist = Dist.CLIENT)
public final class CausticaMod {
	public static final String MOD_ID = "caustica";
	public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

	public CausticaMod() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		CausticaConfig.ensureRegistered();
		CausticaConfig.saveIfMissing();
		LOGGER.info("Caustica initialized (client); config: {}", CausticaConfig.configPath());
	}
}
