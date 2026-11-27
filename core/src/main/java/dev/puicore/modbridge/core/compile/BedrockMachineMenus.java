package dev.puicore.modbridge.core.compile;

import dev.puicore.modbridge.core.adapter.Adapter.ItemAmount;
import dev.puicore.modbridge.core.adapter.Adapter.Menu;
import dev.puicore.modbridge.core.adapter.Adapter.Recipe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mode B の加工メニューを、固定の実行コードと検証済みの宣言データにする。 */
final class BedrockMachineMenus {
    static final String ENTRY = "scripts/modbridge-machine-menus.js";
    static final List<Integer> MIN_ENGINE_VERSION = List.of(1, 21, 90);
    private static final String SCRIPT_API_VERSION = "2.0.0";

    private BedrockMachineMenus() {}

    static List<Map<String, Object>> dependencies() {
        return List.of(dependency("@minecraft/server"), dependency("@minecraft/server-ui"));
    }

    private static Map<String, Object> dependency(String name) {
        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("module_name", name);
        dependency.put("version", SCRIPT_API_VERSION);
        return dependency;
    }

    static Map<String, Object> module(UUID header) {
        UUID uuid = UUID.nameUUIDFromBytes(("modbridge:bp:script:" + header).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("type", "script");
        module.put("language", "javascript");
        module.put("uuid", uuid.toString());
        module.put("version", List.of(1, 0, 0));
        module.put("entry", ENTRY);
        return module;
    }

    static void addScript(List<Menu> menus, Map<String, PackCompiler.Entry> entries, Map<String, byte[]> files,
            List<BehaviorPack.Entry> behavior) {
        List<Menu> resolved = menus.stream().map(menu -> resolve(menu, entries)).toList();
        Map<String, Menu> stations = new LinkedHashMap<>();
        for (Menu menu : resolved) {
            for (String block : menu.blocks().stream().sorted().toList()) {
                if (stations.putIfAbsent(block, menu) != null) {
                    throw new IllegalArgumentException("同じ Bedrock ブロックの加工メニューが重複: " + block);
                }
            }
        }
        // 各ブロックを配列として渡し、プレーンな JSON だけを実行コードに埋め込む。
        var rules = stations.entrySet().stream().map(e -> new Station(e.getKey(), e.getValue().title(),
                e.getValue().maxDistance(), e.getValue().recipes())).toList();
        try (var script = BedrockMachineMenus.class.getResourceAsStream("bedrock-machine-menus.js")) {
            if (script == null) {
                throw new IllegalStateException("Mode B の加工スクリプトが同梱されていない");
            }
            String source = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            String data = new String(PackCompiler.json(rules), StandardCharsets.UTF_8);
            files.put(ENTRY, (source + "\nregisterMachineMenus(" + data.stripTrailing() + ");\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        behavior.add(new BehaviorPack.Entry("modbridge:machine_menus", "SCRIPT", "modbridge:machine_menus", "GENERATED",
                ENTRY, resolved.stream().map(Menu::id).toList()));
    }

    private static Menu resolve(Menu menu, Map<String, PackCompiler.Entry> entries) {
        var blocks = new java.util.TreeSet<String>();
        menu.blocks().forEach(block -> blocks.add(resolve("block", block, entries)));
        List<Recipe> recipes = menu.recipes().stream()
                .map(recipe -> new Recipe(recipe.id(), recipe.label(), resolveItem(recipe.input(), entries),
                        resolveItem(recipe.output(), entries))).toList();
        return new Menu(menu.id(), menu.title(), blocks, menu.maxDistance(), recipes);
    }

    private static ItemAmount resolveItem(ItemAmount amount, Map<String, PackCompiler.Entry> entries) {
        // Mode B では変換済みのブロック自体にも同じ ID のブロックアイテムが付く。
        String kind = entries.containsKey("block:" + amount.item()) ? "block" : "item";
        return new ItemAmount(resolve(kind, amount.item(), entries), amount.count());
    }

    private static String resolve(String kind, String id, Map<String, PackCompiler.Entry> entries) {
        if (id.startsWith("minecraft:")) {
            return id;
        }
        PackCompiler.Entry entry = entries.get(kind + ":" + id);
        if (entry == null || entry.status().equals("SKIPPED")) {
            throw new IllegalArgumentException("加工ルールの参照先を Mode B に変換できない: " + id);
        }
        return entry.bedrock();
    }

    private record Station(String block, String title, double maxDistance, List<Recipe> recipes) {}
}
