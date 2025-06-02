package com.radexin.cubicchunks.mixin;

import com.radexin.cubicchunks.client.VerticalRenderDistanceOption;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(VideoSettingsScreen.class)
public class VideoSettingsScreenMixin {
    @Inject(method = "options", at = @At("RETURN"), cancellable = true)
    private static void cubicchunks$addVerticalRenderDistance(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] original = cir.getReturnValue();
        OptionInstance<?>[] modified = Arrays.copyOf(original, original.length + 1);
        
        // Find position after render distance
        int insertIdx = -1;
        for (int i = 0; i < original.length; i++) {
            if (original[i] == options.renderDistance()) {
                insertIdx = i + 1;
                break;
            }
        }
        
        // If render distance not found, append at the end
        if (insertIdx == -1) {
            insertIdx = original.length;
        }
        
        // Shift elements after insertIdx
        System.arraycopy(original, insertIdx, modified, insertIdx + 1, original.length - insertIdx);
        
        // Insert vertical render distance option
        modified[insertIdx] = VerticalRenderDistanceOption.create();
        
        cir.setReturnValue(modified);
    }
} 