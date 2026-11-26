package lablink;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Discovers active LabLink hosts on the local network using UDP multicast and broadcast. */
public final class Discovery {
    public static final String MULTICAST_GROUP = "239.255.42.99";
    public static final int DEFAULT_PORT = 5051;
    public static final int DEFAULT_TIMEOUT_MS = 900;
    static final String REQUEST_PREFIX = "LABLINK_DISCOVER";
    static final String RESPONSE_PREFIX = "LABLINK_HOST";
    static final int PROTOCOL_VERSION = 1;
    private static final Set<String> VALID_STATES = Set.of("OPEN", "WAITING", "IN_GAME", "FINISHED", "ABORTED");

    private Discovery() {}

    record RoomSnapshot(int players, int maxPlayers, long round, String state, String hostName) {}

    public record DiscoveredRoom(
            String address,
            int port,
            int players,
            int maxPlayers,
            long round,
            String state,
            String hostName,
            long rttMs) {

        public boolean joinable() {
            return players < maxPlayers && ("OPEN".equals(state) || "WAITING".equals(state));
        }

        public String displayTitle() {
            return hostName.isEmpty() ? "Open Room" : hostName + "'s Room";
        }

        public String statusLabel() {
            return switch (state) {
                case "OPEN" -> "Open";
                case "WAITING" -> "Waiting for player";
                case "IN_GAME" -> "In progress";
                case "FINISHED" -> "Round finished";
                case "ABORTED" -> "Awaiting reset";
                default -> state;
            };
        }
    }

    record ParsedReply(String serverId, DiscoveredRoom room) {}

    static DiscoveredRoom parseReply(String message, String senderAddress, String expectedNonce, long rttMs) {
        ParsedReply parsed = parseReplyInternal(message, senderAddress, expectedNonce, rttMs);
        return parsed == null ? null : parsed.room();
    }

    private static ParsedReply parseReplyInternal(
            String message, String senderAddress, String expectedNonce, long rttMs) {
        if (message == null || expectedNonce == null || expectedNonce.isEmpty()) return null;
        String[] fields = message.split("\\|", -1);
        if (fields.length != 11) return null;
        if (!RESPONSE_PREFIX.equals(fields[0])) return null;
        if (!Integer.toString(PROTOCOL_VERSION).equals(fields[1])) return null;
        if (!expectedNonce.equals(fields[2])) return null;
        String serverId = fields[3];
        if (!serverId.matches("[A-Za-z0-9_-]{1,32}")) return null;
        String resolvedAddress = fields[4].isEmpty() ? senderAddress : fields[4];
        if (resolvedAddress == null || resolvedAddress.isEmpty() || !resolvedAddress.matches("[0-9a-fA-F.:-]+")) {
            return null;
        }
        int tcpPort;
        int players;
        int maxPlayers;
        long round;
        try {
            tcpPort = Integer.parseInt(fields[5]);
            players = Integer.parseInt(fields[6]);
            maxPlayers = Integer.parseInt(fields[7]);
            round = Long.parseLong(fields[8]);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (tcpPort < 1 || tcpPort > 65535) return null;
        if (maxPlayers < 1 || players < 0 || players > maxPlayers) return null;
        if (round < 1) return null;
        String state = fields[9];
        if (!VALID_STATES.contains(state)) return null;
        String hostName = fields[10];
        if (hostName.length() > 20) return null;
        return new ParsedReply(
                serverId,
                new DiscoveredRoom(resolvedAddress, tcpPort, players, maxPlayers, round, state, hostName,
                        Math.max(0, rttMs)));
    }

    public static List<DiscoveredRoom> scan() throws IOException {
        return scan(DEFAULT_PORT, DEFAULT_TIMEOUT_MS);
    }

    public static List<DiscoveredRoom> scan(int discoveryPort, int timeoutMs) throws IOException {
        if (discoveryPort < 1 || discoveryPort > 65535) {
            throw new IllegalArgumentException("Invalid discovery port: " + discoveryPort);
        }
        if (timeoutMs < 50) {
            throw new IllegalArgumentException("Timeout must be at least 50 ms");
        }
        String nonce = randomHexId();
        byte[] probeBytes = (REQUEST_PREFIX + "|" + PROTOCOL_VERSION + "|" + nonce)
                .getBytes(StandardCharsets.UTF_8);

        Map<String, DiscoveredRoom> found = new LinkedHashMap<>();
        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
             Selector selector = Selector.open()) {
            channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
            channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 1);
            channel.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
            channel.bind(new InetSocketAddress(0));
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);

