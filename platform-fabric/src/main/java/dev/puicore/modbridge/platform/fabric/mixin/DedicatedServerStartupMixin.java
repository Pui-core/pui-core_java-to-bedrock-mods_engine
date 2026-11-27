package dev.puicore.modbridge.platform.fabric.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.puicore.modbridge.platform.fabric.ModBridgeFabric;
import net.minecraft.server.Main;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
abstract class DedicatedServerStartupMixin {
    // Main はデータパックを読み込んだ後、SERVER_STARTING に到達する前に level.dat を保存する。
    @Inject(method = "main", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/WorldStem;registries()Lnet/minecraft/core/LayeredRegistryAccess;"))
    private static void modbridge$checkStartup(CallbackInfo callback,
            @Local WorldStem world, @Local LevelStorageAccess storage) {
        try {
            ModBridgeFabric.checkStartup(world.dataPackResources().getRecipeManager());
        } catch (RuntimeException | Error failure) {
            // サーバーを生成しない場合は Main が開いたリソースとワールドロックをここで解放する。
            try (storage; world) {
                throw failure;
            } catch (Throwable blocked) {
                // Main が例外を捕まえると初期化済み MOD のスレッドで JVM が残る。
                LoggerFactory.getLogger("modbridge").error("[modbridge] startup blocked", blocked);
                System.exit(1);
            }
        }
    }
}
