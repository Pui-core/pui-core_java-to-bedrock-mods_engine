package dev.puicore.modbridge.core.adapter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import dev.puicore.modbridge.core.adapter.Adapter.Capability;
import dev.puicore.modbridge.core.adapter.Adapter.Descriptor;
import dev.puicore.modbridge.core.adapter.Adapter.Ids;
import dev.puicore.modbridge.core.adapter.Adapter.Menu;
import dev.puicore.modbridge.core.adapter.Adapter.Registrar;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 運用者が明示した JSON を、クラス名やコード実行を伴わない登録ルールとして読む。 */
public final class AdapterRules {
    private static final String SCHEMA = "modbridge.adapter-rules";
    private static final int MAX_BYTES = 1 << 20;
    public static final AdapterRules EMPTY = new AdapterRules(List.of(), "");

    private final List<Adapter> adapters;
    private final String sha256;

    private AdapterRules(List<Adapter> adapters, String sha256) {
        this.adapters = adapters;
        this.sha256 = sha256;
    }

    public static List<Adapter> read(Path path) throws IOException {
        return readDocument(path).adapters();
    }

    public List<Adapter> adapters() {
        return adapters;
    }

    public String sha256() {
        return sha256;
    }

    /** 読み取った内容とキャッシュ用のハッシュを同じスナップショットにまとめる。 */
    public static AdapterRules readDocument(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] data = stream.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("アダプタールールは 1 MiB まで");
            }
            List<Adapter> adapters = parse(new String(data, StandardCharsets.UTF_8));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            return new AdapterRules(adapters, digest);
        } catch (JsonParseException | IllegalArgumentException | NullPointerException failure) {
            throw new IOException("アダプタールールを読めない: " + failure.getMessage(), failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<Adapter> parse(String json) {
        var document = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(json, Document.class);
        if (document == null || !SCHEMA.equals(document.schema()) || document.schemaVersion() != 1) {
            throw new IllegalArgumentException("スキーマと版 modbridge.adapter-rules/1 を指定する");
        }
        return document.adapters().stream().map(Rule::asAdapter).toList();
    }

    private record Document(String schema, int schemaVersion, List<Rule> adapters) {
        Document {
            adapters = List.copyOf(adapters);
        }
    }

    private record Mapping(Kind kind, String source, String destination) {
        Mapping {
            Objects.requireNonNull(kind);
            Ids.require(source);
            Ids.require(destination);
        }
    }

    private record Rule(String id, String targetMod, Set<String> versions, Set<Mode> modes, List<Mapping> mappings, List<Menu> menus) {
        Rule {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
            menus = menus == null ? List.of() : List.copyOf(menus);
        }

        Adapter asAdapter() {
            var capabilities = EnumSet.noneOf(Capability.class);
            if (!mappings.isEmpty()) {
                capabilities.add(Capability.IDENTIFIER_MAPPING);
            }
            if (!menus.isEmpty()) {
                capabilities.add(Capability.MACHINE_MENU);
            }
            var descriptor = new Descriptor(id, Adapter.API_VERSION, targetMod, versions, modes, capabilities);
            return new Adapter() {
                @Override
                public Descriptor descriptor() {
                    return descriptor;
                }

                @Override
                public void register(Registrar registrar) {
                    mappings.forEach(mapping -> registrar.mapping(mapping.kind(), mapping.source(), mapping.destination()));
                    menus.forEach(registrar::menu);
                }
            };
        }
    }
}
