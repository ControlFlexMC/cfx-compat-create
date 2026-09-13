package com.ifels.cfx.create;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * cfx-compat-create: ControlFlex x Create compat mod (MC 1.20.1 Forge).
 *
 * <p>The whole integration is a single injection point:
 * {@code com.ifels.cfx.create.mixin.ControlsUtilMixin} merges ControlFlex controller input
 * into Create's train-console key polling. {@link CreateTrainControl} holds the mapping
 * rules. There are no key bindings, no config and no other hooks.</p>
 */
@Mod(CfxCreateMod.MOD_ID)
public class CfxCreateMod {

    public static final String MOD_ID = "cfx_compat_create";
    private static final Logger LOGGER = LogManager.getLogger("cfx-compat-create");

    public CfxCreateMod() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(this::logTargetPresent);
    }

    /**
     * Logs that Create's train-console input class is present.
     *
     * <p>This is a diagnostic breadcrumb only: a signature change in
     * {@code ControlsUtil.isActuallyPressed} cannot be detected from here. It is caught by the
     * mixin config's {@code required: true}, which fails loudly at load time instead.</p>
     */
    private void logTargetPresent() {
        try {
            Class.forName("com.simibubi.create.foundation.utility.ControlsUtil");
            LOGGER.info("Create train-console input bridge active (ControlsUtil present)");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("ControlsUtil not found; Create train-console bridge is a no-op ({})",
                    e.getMessage());
        }
    }
}
