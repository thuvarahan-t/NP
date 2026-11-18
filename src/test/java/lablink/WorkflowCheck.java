package lablink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Run with assertions enabled. Exercises the real TCP and UDP paths on loopback. */
public final class WorkflowCheck {
    private static final class Player implements AutoCloseable {
        final BlockingQueue<String[]> messages = new LinkedBlockingQueue<>();
        final Connection connection;

        Player(int port, String name) throws IOException {
            connection = new Connection("127.0.0.1", port);
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
            return await(fields -> fields[0].equals(type));
        }

        void send(String command) throws IOException {
            connection.send(command);
        }

        @Override public void close() {
            connection.close();
        }
    }

    public static void main(String[] args) throws Exception {
        check(!Game.validLength(Double.NaN) && !Game.validLength(Double.POSITIVE_INFINITY), "Finite length required");
        check(Math.abs(Game.period(.95) - 1.9552719379) < 1e-9, "Pendulum period");
        var hostFailure = new CompletableFuture<Throwable>();
        Host host = new Host("127.0.0.1", 0, 7);
        Thread server = new Thread(() -> {
            try {
                host.serve();
            } catch (Throwable error) {
                hostFailure.complete(error);
            }
        }, "Workflow host");
        server.start();
        try {
            run(host.port());
        } finally {
            host.close();
            server.join(5000);
        }
        check(!server.isAlive(), "Host must shut down");
        if (hostFailure.isDone()) throw new AssertionError("Host failed", hostFailure.join());
        System.out.println("PASS: Physics, split TCP frames, two clients, turn race, permissions, UDP loss, scores, tie, reset, disconnect, and rejoin");
    }

    private static void run(int port) throws Exception {
        try (Socket partial = new Socket("127.0.0.1", port);
             Player a = new Player(port, "Asha")) {
            partial.setSoTimeout(4000);
            var output = partial.getOutputStream();
            var reader = new BufferedReader(new InputStreamReader(partial.getInputStream(), StandardCharsets.UTF_8));
            output.write("PI".getBytes(StandardCharsets.UTF_8));
            String aId = a.await("PLAYERS")[1];
            a.send("PING");
            a.await("PONG");
            output.write("NG\nPING\n".getBytes(StandardCharsets.UTF_8));
            check("PONG".equals(reader.readLine()) && "PONG".equals(reader.readLine()), "Partial and coalesced frames");

            try (Player b = new Player(port, "Kavin")) {
                String bId = b.await("JOINED")[1];
                a.await("READY");
                b.await("READY");
                race(a, b);
                String owner = a.await("TURN")[2];
                check(owner.equals(b.await("TURN")[2]), "Both clients agree on the owner");
                Player first = owner.equals(aId) ? a : b;
                Player second = first == a ? b : a;
                second.await(message -> message[0].equals("ERR") && message[1].equals("TURN_TAKEN"));
                first.send("PLAY|NaN");
                check(first.await("ERR")[1].equals("INVALID_LENGTH"), "Reject NaN");
                first.send("PLAY|0.95");
                first.await("START");
                second.send("PAUSE");
                check(second.await("ERR")[1].equals("NOT_OWNER"), "Reject spectator pause");
                first.send("PLAY|1.0");
                check(first.await("ERR")[1].equals("SWING_RUNNING"), "Reject duplicate swing");
                boolean sampleAfterDrop = false;
                boolean seenSeven = false;
                while (!sampleAfterDrop) {
                    String[] sample = b.await("SAMPLE");
                    int sequence = Integer.parseInt(sample[3]);
                    seenSeven |= sequence == 7;
                    sampleAfterDrop = sequence >= 8;
                }
                check(!seenSeven, "Second client skips UDP sequence 7 and receives a later sample");
                a.await("SCORE");
                b.await("SCORE");
                a.await("READY");
                b.await("READY");
                second.send("CLAIM");
                second.await("TURN");
                second.send("PLAY|1.08");
                check(a.await("RESULT")[2].equals(owner), "0.95 m wins on first client");
                check(b.await("RESULT")[2].equals(owner), "0.95 m wins on second client");
                a.send("RESET");
                a.await("READY");
                b.await("READY");
                takeTurn(a, "1.0");
                takeTurn(b, "1.0");
                check(a.await("RESULT")[2].equals("TIE"), "Equal lengths tie");
                a.send("RESET");
                a.await("READY");
                b.await("READY");
                a.send("CLAIM");
                a.await("TURN");
                a.send("PLAY|1.0");
                b.await("START");
                b.close();
                check(a.await("ABORT")[2].equals("PLAYER_LEFT"), "Disconnect aborts a live round");
                a.send("RESET");
                a.await("WAITING");
                try (Player replacement = new Player(port, "Guest")) {
                    check(!replacement.await("JOINED")[1].equals(bId), "Replacement receives a new identity");
                    a.await("READY");
                }
            }
        }
    }

    private static void takeTurn(Player player, String length) throws Exception {
        player.send("CLAIM");
        player.await("TURN");
        player.send("PLAY|" + length);
        player.await("SCORE");
    }

    private static void race(Player a, Player b) throws Exception {
        var launch = new CountDownLatch(1);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try {
            Future<?> aSend = senders.submit(() -> { launch.await(); a.send("CLAIM"); return null; });
            Future<?> bSend = senders.submit(() -> { launch.await(); b.send("CLAIM"); return null; });
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
