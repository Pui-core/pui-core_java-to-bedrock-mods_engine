package dev.puicore.modbridge.core.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.puicore.modbridge.core.adapter.Adapter.Capability;
import dev.puicore.modbridge.core.adapter.Adapter.Descriptor;
import dev.puicore.modbridge.core.adapter.Adapter.ItemAmount;
import dev.puicore.modbridge.core.adapter.Adapter.Menu;
import dev.puicore.modbridge.core.adapter.Adapter.Recipe;
import dev.puicore.modbridge.core.adapter.Adapter.Registrar;
import dev.puicore.modbridge.core.adapter.MachineSessions.PlayerAccess;
import dev.puicore.modbridge.core.adapter.MachineSessions.Position;
import dev.puicore.modbridge.core.adapter.MachineSessions.Result;
import dev.puicore.modbridge.core.adapter.MachineSessions.Stack;
import dev.puicore.modbridge.core.adapter.MachineSessions.Target;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineSessionsTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String MACHINE = "pmb_fixture:bench";
    private static final String INPUT = "minecraft:copper_ingot";
    private static final String OUTPUT = "pmb_fixture:test_ingot";
    private static final Target TARGET = new Target(DIMENSION, 0, 80, 0, MACHINE);
    private static final Menu MENU = new Menu("pmb_fixture:bench", "加工台", Set.of(MACHINE), 5,
            List.of(new Recipe("pmb_fixture:refine", "加工", new ItemAmount(INPUT, 2), new ItemAmount(OUTPUT, 2))));
    private final AtomicLong clock = new AtomicLong();
    private final MachineSessions sessions = new MachineSessions(Duration.ofSeconds(1), clock::get);
    private final UUID player = UUID.randomUUID();

    @Test
    void 信頼済みで対象の版とモードに合うアダプターだけが登録する() {
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<Registrar> savedRegistrar = new AtomicReference<>();
        Adapter adapter = new Adapter() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor("pmb_fixture:adapter", API_VERSION, "pmb_fixture", Set.of("1.0"), Set.of(Mode.A),
                        Set.of(Capability.MACHINE_MENU, Capability.IDENTIFIER_MAPPING));
            }

            @Override
            public void register(Registrar registrar) {
                savedRegistrar.set(registrar);
                registrar.menu(MENU);
                registrar.mapping(Kind.ITEM, INPUT, OUTPUT);
            }

            @Override
            public void shutdown() {
                closed.incrementAndGet();
            }
        };
        var candidates = List.of(new AdapterRegistry.Candidate("other_mod", () -> {
            throw new AssertionError("信頼されていない提供元の入口を呼んだ");
        }), new AdapterRegistry.Candidate("pmb_fixture", () -> adapter));
        Set<String> trusted = Set.of("pmb_fixture");
        try (var mismatch = AdapterRegistry.load(candidates, trusted, Map.of("pmb_fixture", "2.0"), Mode.A);
                var otherMode = AdapterRegistry.load(candidates, trusted, Map.of("pmb_fixture", "1.0"), Mode.B);
                var registry = AdapterRegistry.load(candidates, trusted, Map.of("pmb_fixture", "1.0"), Mode.A)) {
            assertEquals(0, mismatch.activeAdapters().size());
            assertEquals(0, otherMode.activeAdapters().size());
            assertEquals(List.of(adapter.descriptor()), registry.activeAdapters());
            assertEquals(MENU, registry.menuForBlock(MACHINE).orElseThrow());
            assertEquals(OUTPUT, registry.mapping(Kind.ITEM, INPUT).orElseThrow());
            assertThrows(IllegalStateException.class, () -> savedRegistrar.get().menu(MENU));
        }
        assertEquals(1, closed.get());
    }

    @Test
    void 宣言ルールを登録し未知のスキーマは拒否する(@TempDir Path temp) throws IOException {
        Path rules = temp.resolve("rules.json");
        String json = """
                {
                  "schema": "modbridge.adapter-rules",
                  "schemaVersion": 1,
                  "adapters": [{
                    "id": "fixtures:rules",
                    "targetMod": "test_mod",
                    "versions": ["1.0"],
                    "modes": ["A"],
                    "mappings": [{"kind":"ITEM","source":"test_mod:input","destination":"test_mod:output"}]
                  }]
                }
                """;
        Files.writeString(rules, json);
        try (var registry = AdapterRegistry.load(List.of(), Set.of(), Map.of("test_mod", "1.0"), Mode.A, AdapterRules.read(rules))) {
            assertEquals("test_mod:output", registry.mapping(Kind.ITEM, "test_mod:input").orElseThrow());
        }
        Files.writeString(rules, json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));
        assertThrows(IOException.class, () -> AdapterRules.read(rules));
    }

    @Test
    void 応答で位置権限とブロックを再検証する() {
        Player access = new Player(List.of(input(3), Stack.EMPTY));
        var tooFar = sessions.open(player, TARGET, MENU, access).orElseThrow();
        access.position = new Position(DIMENSION, 20, 80, 0);
        assertEquals(Result.OUT_OF_REACH, sessions.select(player, tooFar.token(), 0, access));
        assertFalse(sessions.open(player, TARGET, MENU, access).isPresent());
        access.position = new Position(DIMENSION, 0, 80, 0);
        var denied = sessions.open(player, TARGET, MENU, access).orElseThrow();
        access.allowed = false;
        assertEquals(Result.DENIED, sessions.select(player, denied.token(), 0, access));
        access.allowed = true;
        var blockChanged = sessions.open(player, TARGET, MENU, access).orElseThrow();
        access.block = "minecraft:air";
        assertEquals(Result.BLOCK_CHANGED, sessions.select(player, blockChanged.token(), 0, access));
        assertEquals(List.of(input(3), Stack.EMPTY), access.inventory());
    }

    @Test
    void 別プレイヤー古い画面切断期限切れおよび不正な選択を拒否する() {
        Player access = new Player(List.of(input(3), Stack.EMPTY));
        var replaced = sessions.open(player, TARGET, MENU, access).orElseThrow();
        var current = sessions.open(player, TARGET, MENU, access).orElseThrow();
        sessions.cancel(player, replaced.token());
        assertEquals(Result.STALE, sessions.select(player, replaced.token(), 0, access));
        assertEquals(Result.STALE, sessions.select(UUID.randomUUID(), current.token(), 0, access));
        assertEquals(Result.INVALID_SELECTION, sessions.select(player, current.token(), 1, access));
        assertEquals(Result.STALE, sessions.select(player, current.token(), 0, access));
        var dropped = sessions.open(player, TARGET, MENU, access).orElseThrow();
        sessions.disconnect(player);
        assertEquals(Result.STALE, sessions.select(player, dropped.token(), 0, access));
        var expired = sessions.open(player, TARGET, MENU, access).orElseThrow();
        clock.set(Duration.ofSeconds(1).toNanos());
        assertEquals(Result.EXPIRED, sessions.select(player, expired.token(), 0, access));
        assertEquals(List.of(input(3), Stack.EMPTY), access.inventory());
    }

    @Test
    void 入力不足出力満杯および変更済み在庫でアイテムを失わない() {
        var modified = new Stack(INPUT, 2, 64, false);
        Player wrongInput = new Player(List.of(modified, Stack.EMPTY));
        assertEquals(Result.INPUT_MISSING, select(wrongInput));
        assertEquals(List.of(modified, Stack.EMPTY), wrongInput.inventory());

        Player full = new Player(List.of(input(3), new Stack(OUTPUT, 15, 16, true)));
        assertEquals(Result.OUTPUT_FULL, select(full));
        assertEquals(List.of(input(3), new Stack(OUTPUT, 15, 16, true)), full.inventory());

        Player stale = new Player(List.of(input(3), Stack.EMPTY));
        stale.refuseCommit = true;
        assertEquals(Result.INVENTORY_CHANGED, select(stale));
        assertEquals(List.of(input(3), Stack.EMPTY), stale.inventory());
    }

    @Test
    void 同時に同じ応答が届いても加工は一度しか成立しない() throws Exception {
        Player access = new Player(List.of(input(4), Stack.EMPTY));
        var view = sessions.open(player, TARGET, MENU, access).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                start.await();
                return sessions.select(player, view.token(), 0, access);
            });
            var second = workers.submit(() -> {
                start.await();
                return sessions.select(player, view.token(), 0, access);
            });
            start.countDown();
            assertEquals(Set.of(Result.APPLIED, Result.STALE),
                    Set.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)));
        }
        assertEquals(List.of(input(2), new Stack(OUTPUT, 2, 16, true)), access.inventory());
    }

    private Result select(Player access) {
        return sessions.select(player, sessions.open(player, TARGET, MENU, access).orElseThrow().token(), 0, access);
    }

    private static Stack input(int count) {
        return new Stack(INPUT, count, 64, true);
    }

    private static final class Player implements PlayerAccess {
        private Position position = new Position(DIMENSION, 0, 80, 0);
        private String block = MACHINE;
        private boolean allowed = true;
        private boolean refuseCommit;
        private List<Stack> inventory;

        Player(List<Stack> inventory) {
            this.inventory = inventory;
        }

        @Override public Position position() { return position; }
        @Override public String blockAt(Target target) { return block; }
        @Override public boolean mayOperate(Target target) { return allowed; }
        @Override public List<Stack> inventory() { return inventory; }
        @Override public int stackLimit(String item) { return item.equals(OUTPUT) ? 16 : 0; }

        @Override
        public boolean replaceInventory(List<Stack> expected, List<Stack> updated) {
            if (refuseCommit || !inventory.equals(expected)) {
                return false;
            }
            inventory = updated;
            return true;
        }
    }
}
