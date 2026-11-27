package dev.puicore.modbridge.platform.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 実行時レジストリの取得。サーバーの起動完了後に、MOD が実際に登録したアイテム・ブロック・レシピを JSON に書き出す。
 *
 * <ul>
 *   <li>JVM 引数 {@code -Dmodbridge.registryDump=<file>} を付けたときだけ動く。付けなければ何もしない</li>
 *   <li>{@code -Dmodbridge.registryDump.stop=true} を付けると、書き出した後にサーバーを止める（自動化用）</li>
 *   <li>出力は決定的にする（ソート済み、時刻・パスを含めない）。{@code minecraft} 名前空間は含めない</li>
 *   <li>識別子は {@code namespace:path} の文字列。起動ごとに変わる数値 ID は出さない</li>
 * </ul>
 *
 * <p>形式は {@code modbridge.runtime-registry} 版 1。読込側は {@code dev.puicore.modbridge.core.runtime.RuntimeRegistry}。
 */
public final class RegistryDump implements DedicatedServerModInitializer {
    static final String SCHEMA = "modbridge.runtime-registry";
    static final int SCHEMA_VERSION = 1;
    private static final Logger LOG = LoggerFactory.getLogger("modbridge_registry_dump");

    @Override
    public void onInitializeServer() {
        String target = System.getProperty("modbridge.registryDump");
        if (target == null || target.isBlank()) {
            return;
        }
        boolean stop = Boolean.getBoolean("modbridge.registryDump.stop");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                Path out = Path.of(target).toAbsolutePath();
                writeAtomically(out, toJson(server));
                LOG.info("[modbridge] registry dump written: {}", out.getFileName());
            } catch (IOException | RuntimeException e) {
                // 取得の失敗はサーバーを止める理由にしない。呼出し側はファイルが無いことで検出する
                LOG.error("[modbridge] registry dump failed", e);
            }
            if (stop) {
                LOG.info("[modbridge] stopping server after registry dump");
                server.halt(false);
            }
        });
    }

    static String toJson(MinecraftServer server) {
        Map<String, Set<String>> entries = new TreeMap<>();
        entries.put("block", ids(BuiltInRegistries.BLOCK.keySet()));
        entries.put("item", ids(BuiltInRegistries.ITEM.keySet()));
        Set<String> recipes = new TreeSet<>();
        server.getRecipeManager().getRecipes().forEach(h -> add(recipes, h.id().identifier()));
        entries.put("recipe", recipes);

        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("minecraft", FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""));

        Map<String, String> mods = new TreeMap<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            mods.put(c.getMetadata().getId(), c.getMetadata().getVersion().getFriendlyString());
        }
        JsonArray modArray = new JsonArray();
        mods.forEach((id, version) -> {
            JsonObject m = new JsonObject();
            m.addProperty("id", id);
            m.addProperty("version", version);
            modArray.add(m);
        });
        root.add("mods", modArray);

        JsonArray entryArray = new JsonArray();
        entries.forEach((registry, ids) -> ids.forEach(id -> {
            JsonObject e = new JsonObject();
            e.addProperty("registry", registry);
            e.addProperty("id", id);
            entryArray.add(e);
        }));
        root.add("entries", entryArray);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static Set<String> ids(Set<Identifier> keys) {
        Set<String> out = new TreeSet<>();
        keys.forEach(k -> add(out, k));
        return out;
    }

    private static void add(Set<String> out, Identifier id) {
        if (!id.getNamespace().equals("minecraft")) {
            out.add(id.toString());
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
