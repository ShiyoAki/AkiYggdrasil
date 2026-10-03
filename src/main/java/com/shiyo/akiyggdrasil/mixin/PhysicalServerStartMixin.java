package com.shiyo.akiyggdrasil.mixin;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.shiyo.akiyggdrasil.auth.AkiAuthService;
import net.minecraft.server.Main;
import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.File;

/**
 * Swaps the auth service handed to {@code Services.create(...)} in
 * {@link Main#main(String[])} for the multi-source {@link AkiAuthService}.
 *
 * <p>Vanilla/NeoForge builds
 * {@code Services.create(new YggdrasilAuthenticationService(proxy), file)} while
 * Paper-family servers such as Youer build
 * {@code Services.create(new PaperAuthenticationService(proxy), file)}. Both call
 * sites have the same {@code Services.create(YggdrasilAuthenticationService, File)}
 * descriptor, so redirecting the call (instead of the {@code new} expression) makes
 * the mixin work on either kind of server.
 */
@Mixin(value = Main.class, priority = 50)
abstract class PhysicalServerStartMixin {

    @Redirect(
        method = "main",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/Services;create(Lcom/mojang/authlib/yggdrasil/YggdrasilAuthenticationService;Ljava/io/File;)Lnet/minecraft/server/Services;"
        ),
        remap = false
    )
    private static Services createAkiServices(YggdrasilAuthenticationService service, File file) {
        AkiAuthService aki = new AkiAuthService(service.getProxy());
        aki.enableReload();
        return Services.create(aki, file);
    }
}
