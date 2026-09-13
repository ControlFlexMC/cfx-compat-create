package com.ifels.cfx.create;

import com.ifels.controlflex.api.IControlFlexPlugin;

/**
 * ControlFlex plugin entry point for cfx-compat-create.
 *
 * <p>Registered via
 * {@code META-INF/services/com.ifels.controlflex.api.IControlFlexPlugin}.</p>
 *
 * <p>This bridge is intentionally passive: it installs no compat JSON and no guide
 * assets, and pushes no player state. The entire integration is the
 * {@code ControlsUtilMixin} injection, because the defect it fixes is
 * "Create's train console polls raw key state, which the analog movement channel
 * never publishes". Keeping the plugin inert means the bridge cannot change any
 * behaviour outside Create's own train-console input checks.</p>
 */
public class CfxCreatePlugin implements IControlFlexPlugin {

    public static final String MOD_ID = "cfx_compat_create";

    @Override
    public String getModId() {
        return MOD_ID;
    }

    @Override
    public void onControlFlexReady() {
        // The version floor is enforced by the loader metadata instead of a runtime
        // check, so there is nothing to do here.
    }
}
