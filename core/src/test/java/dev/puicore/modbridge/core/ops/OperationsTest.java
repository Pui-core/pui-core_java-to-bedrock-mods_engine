package dev.puicore.modbridge.core.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.puicore.modbridge.core.ops.Operations.Backend;
import dev.puicore.modbridge.core.ops.Operations.Endpoint;
import dev.puicore.modbridge.core.ops.Operations.Kind;
import dev.puicore.modbridge.core.ops.Operations.Plan;
import dev.puicore.modbridge.core.ops.Operations.Status;
import dev.puicore.modbridge.core.ops.Operations.Topology;
import dev.puicore.modbridge.core.ops.Operations.Transport;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OperationsTest {
    private static final String LOOPBACK = "127.0.0.1";

    @Test
    void 移動構成で宣言上の保存先が重複すると拒否する() {
        Backend origin = backend("origin", Transport.NETHERNET, 19132, "origin");
        Backend target = backend("target", Transport.RAKNET, 19142, "target");
        var result = Operations.check(new Plan(1, Topology.BEDROCK_TRANSFER, List.of(origin, target)), false);
        assertTrue(result.reachable());
        assertEquals(List.of(Status.NOT_PROBED, Status.NOT_PROBED), result.findings().stream().map(Operations.Finding::status).toList());

        Backend shared = backend("target", Transport.RAKNET, 19142, "origin");
        assertThrows(IllegalArgumentException.class,
                () -> Operations.check(new Plan(1, Topology.BEDROCK_TRANSFER, List.of(origin, shared)), false));
        assertThrows(IllegalArgumentException.class,
                () -> Operations.check(new Plan(1, Topology.UDP_FORWARD, List.of(origin)), false));
        assertTrue(Operations.check(new Plan(1, Topology.NETHERNET_FORWARD, List.of(origin)), false).reachable());
        assertThrows(IllegalArgumentException.class,
                () -> Operations.check(new Plan(1, Topology.NETHERNET_FORWARD, List.of(target)), false));
    }

    @Test
    void 同じプロトコルの監視先が重複すると拒否する() {
        Backend origin = backend("origin", Transport.RAKNET, 19132, "origin");
        Backend target = new Backend("target", Kind.GEYSER, Transport.RAKNET, new Endpoint(LOOPBACK, 19142),
                origin.probe(), "target");
        assertThrows(IllegalArgumentException.class,
                () -> Operations.check(new Plan(1, Topology.BEDROCK_TRANSFER, List.of(origin, target)), false));
        Backend tcp = new Backend("target", Kind.BDS, Transport.NETHERNET, target.client(), origin.probe(), "target");
        assertTrue(Operations.check(new Plan(1, Topology.BEDROCK_TRANSFER, List.of(origin, tcp)), false).reachable());
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("0", 19132).validate("probe"));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("999.0.0.1", 19132).validate("client"));
    }

    @Test
    void NetherNetはTCPを観測する() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            var result = Operations.check(new Plan(1, Topology.DIRECT,
                    List.of(backend("origin", Transport.NETHERNET, server.getLocalPort(), "origin"))), true);
            assertTrue(result.reachable());
            assertEquals(Status.REACHABLE, result.findings().getFirst().status());
            server.setSoTimeout(1000);
            try (var incoming = server.accept()) {
                assertTrue(incoming.isConnected());
            }
        }
    }

    @Test
    void RakNetの応答を検証する() throws Exception {
        assertTrue(rakNetLoopback(true, true).reachable());
        assertFalse(rakNetLoopback(false, false).reachable());
    }

    private static Operations.Result rakNetLoopback(boolean bedrock, boolean loseFirst) throws Exception {
        try (var server = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            server.setSoTimeout(3000);
            var responder = new FutureTask<Void>(() -> {
                var request = new DatagramPacket(new byte[256], 256);
                server.receive(request);
                if (loseFirst) {
                    request.setLength(request.getData().length);
                    server.receive(request);
                }
                assertEquals(33, request.getLength());
                var buffer = ByteBuffer.wrap(request.getData());
                assertEquals(1, buffer.get());
                long sent = buffer.getLong();
                byte[] description = (bedrock ? "MCPE;Fixture" : "OTHER;Fixture").getBytes(StandardCharsets.UTF_8);
                byte[] response = ByteBuffer.allocate(35 + description.length).put((byte) 0x1c).putLong(sent).putLong(1)
                        .put(HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678"))
                        .putShort((short) description.length).put(description).array();
                server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                return null;
            });
            Thread.ofVirtual().start(responder);
            var result = Operations.check(new Plan(1, Topology.UDP_FORWARD,
                    List.of(backend("origin", Transport.RAKNET, server.getLocalPort(), "origin"))), true);
            responder.get(3, TimeUnit.SECONDS);
            return result;
        }
    }

    private static Backend backend(String id, Transport transport, int port, String directory) {
        var endpoint = new Endpoint(LOOPBACK, port);
        return new Backend(id, Kind.GEYSER, transport, endpoint, endpoint, directory);
    }
}
