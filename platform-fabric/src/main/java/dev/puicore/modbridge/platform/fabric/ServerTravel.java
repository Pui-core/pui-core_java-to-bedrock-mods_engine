package dev.puicore.modbridge.platform.fabric;

import dev.puicore.modbridge.core.ops.Operations;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.geysermc.geyser.api.GeyserApi;

/** Geyser の Bedrock セッションを、運用者が登録した独立バックエンドへ移す。 */
final class ServerTravel {
    private ServerTravel() {}

    static void install(String document, String origin) {
        if (document == null || document.isBlank() || origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("modbridge.travelConfig と modbridge.travelOrigin の両方を指定する");
        }
        Operations.Plan plan;
        try {
            plan = Operations.read(Path.of(document));
        } catch (IOException failure) {
            throw new IllegalStateException("Bedrock 移動先の運用設定を読み込めない", failure);
        }
        if (plan.topology() != Operations.Topology.BEDROCK_TRANSFER
                || plan.backends().stream().noneMatch(backend -> backend.id().equals(origin) && backend.kind() == Operations.Kind.GEYSER)) {
            throw new IllegalArgumentException("BEDROCK_TRANSFER とこの Geyser バックエンドの ID を指定する");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var command = Commands.literal("modbridge-travel");
            for (var destination : plan.backends()) {
                if (!destination.id().equals(origin)) {
                    command.then(Commands.literal(destination.id()).executes(context -> transfer(context.getSource(), destination)));
                }
            }
            dispatcher.register(command);
        });
    }

    private static int transfer(CommandSourceStack source, Operations.Backend destination)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        GeyserApi api = GeyserApi.api();
        var connection = api == null ? null : api.connectionByUuid(player.getUUID());
        if (connection == null) {
            source.sendFailure(Component.literal("Bedrock セッションから実行してください。"));
            return 0;
        }
        var gate = ModBridgeFabric.DECISION.get();
        if (gate == null || !gate.allowBedrock()) {
            source.sendFailure(Component.literal("このサーバーの接続判定が移動を許可していません。"));
            return 0;
        }

        var endpoint = destination.client();
        try {
            if (connection.transfer(endpoint.host(), endpoint.port())) {
                return 1;
            }
        } catch (RuntimeException failure) {
            ModBridgeFabric.LOG.warn("[modbridge] Bedrock transfer failed for destination {}", destination.id(), failure);
        }
        source.sendFailure(Component.literal("移動を送信できませんでした。Bedrock のサーバー一覧から "
                + endpoint.host() + ":" + endpoint.port() + " に接続してください。"));
        return 0;
    }
}
