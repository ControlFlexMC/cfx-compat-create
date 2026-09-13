package com.ifels.cfx.create;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping rules for the six input slots of Create's train console.
 *
 * <p>Create's {@code ControlsUtil.isActuallyPressed()} polls raw GLFW key state, and
 * ControlFlex's analog movement channel skips digital dispatch for the MOVE group, so
 * {@code InputConstants.isKeyDown(window, W)} stays false while the stick is pushed and the
 * train never receives a throttle input. {@link CreateTrainControl} converts the player's
 * movement impulses and ControlFlex's action state into "is this console key held", and the
 * mixin ORs that into Create's check.</p>
 *
 * <p>Sign convention matches vanilla {@code Input}: a positive {@code forwardImpulse} means
 * forward and a positive {@code leftImpulse} means left.</p>
 */
class CreateTrainControlTest {

    // ===== Movement slots =====

    @Test
    void noMovement_pressesNoDirection() {
        assertFalse(CreateTrainControl.FORWARD.isHeld(0f, 0f, false, false));
        assertFalse(CreateTrainControl.BACKWARD.isHeld(0f, 0f, false, false));
        assertFalse(CreateTrainControl.LEFT.isHeld(0f, 0f, false, false));
        assertFalse(CreateTrainControl.RIGHT.isHeld(0f, 0f, false, false));
    }

    @Test
    void movingForward_pressesForwardOnly() {
        assertTrue(CreateTrainControl.FORWARD.isHeld(0.8f, 0f, false, false));
        assertFalse(CreateTrainControl.BACKWARD.isHeld(0.8f, 0f, false, false));
        assertFalse(CreateTrainControl.LEFT.isHeld(0.8f, 0f, false, false));
        assertFalse(CreateTrainControl.RIGHT.isHeld(0.8f, 0f, false, false));
    }

    @Test
    void movingBackward_pressesBackwardOnly() {
        assertTrue(CreateTrainControl.BACKWARD.isHeld(-0.8f, 0f, false, false));
        assertFalse(CreateTrainControl.FORWARD.isHeld(-0.8f, 0f, false, false));
    }

    @Test
    void movingLeft_pressesLeftOnly() {
        assertTrue(CreateTrainControl.LEFT.isHeld(0f, 0.8f, false, false));
        assertFalse(CreateTrainControl.RIGHT.isHeld(0f, 0.8f, false, false));
    }

    @Test
    void movingRight_pressesRightOnly() {
        assertTrue(CreateTrainControl.RIGHT.isHeld(0f, -0.8f, false, false));
        assertFalse(CreateTrainControl.LEFT.isHeld(0f, -0.8f, false, false));
    }

    @Test
    void diagonalMovement_pressesBothAxes() {
        // Forward and left at the same time.
        assertTrue(CreateTrainControl.FORWARD.isHeld(0.7f, 0.7f, false, false));
        assertTrue(CreateTrainControl.LEFT.isHeld(0.7f, 0.7f, false, false));
        assertFalse(CreateTrainControl.BACKWARD.isHeld(0.7f, 0.7f, false, false));
        assertFalse(CreateTrainControl.RIGHT.isHeld(0.7f, 0.7f, false, false));
    }

    // ===== Dead zone boundaries =====

    @Test
    void belowThreshold_pressesNoDirection() {
        assertFalse(CreateTrainControl.FORWARD.isHeld(0.29f, 0f, false, false));
        assertFalse(CreateTrainControl.RIGHT.isHeld(0f, -0.29f, false, false));
    }

    @Test
    void exactlyAtThreshold_presses() {
        assertTrue(CreateTrainControl.FORWARD.isHeld(0.3f, 0f, false, false));
        assertTrue(CreateTrainControl.RIGHT.isHeld(0f, -0.3f, false, false));
    }

    // ===== Movement slots ignore action state =====

    @Test
    void movementControls_ignoreActionState() {
        // Holding sneak/jump must not invent a direction, or the train would roll
        // away whenever the player crouches.
        assertFalse(CreateTrainControl.FORWARD.isHeld(0f, 0f, true, true));
        assertFalse(CreateTrainControl.BACKWARD.isHeld(0f, 0f, true, true));
        assertFalse(CreateTrainControl.LEFT.isHeld(0f, 0f, true, true));
        assertFalse(CreateTrainControl.RIGHT.isHeld(0f, 0f, true, true));
    }

    // ===== Horn and brake =====

    @Test
    void actionHeld_pressesTheMatchingControl() {
        assertTrue(CreateTrainControl.HORN.isHeld(0f, 0f, true, false));
        assertTrue(CreateTrainControl.BRAKE.isHeld(0f, 0f, false, true));
    }

    @Test
    void actionNotHeld_pressesNothing() {
        assertFalse(CreateTrainControl.HORN.isHeld(0f, 0f, false, false));
        assertFalse(CreateTrainControl.BRAKE.isHeld(0f, 0f, false, false));
    }

    @Test
    void actionControls_doNotLeakIntoEachOther() {
        assertFalse(CreateTrainControl.HORN.isHeld(0f, 0f, false, true));
        assertFalse(CreateTrainControl.BRAKE.isHeld(0f, 0f, true, false));
    }

    @Test
    void actionControls_ignoreMovementImpulse() {
        // Pushing the stick must not honk or brake.
        assertFalse(CreateTrainControl.HORN.isHeld(0.9f, 0.9f, false, false));
        assertFalse(CreateTrainControl.BRAKE.isHeld(0.9f, 0.9f, false, false));
    }

    // ===== Signal source classification =====

    @Test
    void movementSlots_areClassifiedAsMovement() {
        assertTrue(CreateTrainControl.FORWARD.isMovement());
        assertTrue(CreateTrainControl.BACKWARD.isMovement());
        assertTrue(CreateTrainControl.LEFT.isMovement());
        assertTrue(CreateTrainControl.RIGHT.isMovement());
    }

    @Test
    void actionSlots_areNotClassifiedAsMovement() {
        // This drives whether the mixin reads impulses or ControlFlex's action state:
        // a wrong answer here would silently stop the horn/brake or the throttle.
        assertFalse(CreateTrainControl.HORN.isMovement());
        assertFalse(CreateTrainControl.BRAKE.isMovement());
    }

    // ===== Digital-action fallback source =====

    @Test
    void movementSlotsNameTheirControlFlexAction() {
        // Needed because ControlFlex only writes impulses while analog movement is on.
        // With it off the stick is routed to these actions and the impulses stay zero,
        // which is the "stick no longer drives the train" regression.
        assertEquals("move_forward", CreateTrainControl.FORWARD.actionId());
        assertEquals("move_backward", CreateTrainControl.BACKWARD.actionId());
        assertEquals("move_left", CreateTrainControl.LEFT.actionId());
        assertEquals("move_right", CreateTrainControl.RIGHT.actionId());
    }

    @Test
    void actionOnlySlotsHaveNoMovementAction() {
        // Horn and brake are read from jump/sneak directly; giving them a move_* action
        // would make the stick honk or brake.
        assertNull(CreateTrainControl.HORN.actionId());
        assertNull(CreateTrainControl.BRAKE.actionId());
    }

    @Test
    void everyMovementSlotHasAFallbackAction() {
        for (CreateTrainControl control : CreateTrainControl.values()) {
            if (control.isMovement()) {
                assertNotNull(control.actionId(),
                        control + " is a movement slot and needs a digital fallback action");
            }
        }
    }
}
