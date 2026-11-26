package lablink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class Host implements AutoCloseable {
    private static final int MAX_LINE = 512;
    private static final int MAX_QUEUE = 32;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final DatagramSocket udp;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private final Queue<Runnable> changes = new ConcurrentLinkedQueue<>();
    private final Room room = new Room();
    private final int skipSample;
    private final Discovery.Responder discovery;
    private volatile boolean running = true;

    public Host(String bind, int port, int skipSample) throws IOException {
        this(bind, port, skipSample, port == 0 ? 0 : Discovery.DEFAULT_PORT);
    }

    public Host(String bind, int port, int skipSample, int discoveryPort) throws IOException {
        this.skipSample = skipSample;
        selector = Selector.open();
        ServerSocketChannel server = null;
        DatagramSocket datagrams = null;
        try {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(bind, port));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
            datagrams = new DatagramSocket(0, ((InetSocketAddress) server.getLocalAddress()).getAddress());
        } catch (IOException | RuntimeException exception) {
            if (server != null) server.close();
            selector.close();
            throw exception;
        }
        listener = server;
        udp = datagrams;
        Discovery.Responder responder = null;
        if (discoveryPort >= 0) {
            try {
                responder = new Discovery.Responder(
                        ((InetSocketAddress) listener.getLocalAddress()).getAddress(),
                        ((InetSocketAddress) listener.getLocalAddress()).getPort(),
                        discoveryPort,
                        room::snapshot);
            } catch (IOException exception) {
                System.err.println("LAN discovery disabled on UDP port " + discoveryPort + ": "
                        + exception.getMessage());
            }
        }
        discovery = responder;
    }

    public int port() throws IOException {
        return ((InetSocketAddress) listener.getLocalAddress()).getPort();
    }

    public int discoveryPort() {
        return discovery == null ? -1 : discovery.port();
    }

    private static final class Peer {
        final SelectionKey key;
        final InetSocketAddress address;
        final ByteBuffer input = ByteBuffer.allocate(4096);
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        final ArrayDeque<String> requests = new ArrayDeque<>();
        final ArrayDeque<ByteBuffer> output = new ArrayDeque<>();
        boolean busy;
        boolean closed;
        String id;
        String name;
        InetSocketAddress udpAddress;
        Double score;

        Peer(SelectionKey key, InetSocketAddress address) {
            this.key = key;
            this.address = address;
        }
    }

    public void serve() throws IOException {
        try {
            while (running) {
                selector.select();
                Runnable change;
                while ((change = changes.poll()) != null) change.run();
                var iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) continue;
                    if (key.isAcceptable()) {
                        accept();
                        continue;
                    }
                    Peer peer = (Peer) key.attachment();
                    try {
                        if (key.isReadable()) read(peer);
                        if (key.isValid() && key.isWritable()) write(peer);
                    } catch (IOException exception) {
                        disconnect(peer);
                    }
                }
            }
        } finally {
            if (discovery != null) discovery.close();
            workers.shutdownNow();
            clock.shutdownNow();
            udp.close();
            for (SelectionKey key : selector.keys()) key.channel().close();
            selector.close();
        }
    }

    private void accept() throws IOException {
        SocketChannel channel = listener.accept();
        if (channel == null) return;
        if (selector.keys().size() >= 17) {
            channel.close();
            return;
        }
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
        key.attach(new Peer(key, (InetSocketAddress) channel.getRemoteAddress()));
    }

    private void read(Peer peer) throws IOException {
        SocketChannel channel = (SocketChannel) peer.key.channel();
        int count = channel.read(peer.input);
        if (count < 0) {
            disconnect(peer);
            return;
        }
        peer.input.flip();
        while (peer.input.hasRemaining()) {
            byte value = peer.input.get();
            if (value == '\n') {
                if (peer.requests.size() >= MAX_QUEUE) {
                    disconnect(peer);
                    return;
                }
                String line = peer.line.toString(StandardCharsets.UTF_8);
                peer.requests.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                peer.line.reset();
            } else {
                if (peer.line.size() == MAX_LINE) {
                    disconnect(peer);
                    return;
                }
                peer.line.write(value);
            }
        }
        peer.input.clear();
        dispatch(peer);
    }

    private void dispatch(Peer peer) {
        if (peer.closed || peer.busy || peer.requests.isEmpty()) return;
        String request = peer.requests.remove();
        peer.busy = true;
        workers.execute(() -> {
            try {
                room.command(peer, request);
            } finally {
                onSelector(() -> {
                    peer.busy = false;
                    dispatch(peer);
                });
            }
        });
    }

    private void write(Peer peer) throws IOException {
        SocketChannel channel = (SocketChannel) peer.key.channel();
        while (!peer.output.isEmpty()) {
            ByteBuffer data = peer.output.peek();
            channel.write(data);
            if (data.hasRemaining()) return;
            peer.output.remove();
        }
        peer.key.interestOps(SelectionKey.OP_READ);
    }

    private void disconnect(Peer peer) {
        if (peer.closed) return;
        peer.closed = true;
        peer.key.cancel();
        try {
            peer.key.channel().close();
        } catch (IOException ignored) {
            // The room still needs to release the disconnected player.
        }
        room.leave(peer);
    }

    private void onSelector(Runnable change) {
        changes.add(change);
        selector.wakeup();
    }

    private void send(Peer peer, String message) {
        onSelector(() -> {
            if (peer.closed) return;
            if (peer.output.size() >= MAX_QUEUE) {
                disconnect(peer);
                return;
            }
            peer.output.add(StandardCharsets.UTF_8.encode(message + "\n"));
            peer.key.interestOpsOr(SelectionKey.OP_WRITE);
        });
    }

    private final class Room {
        private final List<Peer> players = new ArrayList<>();
        private final Set<Peer> departed = Collections.newSetFromMap(new WeakHashMap<>());
        private long playerNumber;
        private long round = 1;
        private long attemptNumber;
        private Peer owner;
        private SwingAttempt active;
        private String pickedFace;
        private double target;
        private boolean started;
        private boolean finished;
        private boolean aborted;

        synchronized void command(Peer peer, String line) {
            if (departed.contains(peer)) return;
            String[] fields = line.split("\\|", -1);
            if (fields[0].equals("PING") && fields.length == 1) {
                send(peer, "PONG");
                return;
            }
            if (fields[0].equals("JOIN")) {
                join(peer, fields);
                return;
            }
            if (!players.contains(peer)) {
                error(peer, "JOIN_FIRST");
                return;
            }
            switch (fields[0]) {
                case "PICK" -> pick(peer, fields);
                case "FLIP" -> flip(peer, fields);
                case "TARGET" -> chooseTarget(peer, fields);
                case "PLAY" -> play(peer, fields);
                case "PAUSE" -> error(peer, fields.length != 1 ? "BAD_COMMAND" :
                        owner == peer ? "UNSUPPORTED" : "NOT_OWNER");
                case "RESET" -> {
                    if (fields.length != 1) error(peer, "BAD_COMMAND");
                    else if (players.get(0) != peer) error(peer, "NOT_HOST_PLAYER");
                    else if (!finished && !aborted) error(peer, "ROUND_IN_PROGRESS");
                    else reset();
                }
                default -> error(peer, "BAD_COMMAND");
            }
        }

        private void join(Peer peer, String[] fields) {
            if (players.contains(peer)) {
                error(peer, "ALREADY_JOINED");
                return;
            }
            if (players.size() == 2 || finished || aborted) {
                error(peer, "ROOM_UNAVAILABLE");
                return;
            }
            if (fields.length != 3 || !fields[1].matches("[\\p{L}\\p{N}_ ]{1,20}") || fields[1].isBlank()) {
                error(peer, "BAD_JOIN");
                return;
            }
            int port;
            try {
                port = Integer.parseInt(fields[2]);
            } catch (NumberFormatException exception) {
                port = 0;
            }
            if (port < 1 || port > 65535) {
                error(peer, "BAD_JOIN");
                return;
            }
            peer.id = "p" + ++playerNumber;
            peer.name = fields[1].strip();
            peer.udpAddress = new InetSocketAddress(peer.address.getAddress(), port);
            players.add(peer);
            send(peer, "JOINED|" + peer.id + "|" + round + "|" + udp.getLocalPort());
            seats();
        }

        private void seats() {
            if (players.isEmpty()) return;
            Peer first = players.get(0);
            String second = players.size() == 2 ? "|" + players.get(1).id + "|" + players.get(1).name : "||";
            broadcast("PLAYERS|" + first.id + "|" + first.name + second);
            if (players.size() < 2) {
                broadcast("WAITING|" + round);
                return;
            }
            broadcast("TOSS_PICK|" + round + "|" + first.id);
        }

        private void pick(Peer peer, String[] fields) {
            if (players.size() != 2 || pickedFace != null || owner != null || finished || aborted) {
                error(peer, "PICK_UNAVAILABLE");
                return;
            }
            if (players.get(0) != peer) {
                error(peer, "NOT_PICKER");
                return;
            }
            if (fields.length != 2 || (!fields[1].equals("HEADS") && !fields[1].equals("TAILS"))) {
                error(peer, "BAD_COMMAND");
                return;
            }
            pickedFace = fields[1];
            broadcast("TOSS_READY|" + round + "|" + players.get(1).id + "|" + pickedFace);
        }

        private void flip(Peer peer, String[] fields) {
            if (fields.length != 1) {
                error(peer, "BAD_COMMAND");
                return;
            }
            if (players.size() != 2 || pickedFace == null || target != 0 || owner != null || finished || aborted) {
                error(peer, "TOSS_UNAVAILABLE");
                return;
            }
            if (players.get(1) != peer) {
                error(peer, "NOT_FLIPPER");
                return;
            }
            String face = ThreadLocalRandom.current().nextBoolean() ? "HEADS" : "TAILS";
            owner = players.get(face.equals(pickedFace) ? 0 : 1);
            broadcast("TOSS|" + round + "|" + owner.id + "|" + face);
        }

        private void chooseTarget(Peer peer, String[] fields) {
            if (owner != peer) {
                error(peer, "NOT_OWNER");
                return;
            }
            if (target != 0) {
                error(peer, "TARGET_LOCKED");
                return;
            }
            double seconds = fields.length == 2 && fields[1].matches("[0-9]+(\\.[0-9]{1,2})?")
                    ? number(fields[1]) : Double.NaN;
            if (!Double.isFinite(seconds) || seconds < Game.MIN_TARGET || seconds > Game.MAX_TARGET) {
                error(peer, "INVALID_TARGET");
                return;
            }
            target = seconds;
            started = true;
            broadcast("TARGET|" + round + "|" + target);
            broadcast("TURN|" + round + "|" + owner.id);
        }

        private double number(String value) {
            try {
                return value.matches("-?[0-9]+(\\.[0-9]+)?") ? Double.parseDouble(value) : Double.NaN;
            } catch (NumberFormatException exception) {
                return Double.NaN;
            }
        }

        private void play(Peer peer, String[] fields) {
            if (owner != peer) {
                error(peer, "NOT_OWNER");
                return;
            }
            if (target == 0) {
                error(peer, "SET_TARGET_FIRST");
                return;
            }
            if (active != null) {
                error(peer, "SWING_RUNNING");
                return;
            }
            double length = fields.length == 3 ? number(fields[1]) : Double.NaN;
            double angle = fields.length == 3 ? number(fields[2]) : Double.NaN;
            if (!Game.validLength(length)) {
                error(peer, "INVALID_LENGTH");
                return;
            }
            if (!Game.validAngle(angle)) {
                error(peer, "INVALID_ANGLE");
                return;
            }
            SwingAttempt next = new SwingAttempt(round, ++attemptNumber, peer, length, angle,
                    players.stream().map(player -> player.udpAddress).toList());
            active = next;
            broadcast("START|" + round + "|" + next.id + "|" + peer.id + "|" + length + "|" + angle);
            next.future = clock.scheduleWithFixedDelay(() -> tick(next), 0, 50, TimeUnit.MILLISECONDS);
        }

        private void tick(SwingAttempt swing) {
            long elapsed = (System.nanoTime() - swing.start) / 1_000_000;
            if (elapsed >= Math.ceil(swing.period * 1000)) {
                complete(swing);
                return;
            }
            int sequence = swing.sequence++;
            byte[] payload = ("SAMPLE|" + swing.round + "|" + swing.id + "|" + sequence + "|" +
                    elapsed + "|" + swing.motion.advanceTo(elapsed / 1000.0)).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < swing.destinations.size(); i++) {
                if (i == 1 && sequence == skipSample) continue;
                try {
                    udp.send(new DatagramPacket(payload, payload.length, swing.destinations.get(i)));
                } catch (IOException ignored) {
                    // Angle samples are best effort. TCP still delivers the score.
                }
            }
        }

        private synchronized void complete(SwingAttempt swing) {
            if (active != swing) return;
            swing.future.cancel(false);
            active = null;
            Peer player = swing.player;
            player.score = swing.period;
            owner = null;
            broadcast("SCORE|" + round + "|" + swing.id + "|" + player.id + "|" + swing.period);
            if (players.stream().allMatch(candidate -> candidate.score != null)) {
                finished = true;
                double difference = Game.error(players.get(0).score, target) - Game.error(players.get(1).score, target);
                String result = Math.abs(difference) <= 0.000001 ? "TIE" : players.get(difference < 0 ? 0 : 1).id;
                broadcast("RESULT|" + round + "|" + result);
            } else {
                owner = players.stream().filter(candidate -> candidate.score == null).findFirst().orElseThrow();
                broadcast("TURN|" + round + "|" + owner.id);
            }
        }

        synchronized void leave(Peer peer) {
            departed.add(peer);
            if (!players.remove(peer)) return;
            if (active != null) {
                active.future.cancel(false);
                active = null;
            }
            owner = null;
            if (players.isEmpty()) {
                reset();
            } else if (started && !finished) {
                aborted = true;
                send(players.get(0), "ADMIN|" + players.get(0).id);
                broadcast("ABORT|" + round + "|PLAYER_LEFT");
            } else if (!finished) {
                pickedFace = null;
                seats();
            } else {
                send(players.get(0), "ADMIN|" + players.get(0).id);
            }
        }

        private void reset() {
            round++;
            owner = null;
            target = 0;
            pickedFace = null;
            started = false;
            finished = false;
            aborted = false;
            for (Peer player : players) player.score = null;
            broadcast("RESET|" + round);
            seats();
        }

        synchronized Discovery.RoomSnapshot snapshot() {
            String state = aborted ? "ABORTED" : finished ? "FINISHED" :
                    players.isEmpty() ? "OPEN" : players.size() == 1 ? "WAITING" : "IN_GAME";
            String hostName = players.isEmpty() ? "" : players.get(0).name;
            return new Discovery.RoomSnapshot(players.size(), 2, round, state, hostName);
        }

        private void broadcast(String message) {
            players.forEach(peer -> send(peer, message));
        }

        private void error(Peer peer, String code) {
            send(peer, "ERR|" + code);
        }
    }

    private static final class SwingAttempt {
        final long round;
        final long id;
        final Peer player;
        final double period;
        final Game.Motion motion;
        final List<InetSocketAddress> destinations;
        final long start = System.nanoTime();
        ScheduledFuture<?> future;
        int sequence;

        SwingAttempt(long round, long id, Peer player, double length, double angle, List<InetSocketAddress> destinations) {
            this.round = round;
            this.id = id;
            this.player = player;
            this.period = Game.period(length, angle);
            motion = new Game.Motion(length, angle);
            this.destinations = destinations;
        }
    }

    @Override
    public void close() {
        running = false;
        if (discovery != null) discovery.close();
        selector.wakeup();
    }

    public static void main(String[] args) throws IOException {
        String bind = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
        int skip = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int discoveryPort = args.length > 3 ? Integer.parseInt(args[3]) : Discovery.DEFAULT_PORT;
        Host host = new Host(bind, port, skip, discoveryPort);
        Runtime.getRuntime().addShutdownHook(new Thread(host::close));
        String discoveryInfo = host.discoveryPort() > 0
                ? " (LAN discovery UDP " + Discovery.MULTICAST_GROUP + ":" + host.discoveryPort() + ")"
                : " (LAN discovery disabled)";
        System.out.println("LabLink listening on " + bind + ":" + host.port() + discoveryInfo);
        host.serve();
    }
}
