package com.ifels.cfx.create.mixin;

import com.ifels.cfx.create.access.BoundKeyAccess;
import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps the controller bridge from leaking derived input into a {@code KeyMapping} when
 * the train console is left.
 *
 * <h2>The defect this prevents</h2>
 * {@code ControlsHandler.stopControlling()} starts with:
 *
 * <pre>
 * ControlsUtil.getControls()
 *     .forEach(kb -&gt; kb.setDown(ControlsUtil.isActuallyPressed(kb)));
 * </pre>
 *
 * <p>The intent is "restore each console key to its real, physical state", which is why
 * Create polls GLFW here instead of asking {@code KeyMapping.isDown()}. The companion
 * {@code ControlsUtilMixin} widens {@code isActuallyPressed} to answer for controller
 * input as well, so this restore would write the <b>derived</b> value into the mapping:
 * pushing the stick diagonally when the console is left sets {@code keyUp} and
 * {@code keyLeft} down.</p>
 *
 * <p>Nothing clears that afterwards. Create stops running once the player has left the
 * console, so the per-tick {@code setDown(false)} sweep in {@code tick()} never happens
 * again. A stuck {@code keyUp} keeps {@code KeyboardInput.up} true, so
 * {@code forwardImpulse} stays non-zero and the player walks forward indefinitely even
 * with the stick centred and no key touched.</p>
 *
 * <p>It only surfaces on an abnormal exit, such as a train collision: on a normal exit the
 * per-tick sweep still runs afterwards and clears the mapping.</p>
 *
 * <h2>Why the restore polls GLFW instead of reading isDown()</h2>
 * {@code isDown} is not a safe stand-in for the physical key. With analog movement
 * switched off, ControlFlex routes the stick to the {@code move_*} actions, and those
 * write the same mappings through the ordinary KeyMapping channel, so {@code keyUp} is
 * legitimately down while the player drives. Restoring that value would keep the key
 * stuck for exactly the same reason. Polling the bound key reproduces Create's intent
 * literally: only a real key press survives the restore.
 *
 * <h2>Why the redirect targets forEach</h2>
 * The restore loop compiles into a synthetic lambda whose parameter is a {@code KeyMapping},
 * so naming {@code lambda$stopControlling$0} in {@code method = ...} embeds a descriptor
 * that is spelled differently on every platform and cannot come from one shared source
 * file. The {@code forEach} call has no such problem: its descriptor mentions only
 * {@code java.util} types, none of which is remapped. Redirecting it also scopes the change
 * precisely, because {@code tick()} has its own, separate {@code forEach} call.
 *
 * <p>The receiver is spelled {@code java.util.List} for every target. Create 1.20.1
 * declares {@code getControls()} as {@code Vector} and dispatches {@code forEach} with
 * {@code invokevirtual}, while 1.21.1 declares {@code List} and dispatches with
 * {@code invokeinterface}. Both are JDK types, so the interface spelling resolves on both
 * and keeps this one source file shared.</p>
 */
@Mixin(value = ControlsHandler.class, remap = false)
public abstract class ControlsHandlerRestoreMixin {

    /** Replaces the restore loop with one that reports real, physical key state. */
    @Redirect(
            method = "stopControlling()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"),
            remap = false)
    private static void cfxCreate$restorePhysicalStateOnly(List<KeyMapping> controls,
                                                           Consumer<KeyMapping> ignored) {
        for (KeyMapping kb : controls) {
            kb.setDown(cfxCreate$isPhysicallyDown(kb));
        }
    }

    /**
     * Mirrors {@code AllKeys.isKeyDown}/{@code isMouseButtonDown}, the check Create runs
     * in {@code isActuallyPressed}, with no bridge input layered on top.
     */
    private static boolean cfxCreate$isPhysicallyDown(KeyMapping kb) {
        InputConstants.Key bound = ((BoundKeyAccess) kb).cfxCreate$boundKey();
        if (bound == null || bound == InputConstants.UNKNOWN) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        long window = mc.getWindow().getWindow();
        if (bound.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(window, bound.getValue()) == GLFW.GLFW_PRESS;
    }
}
