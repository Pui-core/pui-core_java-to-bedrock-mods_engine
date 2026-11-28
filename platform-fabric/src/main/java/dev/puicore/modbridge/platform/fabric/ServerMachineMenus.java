package dev.puicore.modbridge.platform.fabric;

import dev.puicore.modbridge.core.adapter.Adapter;
import dev.puicore.modbridge.core.adapter.AdapterRegistry;
import dev.puicore.modbridge.core.adapter.AdapterRules;
import dev.puicore.modbridge.core.adapter.MachineSessions;
import dev.puicore.modbridge.core.adapter.MachineSessions.PlayerAccess;
import dev.puicore.modbridge.core.adapter.MachineSessions.Position;
import dev.puicore.modbridge.core.adapter.MachineSessions.Result;
import dev.puicore.modbridge.core.adapter.MachineSessions.Stack;
import dev.puicore.modbridge.core.adapter.MachineSessions.Target;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.geyser.api.GeyserApi;

/** Geyser のフォーム応答を Fabric のサーバースレッドへ渡す。 */
final class ServerMachineMenus {
    private final MachineSessions sessions = new MachineSessions(Duration.ofSeconds(30));
    private AdapterRegistry adapters;

    void install() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> loadAdapters());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (adapters != null) {
                try {
                    adapters.close();
                } finally {
                    adapters = null;
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sessions.disconnect(handler.getPlayer().getUUID()));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> player instanceof ServerPlayer serverPlayer
                && hand == InteractionHand.MAIN_HAND ? open(serverPlayer, hit.getBlockPos()) : InteractionResult.PASS);
    }

    private void loadAdapters() {
        var trusted = Arrays.stream(System.getProperty("modbridge.adapters.trusted", "").split(","))
                .map(String::strip).filter(id -> !id.isEmpty()).collect(Collectors.toUnmodifiableSet());
        FabricLoader loader = FabricLoader.getInstance();
        var mods = loader.getAllMods().stream()
                .map(mod -> mod.getMetadata())
                .collect(Collectors.toMap(m -> m.getId(), m -> m.getVersion().getFriendlyString()));
        var candidates = loader.getEntrypointContainers("modbridge-adapter", Adapter.class).stream()
                .map(entry -> new AdapterRegistry.Candidate(entry.getProvider().getMetadata().getId(), entry::getEntrypoint))
                .toList();
        adapters = AdapterRegistry.load(candidates, trusted, mods, Mode.A, rules());
        adapters.activeAdapters().forEach(adapter -> ModBridgeFabric.LOG.info("[modbridge] adapter active: {}", adapter.id()));
    }

    private static List<Adapter> rules() {
        String file = System.getProperty("modbridge.adapters.rules");
        if (file == null || file.isBlank()) {
            return List.of();
        }
        try {
            return AdapterRules.read(Path.of(file));
        } catch (IOException failure) {
            throw new IllegalStateException("modbridge: 加工アダプターのルールを読み込めません", failure);
        }
    }

    private InteractionResult open(ServerPlayer player, BlockPos pos) {
        if (adapters == null || player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        var level = player.level();
        String block = blockId(level, pos);
        var menu = adapters.menuForBlock(block).orElse(null);
        if (menu == null) {
            return InteractionResult.PASS;
        }
        UUID playerId = player.getUUID();
        var connection = GeyserApi.api().connectionByUuid(playerId);
        if (connection == null) {
            return InteractionResult.PASS;
        }
        var target = new Target(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ(), block);
        var view = sessions.open(playerId, target, menu, new ServerAccess(player)).orElse(null);
        if (view == null) {
            return InteractionResult.PASS;
        }
        MinecraftServer server = level.getServer();
        UUID token = view.token();
        var form = SimpleForm.builder().title(view.menu().title());
        view.menu().recipes().forEach(recipe -> form.button(recipe.label()));
        form.validResultHandler(response -> server.execute(() -> handle(server, playerId, token, response.clickedButtonId())));
        form.closedOrInvalidResultHandler(() -> server.execute(() -> sessions.cancel(playerId, token)));
        if (!connection.sendForm(form)) {
            sessions.cancel(playerId, token);
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    private void handle(MinecraftServer server, UUID playerId, UUID token, int choice) {
        ServerPlayer current = server.getPlayerList().getPlayer(playerId);
        if (current == null) {
            sessions.disconnect(playerId);
            return;
        }
        Result result = sessions.select(playerId, token, choice, new ServerAccess(current));
        if (result == Result.STALE) {
            return;
        }
        current.sendOverlayMessage(Component.literal(switch (result) {
            case APPLIED -> "加工しました";
            case INPUT_MISSING -> "加工に必要な材料が足りません";
            case OUTPUT_FULL -> "加工結果を入れる空きがありません";
            case EXPIRED -> "加工画面の期限が切れました";
            default -> "加工できませんでした。ブロックを操作して開き直してください";
        }));
    }

    private static String blockId(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString() : "";
    }

    private record ServerAccess(ServerPlayer player) implements PlayerAccess {
        @Override
        public Position position() {
            return new Position(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ());
        }

        @Override
        public boolean mayOperate(Target target) {
            return player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild
                    && player.mayInteract(player.level(), position(target));
        }

        @Override
        public String blockAt(Target target) {
            return blockId(player.level(), position(target));
        }

        private static BlockPos position(Target target) {
            return new BlockPos(target.x(), target.y(), target.z());
        }

        @Override
        public List<Stack> inventory() {
            Inventory inventory = player.getInventory();
            return IntStream.range(0, inventory.getNonEquipmentItems().size()).mapToObj(slot -> {
                ItemStack stack = inventory.getItem(slot);
                return stack.isEmpty() ? Stack.EMPTY : new Stack(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(), Math.max(stack.getCount(), stack.getMaxStackSize()),
                        stack.getComponentsPatch().isEmpty() && stack.getCount() <= stack.getMaxStackSize());
            }).toList();
        }

        @Override
        public int stackLimit(String itemId) {
            Item item = item(itemId);
            return item == null ? 0 : new ItemStack(item).getMaxStackSize();
        }

        @Override
        public boolean replaceInventory(List<Stack> expected, List<Stack> updated) {
            if (!inventory().equals(expected) || expected.size() != updated.size()) {
                return false;
            }
            var changes = new LinkedHashMap<Integer, ItemStack>();
            for (int slot = 0; slot < updated.size(); slot++) {
                Stack next = updated.get(slot);
                if (expected.get(slot).equals(next)) {
                    continue;
                }
                if (next.count() == 0) {
                    changes.put(slot, ItemStack.EMPTY);
                } else {
                    Item item = item(next.item());
                    if (item == null) {
                        return false;
                    }
                    changes.put(slot, new ItemStack(item, next.count()));
                }
            }
            Inventory inventory = player.getInventory();
            changes.forEach(inventory::setItem);
            inventory.setChanged();
            player.containerMenu.broadcastChanges();
            return true;
        }

        private static Item item(String id) {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        }
    }
}
