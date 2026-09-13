package com.ifels.cfx.create;

/**
 * The six input slots of Create's train console. Pure logic with no Minecraft
 * dependency, so the mapping rules can be unit tested without launching the game.
 *
 * <h2>Why this exists</h2>
 * Create's train console reads <b>raw key polling</b>, not {@code KeyMapping.isDown()}:
 * <pre>
 * ControlsHandler.tick() -> ControlsUtil.isActuallyPressed(kb)
 *                        -> AllKeys.isKeyDown(code) -> InputConstants.isKeyDown(window, code)
 * </pre>
 * ControlFlex's analog movement channel takes over the MOVE group and then skips digital
 * dispatch for it entirely (the candidate filter in {@code BindingMapper} plus the release
 * on the takeover edge). As a result {@code GlfwPollKeyState} never receives the movement
 * keys, so {@code isKeyDown(window, W)} stays false while the stick is pushed and the train
 * never sees a throttle input.
 *
 * <h2>Responsibility</h2>
 * Translate "what the player is actually doing this tick" plus ControlFlex's action state
 * into "should this console key count as pressed". The companion mixin ORs the answer into
 * Create's own check. Only {@code ControlsUtil.isActuallyPressed} is affected: no global
 * synthetic key state is written and no {@code KeyMapping} is touched.
 *
 * <h2>Why an enum instead of GLFW key codes</h2>
 * The console keys are identified by the <b>object identity</b> of
 * {@code mc.options.keyUp/keyDown/...}. Those fields exist under the same names on both
 * loaders on this branch (Forge and Fabric), whereas the field holding a
 * {@code KeyMapping}'s bound key is named differently between them, so reading it would
 * break the shared source set. Keying off identity also keeps working when the player
 * rebinds the console keys.
 *
 * <h2>Why the player's input impulse instead of the raw stick</h2>
 * {@code LocalPlayer.input.forwardImpulse/leftImpulse} is a tick-level value that has already
 * been through ControlFlex's analog policy and response curve, and it is <b>the same value
 * the player's own movement uses</b>. Reading it makes "the player is moving" and "the console
 * sees a key" impossible to disagree. The API's {@code getControllerState()} would instead
 * mix render-frame interpolation with the tick snapshot.
 *
 * <h2>Sign convention and threshold</h2>
 * Matches vanilla {@code Input}: {@code forwardImpulse > 0} is forward and
 * {@code leftImpulse > 0} is left. The threshold mirrors ControlFlex's
 * {@code Thresholds.STICK_THRESHOLD} (0.3), which is a superset of the 0.15 input dead zone,
 * so the console can never report "not pressed" while the player is genuinely moving.
 */
public enum CreateTrainControl {

    /** Throttle forward - Create's {@code keyUp}, controls index 0. */
    FORWARD,
    /** Reverse - Create's {@code keyDown}, controls index 1. */
    BACKWARD,
    /** Steer left - Create's {@code keyLeft}, controls index 2. */
    LEFT,
    /** Steer right - Create's {@code keyRight}, controls index 3. */
    RIGHT,
    /** Horn - Create's {@code keyJump}, controls index 4. */
    HORN,
    /** Brake - Create's {@code keyShift}, controls index 5. */
    BRAKE;

    /** Mirrors ControlFlex's {@code Thresholds.STICK_THRESHOLD}. */
    public static final float MOVE_THRESHOLD = 0.3f;

    /**
     * Whether this slot is driven by the player's movement axes.
     *
     * <p>Movement slots are derived from the input impulses; the remaining slots
     * ({@link #HORN}, {@link #BRAKE}) have no stick semantics and are driven by
     * ControlFlex's action state instead. Callers use this to avoid querying the
     * action state for movement slots, and vice versa.</p>
     */
    public boolean isMovement() {
        return switch (this) {
            case FORWARD, BACKWARD, LEFT, RIGHT -> true;
            case HORN, BRAKE -> false;
        };
    }

    /**
     * The ControlFlex action that drives this slot, or {@code null} when the slot has no
     * action of its own.
     *
     * <p>Movement slots need both sources. ControlFlex writes the player's impulses only
     * while analog movement is enabled; with it switched off the stick is routed to these
     * digital actions instead and {@code forwardImpulse}/{@code leftImpulse} stay zero, so
     * reading the impulses alone would leave the train unable to move. Horn and brake are
     * action-only and return {@code null} here.</p>
     */
    public String actionId() {
        return switch (this) {
            case FORWARD -> "move_forward";
            case BACKWARD -> "move_backward";
            case LEFT -> "move_left";
            case RIGHT -> "move_right";
            case HORN, BRAKE -> null;
        };
    }

    /**
     * Whether this console key should count as pressed right now.
     *
     * @param forwardImpulse the player's forward/back movement this tick (positive = forward)
     * @param leftImpulse    the player's left/right movement this tick (positive = left)
     * @param jumpHeld       whether ControlFlex's {@code jump} action is active
     * @param sneakHeld      whether ControlFlex's {@code sneak} action is active
     * @return true if the console should treat this key as held down
     */
    public boolean isHeld(float forwardImpulse, float leftImpulse, boolean jumpHeld, boolean sneakHeld) {
        return switch (this) {
            case FORWARD -> forwardImpulse >= MOVE_THRESHOLD;
            case BACKWARD -> forwardImpulse <= -MOVE_THRESHOLD;
            case LEFT -> leftImpulse >= MOVE_THRESHOLD;
            case RIGHT -> leftImpulse <= -MOVE_THRESHOLD;
            case HORN -> jumpHeld;
            case BRAKE -> sneakHeld;
        };
    }
}
