package com.ifels.cfx.create.mixin;

import com.ifels.cfx.create.CreateTrainControl;
import com.ifels.controlflex.api.ControlFlexApi;
import com.ifels.controlflex.api.IActionStateProvider;
import com.simibubi.create.foundation.utility.ControlsUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Create's train console see controller input.
 *
 * <h2>Why this injection point</h2>
 * The console polls raw key state through {@code ControlsUtil.isActuallyPressed(kb)} (which
 * bottoms out in {@code InputConstants.isKeyDown}) rather than {@code KeyMapping.isDown()}.
 * Once ControlFlex's analog movement takes over the MOVE group it publishes no digital key
 * state at all, so that check is constantly false while the stick is pushed. OR-ing
 * controller input into the <b>result</b> fixes it without forging global key state.
 *
 * <h2>Why no "is the player driving?" check is needed</h2>
 * {@code isActuallyPressed} is only called by Create's train console code
 * ({@code ControlsHandler.tick()}, {@code ControlsHandler.stopControlling()}, and
 * {@code TrainHUD} behind its driving guard). "This method was called" therefore already
 * means "the player is at a train console", so no reflection into Create internals and no
 * driving-state tracking is required.
 *
 * <h2>How the console keys are identified</h2>
 * By the object identity of {@code mc.options.keyUp} and friends. The field holding a
 * {@code KeyMapping}'s bound key is named differently on Forge and on Fabric, so reading it
 * would break the shared source set; {@code mc.options.key*} exists under the same name on
 * both loaders.
 *
 * <h2>Boundaries</h2>
 * <ul>
 *   <li>The original result is returned untouched when the real key is already down, which
 *       preserves keyboard priority.</li>
 *   <li>When ControlFlex is unavailable, or the player/input is not ready yet, the check
 *       degrades to the original value.</li>
 *   <li>Read-only: no global state is written.</li>
 * </ul>
 *
 * <p>Declared client-only in both loaders' metadata, matching {@code ControlsUtil}.</p>
 */
@Mixin(value = ControlsUtil.class, remap = false)
public abstract class ControlsUtilMixin {

    @Inject(method = "isActuallyPressed", at = @At("RETURN"), cancellable = true, remap = false)
    private static void cfxCreate$applyControllerInput(KeyMapping kb,
                                                       CallbackInfoReturnable<Boolean> cir) {
        // Real key already down - keyboard priority, leave the result alone.
        if (kb == null || cir.getReturnValueZ()) {
            return;
        }
        if (!ControlFlexApi.isAvailable()) {
            return;
        }
        CreateTrainControl control = cfxCreate$resolve(kb);
        if (control == null) {
            return; // Not a console slot we drive.
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.input == null) {
            return;
        }

        // Movement slots take the player's own impulses; horn and brake take action state.
        // Movement additionally falls back to its digital action, because ControlFlex only
        // writes impulses while analog movement is enabled: with it switched off the stick
        // is routed to the move_* actions instead and the impulses stay zero.
        boolean held;
        if (control.isMovement()) {
            held = control.isHeld(player.input.forwardImpulse, player.input.leftImpulse, false, false);
            if (!held && control.actionId() != null) {
                held = cfxCreate$actionHeld(control.actionId());
            }
        } else {
            held = control.isHeld(0f, 0f, cfxCreate$actionHeld("jump"), cfxCreate$actionHeld("sneak"));
        }

        if (held) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Maps a console key to its semantic slot.
     *
     * <p>The identity comparison mirrors the fixed six-tuple returned by
     * {@code ControlsUtil.getControls()} (forward/back/left/right/jump/sneak), which is the
     * same order the console ships to the server as key indices.</p>
     */
    private static CreateTrainControl cfxCreate$resolve(KeyMapping kb) {
        Options options = Minecraft.getInstance().options;
        if (options == null) {
            return null;
        }
        if (kb == options.keyUp) return CreateTrainControl.FORWARD;
        if (kb == options.keyDown) return CreateTrainControl.BACKWARD;
        if (kb == options.keyLeft) return CreateTrainControl.LEFT;
        if (kb == options.keyRight) return CreateTrainControl.RIGHT;
        if (kb == options.keyJump) return CreateTrainControl.HORN;
        if (kb == options.keyShift) return CreateTrainControl.BRAKE;
        return null;
    }

    /** Queries a ControlFlex game-layer action; treats a missing API as "not held". */
    private static boolean cfxCreate$actionHeld(String actionId) {
        IActionStateProvider actions = ControlFlexApi.getActionStateProvider();
        return actions != null && actions.isGameActionActive(actionId);
    }
}