            long startNanos = System.nanoTime();
            long deadline = startNanos + timeoutMs * 1_000_000L;
            sendProbes(channel, probeBytes, discoveryPort);

            ByteBuffer buffer = ByteBuffer.allocate(512);
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) break;
                long waitMs = Math.max(1, (remainingNanos + 999_999L) / 1_000_000L);
                if (selector.select(waitMs) == 0) continue;
                selector.selectedKeys().clear();
                while (true) {
                    buffer.clear();
                    SocketAddress from = channel.receive(buffer);
                    if (from == null) break;
                    if (!(from instanceof InetSocketAddress remote)) continue;
                    buffer.flip();
                    String message = StandardCharsets.UTF_8.decode(buffer).toString().trim();
                    long rttMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
                    ParsedReply parsed = parseReplyInternal(
                            message, remote.getAddress().getHostAddress(), nonce, rttMs);
                    if (parsed == null) continue;
                    DiscoveredRoom existing = found.get(parsed.serverId());
                    if (existing == null || ("127.0.0.1".equals(existing.address())
                            && !"127.0.0.1".equals(parsed.room().address()))) {
                        found.put(parsed.serverId(), parsed.room());
                    }
                }
            }
        }

        List<DiscoveredRoom> rooms = new ArrayList<>(found.values());
        rooms.sort(Comparator
                .comparing(DiscoveredRoom::joinable).reversed()
                .thenComparing(room -> !"WAITING".equals(room.state()))
                .thenComparingLong(DiscoveredRoom::rttMs)
                .thenComparing(DiscoveredRoom::address)
                .thenComparingInt(DiscoveredRoom::port));
        return rooms;
    }

    private static void sendProbes(DatagramChannel channel, byte[] probeBytes, int discoveryPort) {
        Set<InetAddress> sentTargets = new HashSet<>();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        sentTargets.add(loopback);
        sendPacket(channel, probeBytes, new InetSocketAddress(loopback, discoveryPort));

        try {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            for (NetworkInterface ni : activeIpv4Interfaces()) {
                try {
                    if (ni.supportsMulticast()) {
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                        sendPacket(channel, probeBytes, new InetSocketAddress(group, discoveryPort));
                    }
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Continue scanning remaining interfaces.
                }
                for (InterfaceAddress interfaceAddress : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null && sentTargets.add(broadcast)) {
                        sendPacket(channel, probeBytes, new InetSocketAddress(broadcast, discoveryPort));
                    }
                }
            }
        } catch (UnknownHostException ignored) {
            // Multicast group literal is always valid.
        }

        try {
            InetAddress limitedBroadcast = InetAddress.getByName("255.255.255.255");
            if (sentTargets.add(limitedBroadcast)) {
                sendPacket(channel, probeBytes, new InetSocketAddress(limitedBroadcast, discoveryPort));
            }
        } catch (UnknownHostException ignored) {
            // Limited broadcast literal is always valid.
        }
    }

    private static void sendPacket(DatagramChannel channel, byte[] payload, InetSocketAddress target) {
        try {
            channel.send(ByteBuffer.wrap(payload), target);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Individual adapters may reject broadcast or multicast packets.
        }
    }

    private static List<NetworkInterface> activeIpv4Interfaces() {
        List<NetworkInterface> active = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException exception) {
            return active;
        }
        if (interfaces == null) return active;
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            try {
                if (!ni.isUp()) continue;
                boolean hasIpv4 = false;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        hasIpv4 = true;
                        break;
                    }
                }
                if (hasIpv4) active.add(ni);
            } catch (SocketException ignored) {
                // Skip interfaces that fail status inspection.
            }
        }
        return active;
    }

    private static boolean isLocalAddress(InetAddress address) {
        if (address.isLoopbackAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException exception) {
            return false;
        }
    }

    private static String randomHexId() {
        return String.format(Locale.ROOT, "%08x", ThreadLocalRandom.current().nextInt());
    }

    static final class Responder implements AutoCloseable {
        private final DatagramChannel channel;
        private final Selector selector;
        private final InetAddress bindAddress;
        private final int tcpPort;
        private final int boundPort;
        private final String serverId = randomHexId();
        private final Supplier<RoomSnapshot> snapshot;
        private final Thread thread;
        private volatile boolean running = true;

        Responder(InetAddress bindAddress, int tcpPort, int discoveryPort, Supplier<RoomSnapshot> snapshot)
                throws IOException {
            this.bindAddress = bindAddress;
            this.tcpPort = tcpPort;
            this.snapshot = snapshot;
            DatagramChannel openedChannel = null;
            Selector openedSelector = null;
            try {
                openedChannel = DatagramChannel.open(StandardProtocolFamily.INET);
                openedSelector = Selector.open();
                openedChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                openedChannel.bind(new InetSocketAddress(discoveryPort));
                this.boundPort = ((InetSocketAddress) openedChannel.getLocalAddress()).getPort();
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                for (NetworkInterface ni : activeIpv4Interfaces()) {
                    try {
                        if (ni.supportsMulticast()) openedChannel.join(group, ni);
                    } catch (IOException | UnsupportedOperationException ignored) {
                        // Virtual or non-multicast adapters should not stop discovery startup.
                    }
                }
                openedChannel.configureBlocking(false);
                openedChannel.register(openedSelector, SelectionKey.OP_READ);
            } catch (IOException | RuntimeException exception) {
                if (openedSelector != null) openedSelector.close();
                if (openedChannel != null) openedChannel.close();
                throw exception;
            }
            this.channel = openedChannel;
            this.selector = openedSelector;
            this.thread = new Thread(this::serve, "LabLink Discovery");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return boundPort;
        }

        private void serve() {
            ByteBuffer buffer = ByteBuffer.allocate(512);
            try {
                while (running) {
                    if (selector.select() == 0) continue;
                    var keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid() || !key.isReadable()) continue;
                        while (running) {
                            buffer.clear();
                            SocketAddress from = channel.receive(buffer);
                            if (from == null) break;
                            if (!(from instanceof InetSocketAddress remote)) continue;
                            buffer.flip();
                            String message = StandardCharsets.UTF_8.decode(buffer).toString().trim();
                            handleProbe(message, remote);
                        }
                    }
                }
            } catch (IOException | ClosedSelectorException ignored) {
                // Closing the channel or selector terminates the discovery loop.
            } finally {
                closeQuietly();
            }
        }

        private void handleProbe(String message, InetSocketAddress remote) {
            String[] fields = message.split("\\|", -1);
            if (fields.length != 3) return;
            if (!REQUEST_PREFIX.equals(fields[0])) return;
            if (!Integer.toString(PROTOCOL_VERSION).equals(fields[1])) return;
            String nonce = fields[2];
            if (!nonce.matches("[A-Za-z0-9_-]{1,32}")) return;

            if (bindAddress.isLoopbackAddress() && !isLocalAddress(remote.getAddress())) return;
            String advertisedAddress = bindAddress.isLoopbackAddress() ? "127.0.0.1"
                    : bindAddress.isAnyLocalAddress() ? "" : bindAddress.getHostAddress();

            RoomSnapshot state = snapshot.get();
            String safeName = state.hostName() == null ? "" : state.hostName().replace("|", "");
            String reply = RESPONSE_PREFIX + "|" + PROTOCOL_VERSION + "|" + nonce + "|" + serverId + "|"
                    + advertisedAddress + "|" + tcpPort + "|" + state.players() + "|" + state.maxPlayers()
                    + "|" + state.round() + "|" + state.state() + "|" + safeName;
            try {
                channel.send(StandardCharsets.UTF_8.encode(reply), remote);
            } catch (IOException ignored) {
                // Discovery replies are best effort.
            }
        }

        private void closeQuietly() {
            try {
                selector.close();
            } catch (IOException ignored) {
                // Selector shutdown requested.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // Channel shutdown requested.
            }
        }

        @Override
        public void close() {
            running = false;
            selector.wakeup();
            if (Thread.currentThread() != thread) {
                try {
                    thread.join(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            closeQuietly();
        }
    }
}
