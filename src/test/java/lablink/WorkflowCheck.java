package lablink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Exercises the real TCP and UDP paths on a local interface. */
public final class WorkflowCheck {
    private static final class Player implements AutoCloseable {
        final BlockingQueue<String[]> messages = new LinkedBlockingQueue<>();
        final Connection connection;

        Player(String address, int port, String name) throws IOException {
            connection = new Connection(address, port);
            connection.start(name, messages::add, () -> {});
        }

        String[] await(Predicate<String[]> match) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                String[] message = messages.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (message != null && match.test(message)) return message;
            }
            throw new AssertionError("Expected message did not arrive");
        }

        String[] await(String type) throws InterruptedException {
            try {
                return await(fields -> fields[0].equals(type));
            } catch (AssertionError timeout) {
                throw new AssertionError("Expected " + type, timeout);
            }
        }

        void send(String command) throws IOException {
            connection.send(command);
        }

        @Override public void close() {
            connection.close();
        }
    }

    public static void main(String[] args) throws Exception {
        String address = args.length == 0 ? "127.0.0.1" : args[0];
        checkPhysics();
        checkPlayback();
        checkDiscoveryParsing();
        var hostFailure = new CompletableFuture<Throwable>();
        Host host = new Host(address, 0, 7);
        Thread server = new Thread(() -> {
            try {
                host.serve();
            } catch (Throwable error) {
                hostFailure.complete(error);
            }
        }, "Workflow host");
        server.start();
        try {
            int discoveryPort = host.discoveryPort();
            check(discoveryPort > 0, "Discovery responder bound to UDP port");
            run(address, host.port(), discoveryPort);
        } finally {
            host.close();
            server.join(5000);
        }
        check(!server.isAlive(), "Host must shut down");
        if (hostFailure.isDone()) throw new AssertionError("Host failed", hostFailure.join());
        System.out.println("PASS on " + address +
                ": physics and playback; LAN UDP discovery; TCP framing; toss and target authorization; automatic turns; UDP loss; winner, tie, reset, and reconnect");
    }

    private static void checkPhysics() {
        check(!Game.validLength(Double.NaN) && !Game.validLength(Double.POSITIVE_INFINITY), "Finite length required");
        double smallAnglePeriod = Game.period(.90, 10);
        double largeAnglePeriod = Game.period(.90, 50);
        check(largeAnglePeriod > smallAnglePeriod, "A wider release increases the period");
        var trajectory = new Game.Motion(.90, -50);
        check(Math.abs(trajectory.advanceTo(largeAnglePeriod / 2) - 50) < 1e-6, "Opposite endpoint");
        check(Math.abs(trajectory.advanceTo(largeAnglePeriod) + 50) < 1e-6, "Returns to release point");
        check(Game.period(Game.MIN_LENGTH, Game.MIN_ANGLE) < Game.MIN_TARGET &&
                Game.period(Game.MAX_LENGTH, Game.MAX_ANGLE) > Game.MAX_TARGET, "The target range is achievable");
        for (double length : new double[]{Game.MIN_LENGTH, Game.MAX_LENGTH}) {
            for (double angle : new double[]{-Game.MAX_ANGLE, Game.MIN_ANGLE}) {
                double period = Game.period(length, angle);
                var motion = new Game.Motion(length, angle);
                check(Math.abs(motion.advanceTo(period / 4)) < 1e-5, "Quarter cycle crosses centre");
                check(Math.abs(motion.advanceTo(period / 2) + angle) < 1e-5, "Half cycle reaches opposite apex");
                check(Math.abs(motion.advanceTo(period) - angle) < 1e-5, "Both boundary cases complete a cycle");
            }
        }
    }

    private static void checkPlayback() {
        long start = 1_000_000_000L;
        SwingTrace trace = new SwingTrace();
        trace.start(2, 50);
        check(trace.accept(0, 0, 50, start), "First UDP sample");
        check(trace.accept(2, 100, 40, start + 125_000_000), "Continue after dropped UDP sample");
        check(!trace.accept(1, 50, 48, start + 130_000_000), "Ignore out-of-order sample");
        check(!trace.accept(2, 100, 40, start + 135_000_000), "Ignore duplicate sample");
        check(!trace.accept(3, 150, Double.NaN, start + 150_000_000), "Ignore malformed sample");
        check(trace.received() == 2 && trace.missing() == 1, "UDP counters reflect accepted samples");
        check(Math.abs(trace.angleAt(start + 125_000_000) - 45) < 1e-8, "Interpolate on host time");
        trace.finish();
        check(!trace.accept(4, 200, 30, start + 200_000_000), "Ignore packets after TCP completion");
        check(trace.angleAt(start + 2_100_000_000) == 50, "Finish at release angle");
    }

    private static void checkDiscoveryParsing() {
        var explicit = Discovery.parseReply(
                "LABLINK_HOST|1|abc12345|srv01|192.168.1.20|5050|1|2|3|WAITING|Asha",
                "127.0.0.1", "abc12345", 4);
        check(explicit != null && "192.168.1.20".equals(explicit.address()) && explicit.port() == 5050
                && explicit.players() == 1 && explicit.round() == 3 && explicit.joinable()
                && "Asha".equals(explicit.hostName()), "Explicit discovery reply parses");

        var resolved = Discovery.parseReply(
                "LABLINK_HOST|1|abc12345|srv01||5050|0|2|1|OPEN|",
                "192.168.1.44", "abc12345", 2);
        check(resolved != null && "192.168.1.44".equals(resolved.address()) && resolved.joinable(),
                "Wildcard reply resolves sender IP");

        check(Discovery.parseReply("LABLINK_HOST|1|wrong|srv01|127.0.0.1|5050|0|2|1|OPEN|",
                "127.0.0.1", "abc12345", 1) == null, "Mismatched nonce ignored");
        check(Discovery.parseReply("LABLINK_HOST|2|abc12345|srv01|127.0.0.1|5050|0|2|1|OPEN|",
                "127.0.0.1", "abc12345", 1) == null, "Unsupported version ignored");
        check(Discovery.parseReply("LABLINK_HOST|1|abc12345|srv01|127.0.0.1|70000|0|2|1|OPEN|",
                "127.0.0.1", "abc12345", 1) == null, "Out-of-range port ignored");
        check(Discovery.parseReply("LABLINK_HOST|1|abc12345|srv01|127.0.0.1|5050|3|2|1|OPEN|",
                "127.0.0.1", "abc12345", 1) == null, "Invalid occupancy ignored");
        check(Discovery.parseReply("LABLINK_HOST|1|abc12345|srv01|127.0.0.1|5050|0|2|1|BOGUS|",
                "127.0.0.1", "abc12345", 1) == null, "Unknown state ignored");
        check(Discovery.parseReply("LABLINK_HOST|1|abc12345",
                "127.0.0.1", "abc12345", 1) == null, "Truncated discovery reply ignored");
    }

    private static void run(String address, int port, int discoveryPort) throws Exception {
        Discovery.DiscoveredRoom openRoom = Discovery.scan(discoveryPort, 700).stream()
                .filter(room -> room.port() == port)
                .findFirst()
                .orElse(null);
        check(openRoom != null && openRoom.players() == 0 && "OPEN".equals(openRoom.state()) && openRoom.joinable(),
                "Discovery finds open host before players join");

        try (DatagramSocket garbage = new DatagramSocket()) {
            byte[] bad = "GARBAGE|123".getBytes(StandardCharsets.UTF_8);
            garbage.send(new DatagramPacket(bad, bad.length, InetAddress.getByName(address), discoveryPort));
        }

        try (Socket partial = new Socket(address, port);
             Player a = new Player(address, port, "Asha")) {
            partial.setSoTimeout(4000);
            var output = partial.getOutputStream();
            var reader = new BufferedReader(new InputStreamReader(partial.getInputStream(), StandardCharsets.UTF_8));
            output.write("PI".getBytes(StandardCharsets.UTF_8));
            String aId = a.await("PLAYERS")[1];
            Discovery.DiscoveredRoom waitingRoom = Discovery.scan(discoveryPort, 700).stream()
                    .filter(room -> room.port() == port)
                    .findFirst()
                    .orElse(null);
            check(waitingRoom != null && waitingRoom.players() == 1 && "WAITING".equals(waitingRoom.state())
                    && "Asha".equals(waitingRoom.hostName()) && waitingRoom.joinable(),
                    "Discovery reports waiting player state");
            a.send("PING");
            a.await("PONG");
            output.write("NG\nPING\n".getBytes(StandardCharsets.UTF_8));
            check("PONG".equals(reader.readLine()) && "PONG".equals(reader.readLine()), "Partial and coalesced frames");

            try (Player b = new Player(address, port, "Kavin")) {
                String bId = b.await("JOINED")[1];
                Discovery.DiscoveredRoom fullRoom = Discovery.scan(discoveryPort, 700).stream()
                        .filter(room -> room.port() == port)
                        .findFirst()
                        .orElse(null);
                check(fullRoom != null && fullRoom.players() == 2 && "IN_GAME".equals(fullRoom.state())
                        && !fullRoom.joinable(), "Discovery marks full room as in progress");
                String owner = toss(a, b);
                Player first = owner.equals(aId) ? a : b;
                Player second = first == a ? b : a;
                first.send("PLAY|0.9|50");
                check(first.await("ERR")[1].equals("SET_TARGET_FIRST"), "Target required");
                first.send("TARGET|9.0");
                check(first.await("ERR")[1].equals("INVALID_TARGET"), "Reject unreachable target");
                setTargetTogether(first, second);
                check(second.await("ERR")[1].equals("NOT_OWNER"), "Only the toss winner sets the target");
                check(first.await("TARGET")[2].equals("2.0"), "Target broadcast");
                check(first.await("TURN")[2].equals(owner), "Toss winner plays first");
                first.send("TARGET|2.20");
                check(first.await("ERR")[1].equals("TARGET_LOCKED"), "Target is fixed for the round");
                first.send("PLAY|NaN|50");
                check(first.await("ERR")[1].equals("INVALID_LENGTH"), "Reject NaN");
                first.send("PLAY|0.9|0");
                check(first.await("ERR")[1].equals("INVALID_ANGLE"), "Reject unpulled weight");
                first.send("PLAY|0.9|-50");
                String[] swing = first.await("START");
                second.send("PAUSE");
                check(second.await("ERR")[1].equals("NOT_OWNER"), "Reject spectator pause");
                first.send("PLAY|1.0|10");
                check(first.await("ERR")[1].equals("SWING_RUNNING"), "Reject duplicate swing");
                boolean sampleAfterDrop = false;
                boolean seenSeven = false;
                while (!sampleAfterDrop) {
                    String[] sample = b.await("SAMPLE");
                    if (!sample[2].equals(swing[2])) continue;
                    int sequence = Integer.parseInt(sample[3]);
                    seenSeven |= sequence == 7;
                    sampleAfterDrop = sequence >= 8;
                }
                check(!seenSeven, "Second client skips UDP sequence 7 and receives a later sample");
                a.await("SCORE");
                b.await("SCORE");
                String secondId = second == a ? aId : bId;
                second.await(message -> message[0].equals("TURN") && message[2].equals(secondId));
                second.send("PLAY|0.9|10");
                check(a.await("RESULT")[2].equals(owner), "Wide-angle release wins on first client");
                check(b.await("RESULT")[2].equals(owner), "Wide-angle release wins on second client");
                a.send("RESET");
                Player next = toss(a, b).equals(aId) ? a : b;
                Player other = next == a ? b : a;
                next.send("TARGET|2.30");
                takeTurn(next);
                other.await("SCORE");
                takeTurn(other);
                check(a.await("RESULT")[2].equals("TIE"), "Equal lengths tie");
                a.send("RESET");
                Player last = toss(a, b).equals(aId) ? a : b;
                last.send("TARGET|2.0");
                last.await("TURN");
                last.send("PLAY|1.0|10");
                b.await("START");
                b.close();
                check(a.await("ABORT")[2].equals("PLAYER_LEFT"), "Disconnect aborts a live round");
                a.send("RESET");
                a.await("WAITING");
                try (Player replacement = new Player(address, port, "Guest")) {
                    check(!replacement.await("JOINED")[1].equals(bId), "Replacement receives a new identity");
                    toss(a, replacement);
                }
            }
        }
    }

    private static void takeTurn(Player player) throws Exception {
        player.await("TURN");
        player.send("PLAY|1.0|10");
        player.await("SCORE");
    }

    private static String toss(Player a, Player b) throws Exception {
        String[] picker = a.await("TOSS_PICK");
        check(picker[2].equals(b.await("TOSS_PICK")[2]), "Both clients see who chooses");
        b.send("FLIP");
        check(b.await("ERR")[1].equals("TOSS_UNAVAILABLE"), "Choose a side before flipping");
        b.send("PICK|HEADS");
        check(b.await("ERR")[1].equals("NOT_PICKER"), "Only the first player chooses");
        a.send("PICK|TAILS");
        String[] ready = a.await("TOSS_READY");
        check(ready[2].equals(b.await("TOSS_READY")[2]) && ready[3].equals("TAILS"), "Side choice is shared");
        a.send("FLIP");
        check(a.await("ERR")[1].equals("NOT_FLIPPER"), "Only the second player flips");
        b.send("FLIP");
        String[] result = a.await("TOSS");
        String[] other = b.await("TOSS");
        check(result[2].equals(other[2]) && result[3].equals(other[3]), "Both clients see the same toss");
        check(result[3].equals("TAILS") == result[2].equals(picker[2]), "Coin face decides the winner");
        b.send("FLIP");
        check(b.await("ERR")[1].equals("TOSS_UNAVAILABLE"), "One flip per round");
        return result[2];
    }

    private static void setTargetTogether(Player a, Player b) throws Exception {
        var launch = new CountDownLatch(1);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try {
            Future<?> aSend = senders.submit(() -> { launch.await(); a.send("TARGET|2.00"); return null; });
            Future<?> bSend = senders.submit(() -> { launch.await(); b.send("TARGET|2.20"); return null; });
            launch.countDown();
            aSend.get(4, TimeUnit.SECONDS);
            bSend.get(4, TimeUnit.SECONDS);
        } finally {
            senders.shutdownNow();
        }
    }

    private static void check(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
    }
}
