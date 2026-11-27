import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

const source = readFileSync(new URL(
    "../core/src/main/resources/dev/puicore/modbridge/core/compile/bedrock-machine-menus.js", import.meta.url), "utf8")
    .replace(/^import .+;\r?\n/gm, "");
const location = { x: 0, y: 64, z: 0 };
const recipe = {
    label: "Process copper",
    input: { item: "minecraft:copper_ingot", count: 2 },
    output: { item: "fixture:test_ingot", count: 1 }
};
const station = { block: "fixture:machine", title: "Machine", maxDistance: 5, recipes: [recipe] };

class ItemStack {
    constructor(typeId, amount = 1, custom = false) {
        this.typeId = typeId;
        this.amount = amount;
        this.custom = custom;
        this.maxAmount = 64;
    }

    isStackableWith(other) {
        return this.typeId === other.typeId && this.custom === other.custom;
    }

    clone() {
        return new ItemStack(this.typeId, this.amount, this.custom);
    }
}

function player(id, slots = [new ItemStack(recipe.input.item, 2), undefined]) {
    const inventory = {
        size: slots.length,
        getItem: slot => slots[slot]?.clone(),
        setItem(slot, item) {
            slots[slot] = item?.clone();
            if (slot === this.failOnceAt) {
                delete this.failOnceAt;
                throw new Error("slot write failed");
            }
        }
    };
    return {
        id, location: { ...location }, mode: "survival", isValid: true, inventory, slots,
        dimension: { id: "overworld", block: station.block, getBlock() { return { typeId: this.block }; } },
        getGameMode() { return this.mode; },
        getComponent(type) {
            return type === "minecraft:health" ? { currentValue: 20 } : { container: inventory };
        },
        onScreenDisplay: { setActionBar() {} }
    };
}

function harness() {
    const forms = [];
    const timers = new Set();
    const warnings = [];
    let beforeInteract;
    let onLeave;
    class ActionFormData {
        title() { return this; }
        button() { return this; }
        show(player) {
            return new Promise(resolve => forms.push({ player, resolve }));
        }
    }
    runInNewContext(source + "\nregisterMachineMenus(stations);", {
        ItemStack, ActionFormData, stations: [station],
        GameMode: { Survival: "survival" },
        world: {
            beforeEvents: { playerInteractWithBlock: { subscribe: listener => { beforeInteract = listener; } } },
            afterEvents: { playerLeave: { subscribe: listener => { onLeave = listener; } } }
        },
        system: {
            run: callback => callback(),
            runTimeout: callback => { timers.add(callback); return callback; },
            clearRun: callback => timers.delete(callback)
        },
        console: { warn: message => warnings.push(message) }
    });
    return {
        forms, warnings,
        click(player, isFirstEvent = true) {
            const event = {
                player, isFirstEvent, cancel: false,
                block: { typeId: station.block, dimension: player.dimension, location }
            };
            beforeInteract(event);
            return event.cancel;
        },
        leave: player => onLeave({ playerId: player.id }),
        expire: () => timers.forEach(callback => callback())
    };
}

function inventory(player) {
    return player.slots.flatMap(stack => stack ? [[stack.typeId, stack.amount, stack.custom]] : []);
}

const settleForms = () => new Promise(resolve => setImmediate(resolve));

test("overlapping responses only update their own inventories and consume each selection once", async () => {
    const { click, forms } = harness();
    const first = player("first");
    const second = player("second");
    click(first);
    click(first, false);
    click(first);
    click(second);
    assert.deepEqual(forms.map(form => form.player.id), ["first", "second"]);
    forms[1].resolve({ selection: 0 });
    forms[0].resolve({ selection: 0 });
    forms[0].resolve({ selection: 0 });
    await settleForms();
    for (const who of [first, second]) assert.deepEqual(inventory(who), [[recipe.output.item, 1, false]]);
});

test("expiry, leaving, distance, permissions, changed block and invalid choices prevent processing", async () => {
    const reject = [
        (qa) => { qa.expire(); },
        (qa, who) => { qa.leave(who); },
        (qa, who) => { who.location.x = 20; },
        (qa, who) => { who.mode = "creative"; },
        (qa, who) => { who.dimension.id = "nether"; },
        (qa, who) => { who.dimension.block = "minecraft:air"; }
    ];
    for (const change of reject) {
        const qa = harness();
        const who = player("first");
        qa.click(who);
        change(qa, who);
        qa.forms[0].resolve({ selection: 0 });
        await settleForms();
        assert.deepEqual(inventory(who), [[recipe.input.item, 2, false]]);
    }
    for (const response of [{ selection: -1 }, { selection: 0, canceled: true }, { selection: "0" }]) {
        const qa = harness();
        const who = player("first");
        qa.click(who);
        qa.forms[0].resolve(response);
        await settleForms();
        assert.deepEqual(inventory(who), [[recipe.input.item, 2, false]]);
    }
});

test("full inventories, custom materials and a failed slot write preserve the inventory", async () => {
    const qa = harness();
    const full = player("full", [new ItemStack(recipe.input.item, 3), new ItemStack("minecraft:stone", 64)]);
    const custom = player("custom", [new ItemStack(recipe.input.item, 2, true), undefined]);
    const failure = player("failure", [new ItemStack(recipe.input.item, 3), undefined]);
    failure.inventory.failOnceAt = 1;
    for (const [i, who] of [full, custom, failure].entries()) {
        const original = inventory(who);
        qa.click(who);
        qa.forms[i].resolve({ selection: 0 });
        await settleForms();
        assert.deepEqual(inventory(who), original);
    }
    assert.deepEqual(qa.warnings, ["ModBridge machine menu: Error: slot write failed"]);
});
