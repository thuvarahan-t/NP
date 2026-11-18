package lablink;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;

public final class Desktop {
    private static final Color PAPER = new Color(245, 244, 239);
    private static final Color WHITE = new Color(253, 252, 248);
    private static final Color INK = new Color(25, 35, 32);
    private static final Color MUTED = new Color(83, 95, 87);
    private static final Color LINE = new Color(220, 223, 212);
    private static final Color GREEN = new Color(27, 79, 62);
    private static final Color SOFT = new Color(231, 239, 230);
    private static final Color ERROR = new Color(151, 54, 40);
    private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 14);

    private final JFrame window = new JFrame("LabLink");
    private final JTextField host = field("127.0.0.1", 12);
    private final JTextField port = field("5050", 5);
    private final JTextField name = field("", 11);
    private final JTextField length = field("0.95", 5);
    private final JLabel status = label("Disconnected", 13, true, MUTED);
    private final JLabel headline = label("Connect to a room", 19, true, INK);
    private final JTextArea details = description();
    private final JLabel feedback = label(" ", 13, false, ERROR);
    private final JLabel sampleStatus = label("Waiting for a swing", 12, false, MUTED);
    private final JLabel[] players = {label("Waiting for player", 14, true, INK), label("Waiting for player", 14, true, INK)};
    private final JLabel[] times = {label("—", 19, true, GREEN), label("—", 19, true, GREEN)};
    private final JLabel[] errors = {label(" ", 12, false, MUTED), label(" ", 12, false, MUTED)};
    private final JButton connect = button("Connect");
    private final JButton action = button("Claim turn");
    private final JButton reset = button("New round");
    private final Scope scope = new Scope();
    private final java.util.concurrent.ExecutorService network = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "LabLink commands");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Double> scores = new HashMap<>();
    private final String[] seats = {"", ""};
    private final String[] names = {"", ""};
    private Connection connection;
    private String me = "";
    private String owner = "";
    private String round = "";
    private String attempt = "";
    private String winner = "";
    private String admin = "";
    private boolean ready;
    private boolean ended;
    private boolean pending;
    private long sequence = -1;
    private long gaps;

    private Desktop(String[] args) {
        if (args.length > 0) name.setText(args[0]);
        if (args.length > 1) host.setText(args[1]);
        if (args.length > 2) port.setText(args[2]);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.setMinimumSize(new Dimension(850, 670));
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (connection != null) connection.close();
                scope.timer.stop();
                network.shutdownNow();
            }
        });
        window.setContentPane(layout());
        window.setSize(1080, 760);
        window.setLocationByPlatform(true);
        connect.addActionListener(event -> toggleConnection());
        name.addActionListener(event -> { if (connection == null && !pending) toggleConnection(); });
        length.addActionListener(event -> { if (action.isEnabled() && me.equals(owner)) action.doClick(); });
        action.addActionListener(event -> play());
        reset.addActionListener(event -> command("RESET"));
        refresh();
        window.setVisible(true);
    }

    private JComponent layout() {
        JPanel root = new JPanel(new BorderLayout(0, 15));
        root.setBackground(PAPER);
        root.setBorder(new EmptyBorder(23, 28, 20, 28));

        JPanel top = transparent(new BorderLayout(16, 0));
        JPanel title = stack();
        title.add(label("LabLink", 37, true, INK));
        title.add(label("The Two-Second Swing", 15, false, MUTED));
        top.add(title, BorderLayout.WEST);
        JPanel settings = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        settings.add(labeled("PLAYER NAME", name));
        settings.add(labeled("HOST", host));
        settings.add(labeled("PORT", port));
        JPanel connectWrap = stack();
        connectWrap.add(status);
        connectWrap.add(Box.createVerticalStrut(5));
        connectWrap.add(connect);
        settings.add(connectWrap);
        top.add(settings, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        JPanel workspace = transparent(new BorderLayout(15, 0));
        JPanel stage = surface(new BorderLayout());
        JPanel stageTop = transparent(new BorderLayout());
        stageTop.add(label("Swing monitor", 17, true, INK), BorderLayout.WEST);
        stageTop.add(sampleStatus, BorderLayout.EAST);
        stage.add(stageTop, BorderLayout.NORTH);
        stage.add(scope, BorderLayout.CENTER);
        workspace.add(stage, BorderLayout.CENTER);

        JPanel sidebar = transparent(new BorderLayout(0, 12));
        sidebar.setPreferredSize(new Dimension(266, 390));
        JPanel controls = surface(new BorderLayout());
        JPanel content = stack();
        content.add(headline);
        content.add(Box.createVerticalStrut(4));
        content.add(details);
        content.add(Box.createVerticalStrut(14));
        JLabel lengthLabel = label("PENDULUM LENGTH (m)", 11, true, MUTED);
        lengthLabel.setLabelFor(length);
        content.add(lengthLabel);
        content.add(Box.createVerticalStrut(5));
        length.setMaximumSize(new Dimension(Integer.MAX_VALUE, 37));
        length.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(length);
        content.add(Box.createVerticalStrut(10));
        action.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(action);
        content.add(Box.createVerticalStrut(8));
        reset.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(reset);
        controls.add(content, BorderLayout.NORTH);
        sidebar.add(controls, BorderLayout.NORTH);

        JPanel scoresPanel = surface(new BorderLayout());
        JPanel scoreboard = stack();
        scoreboard.add(label("Results", 17, true, INK));
        scoreboard.add(label("Target period  2.00 s", 13, false, MUTED));
        for (int i = 0; i < 2; i++) {
            scoreboard.add(Box.createVerticalStrut(15));
            JPanel row = transparent(new BorderLayout(5, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(players[i], BorderLayout.CENTER);
            row.add(times[i], BorderLayout.EAST);
            scoreboard.add(row);
            scoreboard.add(errors[i]);
        }
        scoresPanel.add(scoreboard, BorderLayout.NORTH);
        sidebar.add(scoresPanel, BorderLayout.CENTER);
        workspace.add(sidebar, BorderLayout.EAST);
        root.add(workspace, BorderLayout.CENTER);
        root.add(feedback, BorderLayout.SOUTH);
        return root;
    }

    private void toggleConnection() {
        if (connection != null) {
            Connection current = connection;
            clearConnection();
            current.close();
            return;
        }
        String player = name.getText().strip();
        String address = host.getText().strip();
        int number;
        try {
            number = Integer.parseInt(port.getText().strip());
            if (number < 1 || number > 65535) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            feedback.setText("Enter a port from 1 to 65535.");
            return;
        }
        if (address.isEmpty() || !player.matches("[\\p{L}\\p{N}_ ]{1,20}")) {
            feedback.setText("Enter a host and a player name of up to 20 letters, numbers, spaces, or underscores.");
            return;
        }
        pending = true;
        feedback.setText(" ");
        refresh();
        network.execute(() -> {
            try {
                Connection next = new Connection(address, number);
                SwingUtilities.invokeLater(() -> {
                    if (!window.isDisplayable()) {
                        next.close();
                        return;
                    }
                    connection = next;
                    refresh();
                    network.execute(() -> {
                        try {
                            next.start(player, event -> SwingUtilities.invokeLater(() -> {
                                if (connection == next) receive(event);
                            }), () -> SwingUtilities.invokeLater(() -> {
                                if (connection == next) {
                                    clearConnection();
                                    feedback.setText("Connection to the host closed.");
                                }
                            }));
                        } catch (IOException exception) {
                            next.close();
                            SwingUtilities.invokeLater(() -> {
                                if (connection == next) {
                                    clearConnection();
                                    feedback.setText("Could not join the room.");
                                }
                            });
                        }
                    });
                });
            } catch (IOException exception) {
                SwingUtilities.invokeLater(() -> {
                    pending = false;
                    feedback.setText("Could not connect. Check the host address, port, and server.");
                    refresh();
                });
            }
        });
    }

    private void clearRound() {
        owner = attempt = winner = "";
        ready = ended = false;
        scores.clear();
        scope.clear();
        sampleStatus.setText("Waiting for a swing");
    }

    private void clearConnection() {
        connection = null;
        pending = false;
        me = round = admin = "";
        Arrays.fill(seats, "");
        Arrays.fill(names, "");
        clearRound();
        refresh();
    }

    private void receive(String[] message) {
        try {
            switch (message[0]) {
                case "JOINED" -> {
                    me = message[1];
                    round = message[2];
                    pending = false;
                }
                case "PLAYERS" -> {
                    seats[0] = message[1]; names[0] = message[2];
                    seats[1] = message[3]; names[1] = message[4];
                    admin = seats[0];
                }
                case "ADMIN" -> admin = message[1];
                case "WAITING", "READY" -> {
                    round = message[1];
                    ready = message[0].equals("READY");
                    owner = "";
                }
                case "TURN" -> {
                    owner = message[2];
                    pending = false;
                    feedback.setText(" ");
                }
                case "START" -> {
                    round = message[1];
                    attempt = message[2];
                    owner = message[3];
                    sequence = -1;
                    gaps = 0;
                    pending = false;
                    scope.clear();
                    scope.period = Game.period(Double.parseDouble(message[4]));
                    sampleStatus.setText("Receiving live samples");
                }
                case "SAMPLE" -> {
                    if (!round.equals(message[1]) || !attempt.equals(message[2])) return;
                    long next = Long.parseLong(message[3]);
                    long elapsed = Long.parseLong(message[4]);
                    double angle = Double.parseDouble(message[5]);
                    if (next <= sequence || elapsed < 0 || elapsed > 4000 ||
                            !Double.isFinite(angle) || Math.abs(angle) > Game.AMPLITUDE) return;
                    if (sequence >= 0) gaps += next - sequence - 1;
                    sequence = next;
                    scope.sample(elapsed / 1000.0, angle);
                    sampleStatus.setText("UDP  ·  " + (sequence + 1) + " samples" +
                            (gaps == 0 ? "" : "  ·  " + gaps + " missed"));
                    return;
                }
                case "SCORE" -> {
                    double period = Double.parseDouble(message[4]);
                    scores.put(message[3], period);
                    attempt = owner = "";
                    scope.sample(period, Game.AMPLITUDE);
                    sampleStatus.setText(sequence < 0 ? "Swing complete · no UDP samples" :
                            gaps == 0 ? "Swing complete" : "Swing complete · " + gaps + " UDP missed");
                }
                case "RESULT" -> {
                    winner = message[2];
                    ended = true;
                    ready = false;
                }
                case "ABORT" -> {
                    ready = false;
                    ended = true;
                    pending = false;
                    attempt = owner = "";
                    sampleStatus.setText("Swing stopped");
                    feedback.setText("The other player left. Start a new round to reopen the room.");
                }
                case "RESET" -> {
                    round = message[1];
                    pending = false;
                    feedback.setText(" ");
                    clearRound();
                }
                case "ERR" -> {
                    String error = errorMessage(message[1]);
                    pending = false;
                    if (me.isEmpty() && connection != null) {
                        Connection current = connection;
                        clearConnection();
                        current.close();
                    }
                    feedback.setText(error);
                }
                default -> { return; }
            }
            refresh();
        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // Invalid packets cannot change the plotted measurements.
        }
    }

    private static String errorMessage(String code) {
        return switch (code) {
            case "BAD_JOIN" -> "The host rejected the player details.";
            case "ROOM_UNAVAILABLE" -> "The room is full or awaiting a reset.";
            case "TURN_TAKEN" -> "The other player claimed the turn.";
            case "INVALID_LENGTH" -> "Enter a length from 0.25 to 2.00 metres.";
            case "NOT_OWNER" -> "Only the turn owner can control this swing.";
            default -> "The host rejected that action (" + code + ").";
        };
    }

    private void play() {
        if (!me.equals(owner)) {
            command("CLAIM");
            return;
        }
        try {
            double value = Double.parseDouble(length.getText().strip());
            if (!Game.validLength(value)) throw new NumberFormatException();
            command("PLAY|" + java.math.BigDecimal.valueOf(value).toPlainString());
        } catch (NumberFormatException exception) {
            feedback.setText("Enter a length from 0.25 to 2.00 metres.");
        }
    }

    private void command(String command) {
        Connection current = connection;
        if (current == null || pending) return;
        pending = true;
        feedback.setText(" ");
        refresh();
        network.execute(() -> {
            try {
                current.send(command);
            } catch (IOException exception) {
                current.close();
            }
        });
    }

    private String playerName(String id) {
        if (id.equals(seats[0])) return names[0];
        if (id.equals(seats[1])) return names[1];
        return "The other player";
    }

    private void refresh() {
        boolean joined = !me.isEmpty();
        boolean turn = joined && me.equals(owner);
        boolean connected = connection != null;
        status.setText(joined ? "Connected" : pending ? "Connecting…" : "Disconnected");
        status.setForeground(joined ? GREEN : MUTED);
        connect.setText(connected ? "Disconnect" : "Connect");
        connect.setEnabled(connected || !pending);
        name.setEnabled(!connected && !pending);
        host.setEnabled(!connected && !pending);
        port.setEnabled(!connected && !pending);
        length.setEnabled(turn && attempt.isEmpty() && !pending);
        action.setText(turn ? "Start swing" : "Claim turn");
        action.setEnabled(joined && ready && !ended && attempt.isEmpty() && !pending &&
                (turn || (owner.isEmpty() && !scores.containsKey(me))));
        reset.setVisible(joined && ended && me.equals(admin));
        reset.setEnabled(!pending);
        if (!joined) {
            headline.setText("Connect to a room");
            details.setText("Enter your name and host address.");
        } else if (ended) {
            headline.setText(winner.isEmpty() ? "Round stopped" : "Round complete");
            details.setText(winner.isEmpty() ? "A player disconnected." :
                    winner.equals("TIE") ? "Both attempts tied." :
                            winner.equals(me) ? "You won this round." : playerName(winner) + " won.");
        } else if (!attempt.isEmpty()) {
            headline.setText("Swing in progress");
            details.setText(turn ? "Your attempt is live." : "Watching " + playerName(owner) + ".");
        } else if (turn) {
            headline.setText("Your turn");
            details.setText("Choose a length from 0.25–2.00 m.");
        } else if (!owner.isEmpty()) {
            headline.setText("Other player’s turn");
            details.setText(playerName(owner) + " is choosing a length.");
        } else if (!ready) {
            headline.setText("Waiting for a player");
            details.setText("Share the host address and port.");
        } else if (scores.containsKey(me)) {
            headline.setText("Attempt recorded");
            details.setText("Waiting for the other attempt.");
        } else {
            headline.setText("Round ready");
            details.setText("Claim the turn to choose a length.");
        }
        for (int i = 0; i < 2; i++) {
            players[i].setText(seats[i].isEmpty() ? "Waiting for player" :
                    names[i] + (seats[i].equals(me) ? " (you)" : ""));
            players[i].setToolTipText(names[i]);
            Double period = scores.get(seats[i]);
            times[i].setText(period == null ? "—" : String.format(Locale.ROOT, "%.2f s", period));
            errors[i].setText(period == null ? " " : String.format(Locale.ROOT, "Off target by %.3f s", Game.error(period)));
        }
    }

    private static JPanel transparent(LayoutManager manager) {
        JPanel panel = new JPanel(manager);
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel stack() {
        JPanel panel = transparent(null);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel surface(LayoutManager manager) {
        JPanel panel = new JPanel(manager);
        panel.setBackground(WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(15, 16, 15, 16)));
        return panel;
    }

    private static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(BODY.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        label.setForeground(color);
        return label;
    }

    private static JTextArea description() {
        JTextArea text = new JTextArea(2, 18);
        text.setFont(BODY.deriveFont(13f));
        text.setForeground(MUTED);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setEditable(false);
        text.setFocusable(false);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        return text;
    }

    private static JPanel labeled(String text, JTextField field) {
        JPanel panel = stack();
        JLabel label = label(text, 11, true, MUTED);
        label.setLabelFor(field);
        panel.add(label);
        panel.add(Box.createVerticalStrut(4));
        panel.add(field);
        return panel;
    }

    private static JTextField field(String text, int columns) {
        JTextField field = new JTextField(text, columns);
        field.setFont(BODY);
        field.setForeground(INK);
        field.setBackground(WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(7, 8, 7, 8)));
        return field;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(!isEnabled() ? SOFT : getModel().isPressed() ? GREEN.darker() :
                        getModel().isRollover() ? new Color(36, 97, 75) : GREEN);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9);
                if (isFocusOwner()) {
                    g.setColor(isEnabled() ? WHITE : GREEN);
                    g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 7, 7);
                }
                g.setFont(getFont());
                g.setColor(isEnabled() ? WHITE : MUTED);
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(getText(), (getWidth() - metrics.stringWidth(getText())) / 2,
                        (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                g.dispose();
            }
        };
        button.setFont(BODY.deriveFont(Font.BOLD));
        button.setForeground(Color.WHITE);
        button.setBackground(GREEN);
        button.setMargin(new Insets(7, 13, 7, 13));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        return button;
    }

    private static final class Scope extends JComponent {
        private record Sample(double seconds, double angle) {}
        private final List<Sample> samples = new ArrayList<>();
        private final javax.swing.Timer timer = new javax.swing.Timer(16, event -> repaint());
        private double from = Game.AMPLITUDE;
        private double to = Game.AMPLITUDE;
        private double period = Game.TARGET;
        private long changed;

        Scope() {
            timer.start();
        }

        void clear() {
            samples.clear();
            from = to = Game.AMPLITUDE;
            period = Game.TARGET;
            repaint();
        }

        void sample(double seconds, double angle) {
            from = angleNow();
            to = angle;
            changed = System.nanoTime();
            samples.add(new Sample(seconds, angle));
        }

        private double angleNow() {
            double fraction = Math.min(1, Math.max(0, (System.nanoTime() - changed) / 50_000_000.0));
            return from + (to - from) * fraction;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            double center = width / 2.0;
            double pivot = 53;
            double arm = Math.max(60, Math.min(width * .45, height * .49));
            g.setColor(SOFT);
            g.fill(new Ellipse2D.Double(center - arm / 2, pivot + arm * .53, arm, arm * .68));
            g.setColor(LINE);
            g.draw(new Line2D.Double(center, pivot, center, pivot + arm + 32));
            double angle = Math.toRadians(angleNow());
            double bobX = center + arm * Math.sin(angle);
            double bobY = pivot + arm * Math.cos(angle);
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(center, pivot, bobX, bobY));
            g.fill(new Ellipse2D.Double(center - 5, pivot - 5, 10, 10));
            g.fill(new Ellipse2D.Double(bobX - 14, bobY - 14, 28, 28));

            double left = 34;
            double right = width - 14;
            double top = height * .69;
            double bottom = height - 30;
            double middle = (top + bottom) / 2;
            double scale = Math.max(1, (bottom - top - 14) / 24);
            g.setFont(BODY.deriveFont(11f));
            g.setColor(MUTED);
            g.drawString("Angle (°)", 0, (int) top - 11);
            g.drawString("0", 14, (int) middle + 4);
            g.drawString("0 s", (int) left, height - 9);
            String end = String.format(Locale.ROOT, "%.2f s", period);
            g.drawString(end, (int) right - g.getFontMetrics().stringWidth(end), height - 9);
            g.setColor(LINE);
            g.setStroke(new BasicStroke(1));
            for (int i = 0; i <= 4; i++) {
                double x = left + (right - left) * i / 4;
                g.draw(new Line2D.Double(x, top, x, bottom));
            }
            g.draw(new Line2D.Double(left, middle, right, middle));
            Path2D trace = new Path2D.Double();
            for (Sample sample : samples) {
                double x = left + (right - left) * Math.min(1, sample.seconds / period);
                double y = middle - sample.angle * scale;
                if (trace.getCurrentPoint() == null) trace.moveTo(x, y);
                else trace.lineTo(x, y);
            }
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(trace);
            g.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Desktop(args));
    }
}
