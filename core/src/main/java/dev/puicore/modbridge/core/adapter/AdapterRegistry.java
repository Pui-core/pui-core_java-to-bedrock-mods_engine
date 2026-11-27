package dev.puicore.modbridge.core.adapter;

import dev.puicore.modbridge.core.adapter.Adapter.Capability;
import dev.puicore.modbridge.core.adapter.Adapter.Descriptor;
import dev.puicore.modbridge.core.adapter.Adapter.Ids;
import dev.puicore.modbridge.core.adapter.Adapter.Menu;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** 起動時にアダプターを選び、対応付け・加工メニューを登録する。 */
public final class AdapterRegistry implements AutoCloseable {
    /** ローダーの MOD ID と、信頼判定後にだけ参照する入口。 */
    public record Candidate(String provider, Supplier<? extends Adapter> factory) {
        public Candidate {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(factory);
        }
    }

    private record Mapping(Kind kind, String source) {}

    private final List<Adapter> adapters = new ArrayList<>();
    private final List<Descriptor> descriptors = new ArrayList<>();
    private final Map<Mapping, String> mappings = new HashMap<>();
    private final Map<String, Menu> machines = new HashMap<>();
    private final Set<String> menuIds = new HashSet<>();
    private boolean closed;

    private AdapterRegistry() {}

    public static AdapterRegistry load(List<Candidate> candidates, Set<String> trustedProviders, Map<String, String> loadedMods,
            Mode mode) {
        return load(candidates, trustedProviders, loadedMods, mode, List.of());
    }

    public static AdapterRegistry load(List<Candidate> candidates, Set<String> trustedProviders, Map<String, String> loadedMods,
            Mode mode, List<Adapter> declaredRules) {
        AdapterRegistry registry = new AdapterRegistry();
        try {
            for (Candidate candidate : candidates) {
                if (!trustedProviders.contains(candidate.provider())) {
                    continue;
                }
                registry.registerIfSupported(candidate.factory().get(), loadedMods, mode);
            }
            for (Adapter rule : declaredRules) {
                registry.registerIfSupported(rule, loadedMods, mode);
            }
            return registry;
        } catch (RuntimeException | Error failure) {
            try {
                registry.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void registerIfSupported(Adapter adapter, Map<String, String> loadedMods, Mode mode) {
        Descriptor descriptor = Objects.requireNonNull(adapter.descriptor());
        String version = loadedMods.get(descriptor.targetMod());
        if (version != null && descriptor.versions().contains(version) && descriptor.modes().contains(mode)) {
            register(adapter, descriptor);
        }
    }

    private void register(Adapter adapter, Descriptor descriptor) {
        if (descriptor.apiVersion() != Adapter.API_VERSION) {
            throw new IllegalArgumentException("アダプターの API 版が非対応: " + descriptor.id());
        }
        if (descriptors.stream().anyMatch(d -> d.id().equals(descriptor.id()))) {
            throw new IllegalArgumentException("アダプター ID が重複: " + descriptor.id());
        }
        adapters.add(adapter);
        Registration registration = new Registration(descriptor);
        try {
            adapter.register(registration);
        } finally {
            registration.finished = true;
        }
        descriptors.add(descriptor);
    }

    public List<Descriptor> activeAdapters() {
        return List.copyOf(descriptors);
    }

    public Optional<String> mapping(Kind kind, String source) {
        return Optional.ofNullable(mappings.get(new Mapping(kind, source)));
    }

    public Optional<Menu> menuForBlock(String block) {
        return Optional.ofNullable(machines.get(block));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException problem = null;
        for (Adapter adapter : adapters.reversed()) {
            try {
                adapter.shutdown();
            } catch (RuntimeException failure) {
                if (problem == null) {
                    problem = new IllegalStateException("アダプターの停止に失敗");
                }
                problem.addSuppressed(failure);
            }
        }
        if (problem != null) {
            throw problem;
        }
    }

    private final class Registration implements Adapter.Registrar {
        private final Descriptor descriptor;
        private final Thread registering = Thread.currentThread();
        private boolean finished;

        Registration(Descriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void mapping(Kind kind, String source, String destination) {
            require(Capability.IDENTIFIER_MAPPING);
            Ids.require(source);
            Ids.require(destination);
            if (mappings.putIfAbsent(new Mapping(Objects.requireNonNull(kind), source), destination) != null) {
                throw new IllegalArgumentException("同じ識別子の対応付けが重複: " + source);
            }
        }

        @Override
        public void menu(Menu menu) {
            require(Capability.MACHINE_MENU);
            if (!menuIds.add(menu.id())) {
                throw new IllegalArgumentException("メニュー ID が重複: " + menu.id());
            }
            for (String block : menu.blocks()) {
                if (machines.putIfAbsent(block, menu) != null) {
                    throw new IllegalArgumentException("同じブロックのメニューが重複: " + block);
                }
            }
        }

        private void require(Capability capability) {
            if (Thread.currentThread() != registering || finished || !descriptor.capabilities().contains(capability)) {
                throw new IllegalStateException("登録フェーズまたは宣言した機能を確認する: " + capability);
            }
        }
    }
}
