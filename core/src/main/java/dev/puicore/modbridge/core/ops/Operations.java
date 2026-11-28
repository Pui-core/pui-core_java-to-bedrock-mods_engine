package dev.puicore.modbridge.core.ops;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 配置の接続先と保存先の整合性を検査し、明示指定時だけ稼働中の接続先を観測する。 */
public final class Operations {
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private static final int MAX_CONFIG_BYTES = 65_536;
    private static final int TIMEOUT_MS = 1_500;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,39}");
    private static final Pattern HOST = Pattern.compile("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final byte[] RAKNET_MAGIC = HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678");

    public enum Topology { DIRECT, UDP_FORWARD, BEDROCK_TRANSFER }
    public enum Kind { GEYSER, BDS }
    public enum Transport { RAKNET, NETHERNET }
    public enum Status { NOT_PROBED, REACHABLE, UNREACHABLE }

    public record Endpoint(String host, int port) {
        void validate(String field) {
            if (host == null || host.length() > 253 || !HOST.matcher(host).matches() || host.equals("0.0.0.0")
                    || port < 1 || port > 65_535) {
                throw new IllegalArgumentException(field + " は IPv4 または DNS 名と有効なポートにする");
            }
        }

        String key() {
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        }
    }

    public record Backend(String id, Kind kind, Transport transport, Endpoint client, Endpoint probe, String stateDirectory) {}
    public record Plan(int schemaVersion, Topology topology, List<Backend> backends) {}
    public record Finding(String backend, Transport transport, Status status, String observation) {}
    public record Result(Topology topology, List<Finding> findings) {
        public boolean reachable() {
            return findings.stream().noneMatch(finding -> finding.status() == Status.UNREACHABLE);
        }
    }

    private Operations() {}

    public static Plan read(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(MAX_CONFIG_BYTES + 1);
            if (data.length > MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException("運用設定は 64 KiB 以下にする");
            }
            Plan plan = GSON.fromJson(new String(data, StandardCharsets.UTF_8), Plan.class);
            validate(plan);
            return plan;
        } catch (JsonParseException failure) {
            throw new IllegalArgumentException("運用設定の JSON を読めない: " + failure.getMessage(), failure);
        }
    }

    private static void validate(Plan plan) {
        if (plan == null || plan.schemaVersion() != 1 || plan.topology() == null || plan.backends() == null
                || plan.backends().isEmpty() || plan.backends().size() > 16) {
            throw new IllegalArgumentException("schemaVersion 1、topology、1〜16 件の backends が必要");
        }
        if (plan.topology() == Topology.DIRECT && plan.backends().size() != 1) {
            throw new IllegalArgumentException("DIRECT にはバックエンドを1件指定する");
        }
        if (plan.topology() == Topology.BEDROCK_TRANSFER && plan.backends().size() < 2) {
            throw new IllegalArgumentException("BEDROCK_TRANSFER には独立した接続先を2件以上指定する");
        }

        Set<String> ids = new HashSet<>();
        Set<String> clients = new HashSet<>();
        Set<String> state = new HashSet<>();
        for (Backend backend : plan.backends()) {
            if (backend == null || backend.id() == null || !ID.matcher(backend.id()).matches() || !ids.add(backend.id())
                    || backend.kind() == null || backend.transport() == null || backend.client() == null || backend.probe() == null
                    || backend.stateDirectory() == null || !ID.matcher(backend.stateDirectory()).matches()) {
                throw new IllegalArgumentException("バックエンドの項目が空、形式違い、または ID が重複している");
            }
            backend.client().validate(backend.id() + " の client");
            backend.probe().validate(backend.id() + " の probe");
            if (!clients.add(backend.client().key()) || !state.add(backend.stateDirectory())) {
                throw new IllegalArgumentException("クライアント接続先とワールドの保存先はバックエンドごとに分ける");
            }
            if (plan.topology() == Topology.UDP_FORWARD && backend.transport() != Transport.RAKNET) {
                throw new IllegalArgumentException("UDP_FORWARD には RakNet のバックエンドが必要: " + backend.id());
            }
        }
    }

    public static Result check(Plan plan, boolean probe) {
        validate(plan);
        if (!probe) {
            return new Result(plan.topology(), plan.backends().stream()
                    .map(backend -> new Finding(backend.id(), backend.transport(), Status.NOT_PROBED, "設定を検査"))
                    .toList());
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Callable<Finding>> work = plan.backends().stream()
                    .<Callable<Finding>>map(backend -> () -> observe(backend)).toList();
            var observations = executor.invokeAll(work, 3, TimeUnit.SECONDS);
            var findings = new java.util.ArrayList<Finding>();
            for (int i = 0; i < observations.size(); i++) {
                var future = observations.get(i);
                Backend backend = plan.backends().get(i);
                try {
                    findings.add(future.isCancelled() ? unavailable(backend, "応答待ちの時間を超えた") : future.get());
                } catch (ExecutionException failure) {
                    findings.add(unavailable(backend, "接続の観測に失敗"));
                }
            }
            return new Result(plan.topology(), List.copyOf(findings));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(plan.topology(), plan.backends().stream().map(backend -> unavailable(backend, "検査を中断")).toList());
        } finally {
            executor.shutdownNow();
        }
    }

    public static String json(Result result) {
        return GSON.toJson(result);
    }

    private static Finding observe(Backend backend) {
        Endpoint probe = backend.probe();
        try {
            InetSocketAddress endpoint = new InetSocketAddress(probe.host(), probe.port());
            if (backend.transport() == Transport.RAKNET) {
                rakNetPing(endpoint);
                return new Finding(backend.id(), backend.transport(), Status.REACHABLE, "RakNet の Bedrock ping に応答");
            }
            try (Socket socket = new Socket()) {
                socket.connect(endpoint, TIMEOUT_MS);
            }
            return new Finding(backend.id(), backend.transport(), Status.REACHABLE, "NetherNet の設定先で TCP が応答");
        } catch (IOException | IllegalArgumentException failure) {
            return unavailable(backend, failure.getClass().getSimpleName());
        }
    }

    private static Finding unavailable(Backend backend, String observation) {
        return new Finding(backend.id(), backend.transport(), Status.UNREACHABLE, observation);
    }

    private static void rakNetPing(InetSocketAddress endpoint) throws IOException {
        try (var socket = new DatagramSocket()) {
            socket.connect(endpoint);
            socket.setSoTimeout(TIMEOUT_MS);
            long timestamp = System.nanoTime();
            byte[] ping = ByteBuffer.allocate(33).put((byte) 0x01).putLong(timestamp).put(RAKNET_MAGIC).putLong(0).array();
            socket.send(new DatagramPacket(ping, ping.length));
            byte[] received = new byte[2_048];
            DatagramPacket response = new DatagramPacket(received, received.length);
            socket.receive(response);
            var bytes = ByteBuffer.wrap(received, 0, response.getLength());
            if (bytes.remaining() < 35 || bytes.get() != 0x1c || bytes.getLong() != timestamp) {
                throw new IOException("RakNet pong のヘッダーが不正");
            }
            bytes.getLong(); // サーバー GUID
            byte[] magic = new byte[RAKNET_MAGIC.length];
            bytes.get(magic);
            int size = Short.toUnsignedInt(bytes.getShort());
            if (!Arrays.equals(magic, RAKNET_MAGIC) || size != bytes.remaining() || size < 5 || size > 1024) {
                throw new IOException("RakNet pong の本文が不正");
            }
            String motd = new String(received, bytes.position(), size, StandardCharsets.UTF_8);
            if (!motd.startsWith("MCPE;")) {
                throw new IOException("Bedrock の応答ではない");
            }
        }
    }
}
