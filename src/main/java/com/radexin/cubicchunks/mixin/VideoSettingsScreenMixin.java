package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.client.VerticalRenderDistanceOption;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(VideoSettingsScreen.class)
public class VideoSettingsScreenMixin {
    @Inject(method = "options", at = @At("RETURN"), cancellable = true, remap = false)
    private static void cubicchunks$addVerticalRenderDistance(net.minecraft.client.Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] original = cir.getReturnValue();
        OptionInstance<?>[] modified = new OptionInstance[original.length + 1];
        int insertIdx = -1;
        for (int i = 0; i < original.length; i++) {
            modified[i] = original[i];
            if (original[i] == options.renderDistance()) {
                insertIdx = i + 1;
            }
        }
        if (insertIdx == -1) {
            insertIdx = original.length;
        }
        // Shift elements after insertIdx
        for (int i = original.length; i > insertIdx; i--) {
            modified[i] = modified[i - 1];
        }
        modified[insertIdx] = VerticalRenderDistanceOption.create();
        cir.setReturnValue(modified);
    }
} 