package dev.puicore.modbridge.core.adapter;

import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 信頼済み MOD が提供する、プラットフォームから独立したアダプターの契約。 */
public interface Adapter {
    int API_VERSION = 1;

    Descriptor descriptor();

    void register(Registrar registrar);

    default void shutdown() {}

    enum Capability { IDENTIFIER_MAPPING, MACHINE_MENU }

    record Descriptor(String id, int apiVersion, String targetMod, Set<String> versions, Set<Mode> modes,
            Set<Capability> capabilities) {
        public Descriptor {
            Ids.require(id);
            if (apiVersion <= 0 || Objects.requireNonNull(targetMod).isBlank()) {
                throw new IllegalArgumentException("API 版または対象 MOD が不正");
            }
            versions = Set.copyOf(versions);
            modes = Set.copyOf(modes);
            capabilities = Set.copyOf(capabilities);
            if (versions.isEmpty() || versions.stream().anyMatch(String::isBlank) || modes.isEmpty() || capabilities.isEmpty()) {
                throw new IllegalArgumentException("対象の版、モード、機能を指定する");
            }
        }
    }

    interface Registrar {
        void mapping(Kind kind, String source, String destination);

        void menu(Menu menu);
    }

    record ItemAmount(String item, int count) {
        public ItemAmount {
            Ids.require(item);
            if (count < 1 || count > 4096) {
                throw new IllegalArgumentException("加工する個数は 1〜4096");
            }
        }
    }

    record Recipe(String id, String label, ItemAmount input, ItemAmount output) {
        public Recipe {
            Ids.require(id);
            Ids.requireLabel(label);
            Objects.requireNonNull(input);
            Objects.requireNonNull(output);
        }
    }

    record Menu(String id, String title, Set<String> blocks, double maxDistance, List<Recipe> recipes) {
        public Menu {
            Ids.require(id);
            Ids.requireLabel(title);
            blocks = Set.copyOf(blocks);
            blocks.forEach(Ids::require);
            recipes = List.copyOf(recipes);
            if (blocks.isEmpty() || !Double.isFinite(maxDistance) || maxDistance <= 0 || maxDistance > 64
                    || recipes.isEmpty() || recipes.size() > 32) {
                throw new IllegalArgumentException("ブロック、操作距離（64 以下）、レシピ（1〜32 件）を指定する");
            }
            Set<String> recipeIds = new HashSet<>();
            for (Recipe recipe : recipes) {
                if (!recipeIds.add(recipe.id())) {
                    throw new IllegalArgumentException("同じレシピ ID が重複: " + recipe.id());
                }
            }
        }
    }
    final class Ids {
        private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

        private Ids() {}

        static void require(String id) {
            if (!ID.matcher(Objects.requireNonNull(id)).matches()) {
                throw new IllegalArgumentException("名前空間付きの識別子を指定する: " + id);
            }
        }

        static void requireLabel(String label) {
            if (Objects.requireNonNull(label).isBlank()) {
                throw new IllegalArgumentException("画面に表示する名前を指定する");
            }
        }
    }
}
