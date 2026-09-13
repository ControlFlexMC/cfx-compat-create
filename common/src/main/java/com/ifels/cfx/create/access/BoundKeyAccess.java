package com.ifels.cfx.create.access;

import com.mojang.blaze3d.platform.InputConstants;

/** Implemented on {@code KeyMapping} by {@code KeyMappingBoundKeyMixin}. */
public interface BoundKeyAccess {

    /** @return the key this mapping is currently bound to, or {@code UNKNOWN} */
    InputConstants.Key cfxCreate$boundKey();
}
