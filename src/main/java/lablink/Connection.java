package lablink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class Connection implements AutoCloseable {
    private final Socket socket;
    private final DatagramSocket datagrams;
    private final BufferedReader input;
    private final BufferedWriter output;
    private volatile int serverUdpPort;
    private volatile boolean closed;

    Connection(String host, int port) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 3500);
            socket.setTcpNoDelay(true);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            datagrams = new DatagramSocket(new InetSocketAddress(socket.getLocalAddress(), 0));
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    void start(String name, Consumer<String[]> events, Runnable disconnected) throws IOException {
        send("JOIN|" + name + "|" + datagrams.getLocalPort());
        startThread("LabLink TCP", () -> {
            try {
                String line;
                while ((line = input.readLine()) != null) {
                    String[] fields = line.split("\\|", -1);
                    if (fields[0].equals("JOINED") && fields.length == 4) {
                        serverUdpPort = Integer.parseInt(fields[3]);
                    }
                    events.accept(fields);
                }
            } catch (IOException | RuntimeException ignored) {
                // Closing the socket ends the read loop.
            } finally {
                close();
                disconnected.run();
            }
        });
        startThread("LabLink UDP", () -> {
            byte[] buffer = new byte[256];
            try {
                while (!closed) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    datagrams.receive(packet);
                    if (!packet.getAddress().equals(socket.getInetAddress()) || packet.getPort() != serverUdpPort) continue;
                    String message = new String(buffer, 0, packet.getLength(), StandardCharsets.UTF_8);
                    String[] fields = message.split("\\|", -1);
                    if (fields.length == 6 && fields[0].equals("SAMPLE")) events.accept(fields);
                }
            } catch (IOException ignored) {
                // Closing the datagram socket ends the receive loop.
            }
        });
    }

    synchronized void send(String command) throws IOException {
        output.write(command);
        output.write('\n');
        output.flush();
    }

    private static void startThread(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        closed = true;
        datagrams.close();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Both transports have been asked to close.
        }
    }
}
