package dev.puicore.modbridge.fixture;

import java.util.function.Function;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 検証用 fixture。登録内容は意図的に最小限にし、Bedrock 側での見え方と失敗条件を切り分けやすくする。
 * <ul>
 *   <li>{@code pmb_fixture:test_ingot} — 単純なアイテム（スタック上限 16）</li>
 *   <li>{@code pmb_fixture:test_block} — 単純な立方体ブロックとそのアイテム。ツルハシで壊すとドロップする
 *       （{@code minecraft:mineable/pickaxe} タグ）</li>
 *   <li>{@code pmb_fixture:code_only_marker} — 素材ファイル（items・モデル・テクスチャ・lang）を意図的に持たないアイテム。
 *       静的解析では見つからず、実行時レジストリでだけ見つかる定義の検証用</li>
 * </ul>
 */
public final class FixtureMod implements ModInitializer {
    public static final String MOD_ID = "pmb_fixture";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceKey<Item> TEST_INGOT_KEY = ResourceKey.create(Registries.ITEM, id("test_ingot"));
    public static final ResourceKey<Block> TEST_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, id("test_block"));
    public static final ResourceKey<Item> TEST_BLOCK_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("test_block"));
    public static final ResourceKey<Item> CODE_ONLY_KEY = ResourceKey.create(Registries.ITEM, id("code_only_marker"));

    public static Item testIngot;
    public static Block testBlock;

    @Override
    public void onInitialize() {
        testIngot = registerItem(TEST_INGOT_KEY, Item::new, new Item.Properties().stacksTo(16));
        testBlock = registerBlock(TEST_BLOCK_KEY, Block::new,
                BlockBehaviour.Properties.of().strength(1.5f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops());
        registerItem(TEST_BLOCK_ITEM_KEY, p -> new BlockItem(testBlock, p), new Item.Properties().useBlockDescriptionPrefix());
        registerItem(CODE_ONLY_KEY, Item::new, new Item.Properties());

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(tab -> tab.accept(testIngot));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(tab -> tab.accept(testBlock.asItem()));

        // 登録時期の証跡。サーバーログから Geyser/Hydraulic の初期化順と突き合わせる。
        LOG.info("[pmb-fixture] registered item={} block={} (thread={})",
                TEST_INGOT_KEY.identifier(), TEST_BLOCK_KEY.identifier(), Thread.currentThread().getName());
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static Item registerItem(ResourceKey<Item> key, Function<Item.Properties, Item> factory, Item.Properties props) {
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
    }

    private static Block registerBlock(ResourceKey<Block> key, Function<BlockBehaviour.Properties, Block> factory,
            BlockBehaviour.Properties props) {
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
    }
}
