package dev.puicore.modbridge.core.adapter;

import dev.puicore.modbridge.core.adapter.Adapter.Ids;
import dev.puicore.modbridge.core.adapter.Adapter.Menu;
import dev.puicore.modbridge.core.adapter.Adapter.Recipe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 加工画面の応答を一度だけ受け付け、サーバーの状態から在庫の変更を計算する。 */
public final class MachineSessions {
    public enum Result {
        APPLIED, STALE, EXPIRED, INVALID_SELECTION, OUT_OF_REACH, DENIED, BLOCK_CHANGED,
        INPUT_MISSING, OUTPUT_FULL, RECIPE_UNAVAILABLE, INVENTORY_CHANGED
    }

    public record Target(String dimension, int x, int y, int z, String block) {
        public Target {
            Ids.require(dimension);
            Ids.require(block);
        }
    }

    public record Position(String dimension, double x, double y, double z) {}

    public record Stack(String item, int count, int limit, boolean plain) {
        public static final Stack EMPTY = new Stack("", 0, 0, false);

        public Stack {
            if (count == 0) {
                if (!"".equals(item) || limit != 0 || plain) {
                    throw new IllegalArgumentException("空きスロットは Stack.EMPTY で表す");
                }
            } else {
                Ids.require(item);
                if (count < 0 || limit < 1 || count > limit) {
                    throw new IllegalArgumentException("スロットの個数か上限が不正");
                }
            }
        }

        public boolean matches(String id) {
            return plain && item.equals(id);
        }

        private Stack withCount(int newCount) {
            return newCount == 0 ? EMPTY : new Stack(item, newCount, limit, plain);
        }
    }

    /** 接続中のプレイヤーをサーバー上で解決し、検証・反映する。 */
    public interface PlayerAccess {
        Position position();

        boolean mayOperate(Target target);

        String blockAt(Target target);

        List<Stack> inventory();

        /** 利用できないアイテムは 0。 */
        int stackLimit(String item);

        /** 現在のスロットが expected と一致する場合のみ、変更した全スロットを反映する。 */
        boolean replaceInventory(List<Stack> expected, List<Stack> updated);
    }

    public record View(UUID token, Menu menu) {}

    private record Session(UUID token, Target target, Menu menu, long opened) {}

    private final Map<UUID, Session> active = new HashMap<>();
    private final long lifetime;
    private final LongSupplier clock;

    public MachineSessions(Duration lifetime) {
        this(lifetime, System::nanoTime);
    }

    MachineSessions(Duration lifetime, LongSupplier clock) {
        this.lifetime = lifetime.toNanos();
        this.clock = Objects.requireNonNull(clock);
        if (this.lifetime <= 0) {
            throw new IllegalArgumentException("セッションの有効時間は正の値にする");
        }
    }

    public Optional<View> open(UUID player, Target target, Menu menu, PlayerAccess access) {
        Objects.requireNonNull(player);
        if (!menu.blocks().contains(target.block()) || checkTarget(target, menu, access) != null) {
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        synchronized (active) {
            long now = clock.getAsLong();
            active.values().removeIf(session -> now - session.opened() >= lifetime);
            active.put(player, new Session(token, target, menu, now));
        }
        return Optional.of(new View(token, menu));
    }

    public Result select(UUID player, UUID token, int selection, PlayerAccess access) {
        Session session = take(player, token);
        if (session == null) {
            return Result.STALE;
        }
        if (clock.getAsLong() - session.opened() >= lifetime) {
            return Result.EXPIRED;
        }
        Menu menu = session.menu();
        if (selection < 0 || selection >= menu.recipes().size()) {
            return Result.INVALID_SELECTION;
        }
        Result denied = checkTarget(session.target(), menu, access);
        return denied == null ? process(menu.recipes().get(selection), access) : denied;
    }

    public void cancel(UUID player, UUID token) {
        take(player, token);
    }

    public void disconnect(UUID player) {
        synchronized (active) {
            active.remove(player);
        }
    }

    private Session take(UUID player, UUID token) {
        synchronized (active) {
            Session session = active.get(player);
            if (session == null || !session.token().equals(token)) {
                return null;
            }
            active.remove(player);
            return session;
        }
    }

    private static Result checkTarget(Target target, Menu menu, PlayerAccess access) {
        Position player = access.position();
        double dx = player.x() - (target.x() + 0.5);
        double dy = player.y() - (target.y() + 0.5);
        double dz = player.z() - (target.z() + 0.5);
        double distance = dx * dx + dy * dy + dz * dz;
        if (!target.dimension().equals(player.dimension()) || !(distance <= menu.maxDistance() * menu.maxDistance())) {
            return Result.OUT_OF_REACH;
        }
        if (!access.mayOperate(target)) {
            return Result.DENIED;
        }
        return target.block().equals(access.blockAt(target)) ? null : Result.BLOCK_CHANGED;
    }

    private static Result process(Recipe recipe, PlayerAccess access) {
        int limit = access.stackLimit(recipe.output().item());
        if (limit <= 0) {
            return Result.RECIPE_UNAVAILABLE;
        }
        List<Stack> before = List.copyOf(access.inventory());
        List<Stack> after = new ArrayList<>(before);
        int input = recipe.input().count();
        for (int slot = 0; slot < after.size() && input > 0; slot++) {
            Stack stack = after.get(slot);
            if (stack.matches(recipe.input().item())) {
                int used = Math.min(input, stack.count());
                after.set(slot, stack.withCount(stack.count() - used));
                input -= used;
            }
        }
        if (input > 0) {
            return Result.INPUT_MISSING;
        }
        int output = recipe.output().count();
        for (int slot = 0; slot < after.size() && output > 0; slot++) {
            Stack stack = after.get(slot);
            if (stack.matches(recipe.output().item())) {
                int added = Math.min(output, stack.limit() - stack.count());
                after.set(slot, stack.withCount(stack.count() + added));
                output -= added;
            }
        }
        for (int slot = 0; slot < after.size() && output > 0; slot++) {
            if (after.get(slot).count() == 0) {
                int added = Math.min(output, limit);
                after.set(slot, new Stack(recipe.output().item(), added, limit, true));
                output -= added;
            }
        }
        if (output > 0) {
            return Result.OUTPUT_FULL;
        }
        return access.replaceInventory(before, List.copyOf(after)) ? Result.APPLIED : Result.INVENTORY_CHANGED;
    }
}
