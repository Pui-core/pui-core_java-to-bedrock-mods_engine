import { GameMode, ItemStack, PlayerPermissionLevel, system, world } from "@minecraft/server";
import { ActionFormData } from "@minecraft/server-ui";

const sessionTicks = 600;

function mayUseMachine(player) {
    if (!player.isValid || player.getGameMode() !== GameMode.Survival) return false;
    const role = player.playerPermissionLevel;
    return role === PlayerPermissionLevel.Member || role === PlayerPermissionLevel.Operator;
}

function canOperate(player, target) {
    if (!mayUseMachine(player) ||
        (player.getComponent("minecraft:health")?.currentValue ?? 0) <= 0 ||
        player.dimension.id !== target.dimension) {
        return false;
    }
    const { x, y, z } = player.location;
    const at = target.location;
    const distanceSquared = (x - at.x - 0.5) ** 2 + (y - at.y - 0.5) ** 2 + (z - at.z - 0.5) ** 2;
    return distanceSquared <= target.station.maxDistance ** 2 &&
        player.dimension.getBlock(at)?.typeId === target.station.block;
}

function transfer(container, recipe) {
    const source = new ItemStack(recipe.input.item);
    const result = new ItemStack(recipe.output.item);
    const before = Array.from({ length: container.size }, (_, slot) => container.getItem(slot));
    const after = before.slice();
    let required = recipe.input.count;
    for (let slot = 0; slot < after.length && required > 0; slot++) {
        const stack = after[slot];
        if (!stack?.isStackableWith(source)) continue;
        const taken = Math.min(required, stack.amount);
        if (taken === stack.amount) {
            after[slot] = undefined;
        } else {
            after[slot] = stack.clone();
            after[slot].amount -= taken;
        }
        required -= taken;
    }
    if (required !== 0) return false;

    let remaining = recipe.output.count;
    // まず同じアイテムのスタックに入りきる分を入れる。次に空きスロットを使う。
    for (let slot = 0; slot < after.length && remaining > 0; slot++) {
        const stack = after[slot];
        if (!stack?.isStackableWith(result) || stack.amount >= stack.maxAmount) continue;
        const added = Math.min(remaining, stack.maxAmount - stack.amount);
        after[slot] = stack.clone();
        after[slot].amount += added;
        remaining -= added;
    }
    for (let slot = 0; slot < after.length && remaining > 0; slot++) {
        if (after[slot]) continue;
        const added = Math.min(remaining, result.maxAmount);
        after[slot] = result.clone();
        after[slot].amount = added;
        remaining -= added;
    }
    if (remaining !== 0) return false;

    const written = [];
    try {
        for (let slot = 0; slot < after.length; slot++) {
            if (before[slot] === after[slot]) continue;
            written.push(slot);
            container.setItem(slot, after[slot]);
        }
    } catch (failure) {
        const errors = [failure];
        for (const slot of written.reverse()) {
            try {
                container.setItem(slot, before[slot]);
            } catch (restoreFailure) {
                errors.push(restoreFailure);
            }
        }
        if (errors.length > 1) throw new AggregateError(errors, "inventory rollback failed");
        throw failure;
    }
    return true;
}

function registerMachineMenus(rules) {
    const stations = new Map(rules.map(station => [station.block, station]));
    const sessions = new Map();
    world.afterEvents.playerLeave.subscribe(({ playerId }) => sessions.delete(playerId));

    async function open(player, target) {
        const playerId = player.id;
        if (sessions.has(playerId) || !canOperate(player, target)) return;
        const expiresAt = system.currentTick + sessionTicks;
        // このオブジェクトの同一性をセッション ID として使う。クライアントから受け取るのは選択番号だけ。
        sessions.set(playerId, target);
        const timer = system.runTimeout(() => {
            if (sessions.get(playerId) === target) sessions.delete(playerId);
        }, sessionTicks);
        try {
            const form = new ActionFormData().title(target.station.title);
            target.station.recipes.forEach(recipe => form.button(recipe.label));
            const response = await form.show(player);
            if (sessions.get(playerId) !== target) return;
            sessions.delete(playerId);
            if (system.currentTick >= expiresAt || response.canceled || !Number.isInteger(response.selection) ||
                !canOperate(player, target)) return;
            const recipe = target.station.recipes[response.selection];
            const container = player.getComponent("minecraft:inventory")?.container;
            if (recipe && container) {
                const applied = transfer(container, recipe);
                player.onScreenDisplay.setActionBar(applied ? "加工が完了しました" : "材料かインベントリの空きが不足しています");
            }
        } finally {
            system.clearRun(timer);
            if (sessions.get(playerId) === target) sessions.delete(playerId);
        }
    }

    world.beforeEvents.playerInteractWithBlock.subscribe(event => {
        const station = stations.get(event.block.typeId);
        if (!station || event.cancel || !mayUseMachine(event.player) || event.player.isSneaking) return;
        event.cancel = true;
        if (!event.isFirstEvent) return;
        const { player, block } = event;
        const target = { station, dimension: block.dimension.id, location: block.location };
        system.run(() => {
            open(player, target).catch(failure => console.warn("ModBridge machine menu: " + failure));
        });
    });
}
