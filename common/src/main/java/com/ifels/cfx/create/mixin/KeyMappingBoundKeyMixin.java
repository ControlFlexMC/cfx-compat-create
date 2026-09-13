package com.ifels.cfx.create.mixin;

import com.ifels.cfx.create.access.BoundKeyAccess;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a {@code KeyMapping}'s bound key so the console restore can poll the real key.
 *
 * <p>{@code KeyMapping.key} is private, and its name differs per platform (SRG on Forge,
 * intermediary on Fabric). {@code @Shadow} lets Mixin resolve it through the mapping
 * refmap that the annotation processor already produces for this source set, which is the
 * same technique ControlFlex uses for its own key accessor.</p>
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingBoundKeyMixin implements BoundKeyAccess {

    @Shadow
    private InputConstants.Key key;

    @Override
    public InputConstants.Key cfxCreate$boundKey() {
        return key;
    }
}
