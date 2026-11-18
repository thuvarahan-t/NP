package lablink;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;

public final class Desktop {
    private static final Color PAPER = new Color(12, 19, 38);
    private static final Color WHITE = new Color(29, 41, 65);
    private static final Color INK = new Color(238, 246, 255);
    private static final Color MUTED = new Color(162, 181, 207);
    private static final Color LINE = new Color(202, 226, 255, 44);
    private static final Color GREEN = new Color(117, 235, 212);
    private static final Color SOFT = new Color(80, 173, 185, 32);
    private static final Color ERROR = new Color(255, 160, 160);
    private static final Color GOLD = new Color(237, 181, 84);
    private static final Color GOLD_EDGE = new Color(159, 96, 30);
    private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 14);

    private final JFrame window = new JFrame("LabLink");
    private final JTextField host = field("127.0.0.1", 12);
    private final JTextField port = field("5050", 5);
    private final JTextField name = field("", 11);
    private final JTextField targetField = field("2.00", 5);
    private final JSlider length = new JSlider(25, 200, 95);
    private final JLabel lengthReadout = label("0.95 m", 15, true, GREEN);
    private final JLabel tossReadout = label(" ", 13, true, GREEN);
    private final JLabel targetReadout = label("Target period  —", 13, false, MUTED);
    private final JLabel status = label("Disconnected", 13, true, MUTED);
    private final JLabel headline = label("Connect to a room", 19, true, INK);
    private final JTextArea details = description();
    private final JLabel feedback = label(" ", 13, false, ERROR);
    private final Toast notice = new Toast();
    private final JLabel sampleStatus = label("Waiting for a swing", 12, false, MUTED);
    private final JLabel[] players = {label("Waiting for player", 14, true, INK), label("Waiting for player", 14, true, INK)};
    private final JLabel[] times = {label("—", 19, true, GREEN), label("—", 19, true, GREEN)};
    private final JLabel[] errors = {label(" ", 12, false, MUTED), label(" ", 12, false, MUTED)};
    private final JButton connect = button("Connect");
    private final JButton heads = button("Heads");
    private final JButton tails = button("Tails");
    private final JButton flip = button("Flip the coin");
    private final JButton action = button("Start game");
    private final JButton reset = button("New round");
    private final Scope scope = new Scope();
    private final javax.swing.Timer tossReveal = new javax.swing.Timer(1350, event -> revealToss());
    private final javax.swing.Timer noticeExpiry = new javax.swing.Timer(4200, event -> clearNotice());
    private JPanel targetControls;
    private JPanel faceControls;
    private JPanel lengthControls;
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
    private String tossWinner = "";
    private String flipper = "";
    private String picker = "";
    private String pickedFace = "";
    private String coinFace = "";
    private boolean flipping;
    private long flipStarted;
    private long celebrationStarted;
    private long resultStarted;
    private double target;
    private double releaseAngle;
    private boolean ready;
    private boolean ended;
    private boolean pending;

    private Desktop(String[] args) {
        if (args.length > 0) {
            name.setText(args[0]);
            window.setTitle("LabLink — " + args[0]);
        }
        if (args.length > 1) host.setText(args[1]);
        if (args.length > 2) port.setText(args[2]);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        tossReveal.setRepeats(false);
        noticeExpiry.setRepeats(false);
        clearNotice();
        window.setMinimumSize(new Dimension(850, 670));
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (connection != null) connection.close();
                scope.timer.stop();
                tossReveal.stop();
                noticeExpiry.stop();
                network.shutdownNow();
            }
        });
        window.setContentPane(layout());
        window.setSize(1080, 760);
        window.setLocationByPlatform(true);
        connect.addActionListener(event -> toggleConnection());
        name.addActionListener(event -> { if (connection == null && !pending) toggleConnection(); });
        targetField.addActionListener(event -> { if (action.isEnabled()) action.doClick(); });
        action.addActionListener(event -> setTarget());
        flip.addActionListener(event -> command("FLIP"));
        heads.addActionListener(event -> command("PICK|HEADS"));
        tails.addActionListener(event -> command("PICK|TAILS"));
        length.addChangeListener(event -> {
            double metres = length.getValue() / 100.0;
            lengthReadout.setText(String.format(Locale.ROOT, "%.2f m", metres));
            if (canPull()) scope.previewLength = metres;
            scope.repaint();
        });
        reset.addActionListener(event -> command("RESET"));
        refresh();
        window.setVisible(true);
    }

    private JComponent layout() {
        JPanel root = new JPanel(new BorderLayout(0, 15)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setPaint(new GradientPaint(0, 0, new Color(26, 37, 78), getWidth(), getHeight(), PAPER));
                g.fillRect(0, 0, getWidth(), getHeight());
                glow(g, getWidth() * .18f, getHeight() * .38f, Math.max(1, getWidth() * .60f),
                        new Color(69, 129, 230, 105));
                glow(g, getWidth() * .92f, getHeight() * .84f, Math.max(1, getWidth() * .55f),
                        new Color(56, 159, 172, 82));
                g.dispose();
            }
        };
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
        content.add(Box.createVerticalStrut(7));
        content.add(tossReadout);
        faceControls = transparent(new FlowLayout(FlowLayout.LEFT, 6, 4));
        faceControls.setAlignmentX(Component.LEFT_ALIGNMENT);
        faceControls.add(heads);
        faceControls.add(tails);
        content.add(faceControls);
        flip.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(6));
        content.add(flip);
        targetControls = surface(new BorderLayout());
        JPanel targetContent = stack();
        targetContent.add(label("Select target time", 17, true, INK));
        targetContent.add(Box.createVerticalStrut(7));
        targetContent.add(labeled("SECONDS · 1.20–2.90", targetField));
        targetContent.add(Box.createVerticalStrut(8));
        action.setAlignmentX(Component.LEFT_ALIGNMENT);
        targetContent.add(action);
        targetControls.add(targetContent);
        scope.setLayout(new GridBagLayout());
        GridBagConstraints popup = new GridBagConstraints();
        popup.weightx = popup.weighty = 1;
        popup.anchor = GridBagConstraints.SOUTHEAST;
        popup.insets = new Insets(0, 0, 9, 7);
        scope.add(targetControls, popup);
        lengthControls = stack();
        lengthControls.add(Box.createVerticalStrut(10));
        lengthControls.add(label("PENDULUM LENGTH", 11, true, MUTED));
        lengthControls.add(lengthReadout);
        length.setOpaque(false);
        length.setUI(new javax.swing.plaf.basic.BasicSliderUI(length) {
            @Override public void paintTrack(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int y = trackRect.y + trackRect.height / 2 - 2;
                g.setColor(LINE);
                g.fillRoundRect(trackRect.x, y, trackRect.width, 4, 4, 4);
                g.setColor(slider.isEnabled() ? GREEN : MUTED);
                g.fillRoundRect(trackRect.x, y, thumbRect.x + thumbRect.width / 2 - trackRect.x, 4, 4, 4);
                g.dispose();
            }
            @Override public void paintThumb(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = thumbRect.x + thumbRect.width / 2;
                int y = trackRect.y + trackRect.height / 2;
                g.setColor(slider.hasFocus() ? INK : slider.isEnabled() ? GREEN : MUTED);
                g.fillOval(x - 7, y - 7, 14, 14);
                g.dispose();
            }
            @Override public void paintFocus(Graphics graphics) {
                // The thumb itself carries the focus highlight.
            }
        });
        length.setFocusable(true);
        length.getAccessibleContext().setAccessibleName("Pendulum length in centimetres");
        length.setAlignmentX(Component.LEFT_ALIGNMENT);
        length.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        lengthControls.add(length);
        content.add(lengthControls);
        content.add(Box.createVerticalStrut(8));
        reset.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(reset);
        controls.add(content, BorderLayout.NORTH);
        sidebar.add(controls, BorderLayout.NORTH);

        JPanel scoresPanel = surface(new BorderLayout());
        JPanel scoreboard = stack();
        scoreboard.add(label("Results", 17, true, INK));
        scoreboard.add(targetReadout);
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
        JPanel messages = transparent(new BorderLayout(8, 0));
        messages.add(feedback, BorderLayout.CENTER);
        messages.add(notice, BorderLayout.EAST);
        root.add(messages, BorderLayout.SOUTH);
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
        owner = attempt = winner = tossWinner = flipper = coinFace = picker = pickedFace = "";
        tossReveal.stop();
        flipping = false;
        flipStarted = celebrationStarted = resultStarted = 0;
        target = 0;
        releaseAngle = 0;
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
        noticeExpiry.stop();
        clearNotice();
        refresh();
    }

    private void notifyPlayer(String message) {
        notice.setText(message);
        notice.started = System.nanoTime();
        noticeExpiry.restart();
    }

    private void clearNotice() {
        notice.setText(" ");
        notice.started = 0;
    }

    private void revealToss() {
        flipping = false;
        pending = false;
        sampleStatus.setText(coinFace.equals("HEADS") ? "Heads landed" : "Tails landed");
        if (me.equals(tossWinner)) celebrationStarted = System.nanoTime();
        refresh();
    }

    private void receive(String[] message) {
        try {
            switch (message[0]) {
                case "JOINED" -> {
                    me = message[1];
                    round = message[2];
                    pending = false;
                    notifyPlayer("Connected to the room");
                }
                case "PLAYERS" -> {
                    seats[0] = message[1]; names[0] = message[2];
                    seats[1] = message[3]; names[1] = message[4];
                    admin = seats[0];
                }
                case "ADMIN" -> admin = message[1];
                case "WAITING" -> {
                    round = message[1];
                    clearRound();
                    pending = false;
                    notifyPlayer("Connected · Waiting for another player");
                }
                case "TOSS_READY" -> {
                    round = message[1];
                    flipper = message[2];
                    pickedFace = message[3];
                    pending = false;
                    sampleStatus.setText("Coin toss");
                }
                case "TOSS_PICK" -> {
                    round = message[1];
                    picker = message[2];
                    pending = false;
                    sampleStatus.setText("Coin toss");
                    notifyPlayer("Both players connected");
                }
                case "TOSS" -> {
                    round = message[1];
                    tossWinner = message[2];
                    coinFace = message[3];
                    owner = "";
                    ready = false;
                    flipping = true;
                    flipStarted = System.nanoTime();
                    sampleStatus.setText("Coin in the air");
                    tossReveal.restart();
                }
                case "TARGET" -> {
                    target = Double.parseDouble(message[2]);
                    pending = false;
                    sampleStatus.setText("Waiting for a swing");
                    notifyPlayer(String.format(Locale.ROOT, "Target locked · %.2f s", target));
                }
                case "TURN" -> {
                    owner = message[2];
                    ready = true;
                    pending = false;
                    feedback.setText(" ");
                    if (me.equals(owner)) {
                        scope.clear();
                        scope.previewLength = length.getValue() / 100.0;
                        sampleStatus.setText("Ready to pull");
                        notifyPlayer("Your turn · Pull and release the weight");
                    }
                }
                case "START" -> {
                    round = message[1];
                    attempt = message[2];
                    owner = message[3];
                    pending = false;
                    scope.clear();
                    scope.previewLength = Double.parseDouble(message[4]);
                    releaseAngle = Double.parseDouble(message[5]);
                    scope.period = Game.period(scope.previewLength, releaseAngle);
                    scope.trace.start(scope.period, releaseAngle);
                    sampleStatus.setText("Receiving live samples");
                }
                case "SAMPLE" -> {
                    if (!round.equals(message[1]) || !attempt.equals(message[2])) return;
                    long next = Long.parseLong(message[3]);
                    long elapsed = Long.parseLong(message[4]);
                    double angle = Double.parseDouble(message[5]);
                    if (!scope.trace.accept(next, elapsed, angle, System.nanoTime())) return;
                    long gaps = scope.trace.missing();
                    sampleStatus.setText("UDP  ·  " + scope.trace.received() + " samples" +
                            (gaps == 0 ? "" : "  ·  " + gaps + " missed"));
                    return;
                }
                case "SCORE" -> {
                    double period = Double.parseDouble(message[4]);
                    scores.put(message[3], period);
                    attempt = owner = "";
                    scope.trace.finish();
                    sampleStatus.setText(scope.trace.received() == 0 ? "Swing complete · no UDP samples" :
                            scope.trace.missing() == 0 ? "Swing complete" :
                                    "Swing complete · " + scope.trace.missing() + " UDP missed");
                }
                case "RESULT" -> {
                    winner = message[2];
                    ended = true;
                    ready = false;
                    resultStarted = System.nanoTime();
                    if (me.equals(winner)) celebrationStarted = System.nanoTime();
                    notifyPlayer(winner.equals("TIE") ? "Round complete · Draw" :
                            "Round complete · " + playerName(winner) + " wins");
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
            case "INVALID_LENGTH" -> "Enter a length from 0.25 to 2.00 metres.";
            case "INVALID_ANGLE" -> "Pull the weight at least 5° from centre before releasing.";
            case "INVALID_TARGET" -> "Choose a time from 1.20 to 2.90 seconds.";
            case "NOT_FLIPPER" -> "The other player flips the coin after your choice.";
            case "NOT_PICKER" -> "The first player chooses Heads or Tails.";
            case "TOSS_UNAVAILABLE" -> "This round already has a coin result or needs another player.";
            case "NOT_OWNER" -> "Only the turn owner can control this swing.";
            default -> "The host rejected that action (" + code + ").";
        };
    }

    private void setTarget() {
        try {
            String entered = targetField.getText().strip();
            if (!entered.matches("[0-9]+(\\.[0-9]{1,2})?")) throw new NumberFormatException();
            double value = Double.parseDouble(entered);
            if (value < Game.MIN_TARGET || value > Game.MAX_TARGET) throw new NumberFormatException();
            command("TARGET|" + entered);
        } catch (NumberFormatException exception) {
            feedback.setText("Choose a time from 1.20 to 2.90 seconds, using up to two decimal places.");
        }
    }

    private boolean canPull() {
        return ready && !me.isEmpty() && me.equals(owner) && attempt.isEmpty() && !pending && !ended;
    }

    private void release(double angle) {
        if (!canPull()) return;
        if (!Game.validAngle(angle)) {
            scope.pullAngle = 0;
            feedback.setText("Pull the weight at least 5° from centre before releasing.");
            scope.repaint();
            return;
        }
        command(String.format(Locale.ROOT, "PLAY|%.2f|%.2f", length.getValue() / 100.0, angle));
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
        boolean canPick = joined && me.equals(picker) && pickedFace.isEmpty();
        faceControls.setVisible(canPick);
        heads.setEnabled(canPick && !pending);
        tails.setEnabled(canPick && !pending);
        boolean canFlip = joined && !flipper.isEmpty() && me.equals(flipper) && tossWinner.isEmpty();
        flip.setVisible(canFlip);
        flip.setEnabled(canFlip && !pending);
        boolean pickingTarget = joined && target == 0 && me.equals(tossWinner) && !flipping && !ended;
        targetControls.setVisible(pickingTarget);
        targetField.setEnabled(pickingTarget && !pending);
        action.setEnabled(pickingTarget && !pending);
        lengthControls.setVisible(target > 0 && !ended);
        length.setEnabled(canPull());
        tossReadout.setText(tossWinner.isEmpty() || flipping ? " " :
                (tossWinner.equals(me) ? "You won the toss" : playerName(tossWinner) + " won the toss"));
        targetReadout.setText(target == 0 ? "Target period  —" : String.format(Locale.ROOT, "Target period  %.2f s", target));
        reset.setVisible(joined && ended && me.equals(admin));
        reset.setEnabled(!pending);
        if (!joined) {
            headline.setText("Connect to a room");
            details.setText("Enter your name and host address.");
        } else if (ended) {
            headline.setText(winner.isEmpty() ? "Round stopped" : me.equals(winner) ? "You won!" : "Round complete");
            details.setText(winner.isEmpty() ? "A player disconnected." :
                    winner.equals("TIE") ? "Both attempts tied." :
                            winner.equals(me) ? "Your swing was closest to the target." : playerName(winner) + " won.");
        } else if (flipping) {
            headline.setText("Flipping the coin…");
            details.setText("The result will appear on both screens.");
        } else if (!picker.isEmpty() && pickedFace.isEmpty()) {
            headline.setText(canPick ? "Choose Heads or Tails" : "Waiting for coin choice");
            details.setText(canPick ? playerName(seats[1]) + " will flip after you choose." :
                    playerName(picker) + " is choosing a side. You flip next.");
        } else if (!flipper.isEmpty() && tossWinner.isEmpty()) {
            headline.setText(canFlip ? "Flip the coin" : "Waiting for the flip");
            String selection = pickedFace.equals("HEADS") ? "Heads" : "Tails";
            String other = pickedFace.equals("HEADS") ? "Tails" : "Heads";
            details.setText(canFlip ? playerName(picker) + " chose " + selection + ". You’re " + other + "." :
                    "You chose " + selection + ". " + playerName(flipper) + " flips now.");
        } else if (!tossWinner.isEmpty() && target == 0) {
            headline.setText(pickingTarget ? "You won the toss!" : "Selecting target time");
            details.setText(pickingTarget ? "Set the goal for both players." :
                    playerName(tossWinner) + " is selecting the target time.");
        } else if (!attempt.isEmpty()) {
            headline.setText("Swing in progress");
            details.setText(turn ? "Your attempt is live." : "Watching " + playerName(owner) + ".");
        } else if (turn) {
            headline.setText("Your turn");
            details.setText("Set the length, then drag the weight sideways and release.");
        } else if (!owner.isEmpty()) {
            headline.setText("Other player’s turn");
            details.setText("Watching " + playerName(owner) + " set up the swing.");
        } else if (!ready) {
            headline.setText("Waiting for someone");
            details.setText("You’re connected. Share the host address and port.");
        } else if (scores.containsKey(me)) {
            headline.setText("Attempt recorded");
            details.setText("Waiting for the other attempt.");
        } else {
            headline.setText("Round ready");
            details.setText("The host is assigning the next turn.");
        }
        for (int i = 0; i < 2; i++) {
            players[i].setText(seats[i].isEmpty() ? "Waiting for player" :
                    names[i] + (seats[i].equals(me) ? " (you)" : ""));
            players[i].setToolTipText(names[i]);
            Double period = scores.get(seats[i]);
            times[i].setText(period == null ? "—" : String.format(Locale.ROOT, "%.2f s", period));
            errors[i].setText(period == null ? " " : String.format(Locale.ROOT, "Off target by %.3f s", Game.error(period, target)));
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
        JPanel panel = new JPanel(manager) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double width = getWidth() - 2.0;
                double height = getHeight() - 5.0;
                g.setColor(new Color(0, 5, 19, 62));
                g.fill(new RoundRectangle2D.Double(1, 4, width, height, 24, 24));
                Shape glass = new RoundRectangle2D.Double(1, 1, width, height, 23, 23);
                g.setPaint(new GradientPaint(0, 0, new Color(131, 170, 235, 42),
                        getWidth() * .7f, getHeight(), new Color(17, 28, 60, 117)));
                g.fill(glass);
                g.setPaint(new GradientPaint(0, 0, new Color(214, 238, 255, 97), 0, getHeight(), LINE));
                g.draw(glass);
                g.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(17, 17, 19, 17));
        return panel;
    }

    private static void glow(Graphics2D g, float x, float y, float radius, Color tint) {
        g.setPaint(new RadialGradientPaint(x, y, radius, new float[]{0, 1},
                new Color[]{tint, new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 0)}));
        g.fill(new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static final class Toast extends JLabel {
        long started;

        Toast() {
            super(" ");
            setFont(BODY.deriveFont(Font.BOLD, 13f));
            setForeground(GREEN);
            setBorder(new EmptyBorder(7, 13, 7, 13));
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (started == 0) return;
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            double opacity = Math.max(0, Math.min(1, Math.min(ms / 240, (4200 - ms) / 550)));
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive((float) opacity));
            g.translate((1 - opacity) * 10, 0);
            Shape pill = new RoundRectangle2D.Double(1, 1, getWidth() - 2.0, getHeight() - 2.0, 16, 16);
            g.setColor(new Color(35, 78, 96, 221));
            g.fill(pill);
            g.setColor(new Color(117, 235, 212, 100));
            g.draw(pill);
            super.paintComponent(g);
            g.dispose();
        }
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
        field.setDisabledTextColor(MUTED);
        field.setCaretColor(GREEN);
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
                Color highlight = getModel().isRollover() ? new Color(159, 255, 229) : new Color(122, 237, 216);
                Color base = getModel().isPressed() ? new Color(57, 137, 152) : new Color(59, 183, 188);
                g.setPaint(isEnabled() ? new GradientPaint(0, 0, highlight, 0, getHeight(), base) : SOFT);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 13, 13);
                if (isFocusOwner()) {
                    g.setColor(isEnabled() ? PAPER : GREEN);
                    g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 7, 7);
                }
                g.setFont(getFont());
                g.setColor(isEnabled() ? PAPER : MUTED);
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

    private final class Scope extends JComponent {
        private static final double YAW = Math.toRadians(15);
        private final SwingTrace trace = new SwingTrace();
        private final javax.swing.Timer timer = new javax.swing.Timer(16, event -> {
            repaint();
            if (notice.started != 0) notice.repaint();
        });
        private double period = Game.TARGET;
        private double previewLength = .95;
        private double pullAngle;
        private boolean dragging;

        Scope() {
            MouseAdapter pull = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event) || !canPull()) return;
                    Point2D bob = bobPosition(pullAngle);
                    if (bob.distance(event.getPoint()) > 29) return;
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    feedback.setText(" ");
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (!dragging) return;
                    updatePull(event);
                }

                @Override public void mouseMoved(MouseEvent event) {
                    boolean onWeight = canPull() && bobPosition(pullAngle).distance(event.getPoint()) <= 29;
                    setCursor(onWeight ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                }

                @Override public void mouseReleased(MouseEvent event) {
                    if (!dragging) return;
                    updatePull(event);
                    dragging = false;
                    setCursor(Cursor.getDefaultCursor());
                    release(pullAngle);
                }
            };
            addMouseListener(pull);
            addMouseMotionListener(pull);
            setFocusable(true);
            setToolTipText("Drag the weight sideways and release. Keyboard: arrows to pull, Space to release.");
            bindPullKey("LEFT", -5);
            bindPullKey("RIGHT", 5);
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "release");
            getActionMap().put("release", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    release(pullAngle);
                }
            });
            timer.start();
        }

        private void bindPullKey(String key, double degrees) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
            getActionMap().put(key, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (canPull()) pullAngle = limitAngle(pullAngle + degrees);
                }
            });
        }

        private void updatePull(MouseEvent event) {
            pullAngle = limitAngle(Math.toDegrees(Math.atan2((event.getX() - getWidth() / 2.0) / Math.cos(YAW),
                    Math.max(1, event.getY() - pivot()))));
            repaint();
        }

        private double limitAngle(double degrees) {
            return Math.max(-Game.MAX_ANGLE, Math.min(Game.MAX_ANGLE, degrees));
        }

        private double pivot() {
            return 64;
        }

        private double arm() {
            double max = Math.min(getWidth() * .45, getHeight() * .50);
            return Math.max(50, max * (.52 + .48 * (previewLength - Game.MIN_LENGTH) /
                    (Game.MAX_LENGTH - Game.MIN_LENGTH)));
        }

        private Point2D bobPosition(double degrees) {
            double radians = Math.toRadians(degrees);
            double lateral = arm() * Math.sin(radians);
            double depth = lateral * Math.sin(YAW);
            double perspective = 550 / (550 - depth);
            return new Point2D.Double(getWidth() / 2.0 + lateral * Math.cos(YAW) * perspective,
                    pivot() + arm() * Math.cos(radians) * perspective);
        }

        void clear() {
            trace.clear();
            pullAngle = 0;
            dragging = false;
            setCursor(Cursor.getDefaultCursor());
            period = Game.TARGET;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            if (!picker.isEmpty() && target == 0 && !ended) {
                paintToss(g, width, height);
                g.dispose();
                return;
            }
            double center = width / 2.0;
            double pivot = pivot();
            double arm = arm();
            double floor = pivot + arm + 27;
            double floorWidth = Math.max(145, arm * 1.35);
            glow(g, (float) center, (float) (floor - 60), (float) Math.max(90, arm * 1.05),
                    new Color(60, 192, 212, 33));
            for (int ring = 1; ring <= 3; ring++) {
                double radius = floorWidth * ring / 3;
                g.setColor(new Color(111, 208, 227, 19 + ring * 5));
                g.draw(new Ellipse2D.Double(center - radius, floor - radius * .18, radius * 2, radius * .36));
            }
            g.setColor(LINE);
            g.draw(new Line2D.Double(center, pivot, center, pivot + arm + 32));
            Point2D bob = bobPosition(canPull() || (pending && attempt.isEmpty() && ready) ? pullAngle :
                    trace.angleAt(System.nanoTime()));
            double bobX = bob.getX();
            double bobY = bob.getY();
            g.setPaint(new LinearGradientPaint((float) center, (float) pivot, (float) bobX, (float) bobY,
                    new float[]{0, 1}, new Color[]{new Color(170, 195, 222), GREEN}));
            g.setStroke(new BasicStroke(2.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(center, pivot, bobX, bobY));
            double lift = Math.max(0, pivot + arm - bobY);
            double shadowWidth = 48 + lift * .24;
            g.setColor(new Color(0, 5, 16, (int) Math.max(25, 116 - lift)));
            g.fill(new Ellipse2D.Double(bobX - shadowWidth / 2, floor - 5, shadowWidth, 10 + lift * .04));
            glow(g, (float) bobX, (float) bobY, 33, new Color(70, 225, 210, 64));
            g.setPaint(new RadialGradientPaint((float) bobX - 7, (float) bobY - 8, 29,
                    new float[]{0, .38f, .73f, 1},
                    new Color[]{new Color(228, 255, 248), GREEN, new Color(26, 141, 157), new Color(11, 50, 90)}));
            g.fill(new Ellipse2D.Double(bobX - 17, bobY - 17, 34, 34));
            g.setColor(new Color(191, 254, 246, 168));
            g.setStroke(new BasicStroke(1));
            g.draw(new Ellipse2D.Double(bobX - 17, bobY - 17, 34, 34));
            g.setColor(GREEN);
            g.fill(new Ellipse2D.Double(center - 5, pivot - 5, 10, 10));
            if (canPull()) {
                g.setFont(BODY.deriveFont(13f));
                g.setColor(MUTED);
                String instruction = Math.abs(pullAngle) < .01 ? "Drag the weight to release" :
                        String.format(Locale.ROOT, "%.0f° release angle", Math.abs(pullAngle));
                g.drawString(instruction, (float) (center - g.getFontMetrics().stringWidth(instruction) / 2.0),
                        (float) Math.min(floor + 34, height * .66 - 12));
            }

            double left = 34;
            double right = width - 14;
            double top = height * .69;
            double bottom = height - 30;
            double middle = (top + bottom) / 2;
            double scale = Math.max(.3, (bottom - top - 14) / (2 * (Math.abs(releaseAngle) + 8)));
            g.setFont(BODY.deriveFont(11f));
            g.setColor(MUTED);
            g.drawString("Angle (°)", 0, (int) top - 11);
            g.drawString("0", 14, (int) middle + 4);
            g.drawString("0 s", (int) left, height - 9);
            String end = trace.points().isEmpty() ? "" : String.format(Locale.ROOT, "%.2f s", period);
            g.drawString(end, (int) right - g.getFontMetrics().stringWidth(end), height - 9);
            g.setColor(LINE);
            g.setStroke(new BasicStroke(1));
            for (int i = 0; i <= 4; i++) {
                double x = left + (right - left) * i / 4;
                g.draw(new Line2D.Double(x, top, x, bottom));
            }
            g.draw(new Line2D.Double(left, middle, right, middle));
            Path2D chart = new Path2D.Double();
            double previousTime = -1;
            for (SwingTrace.Sample sample : trace.points()) {
                double x = left + (right - left) * Math.min(1, sample.seconds() / period);
                double y = middle - sample.angle() * scale;
                if (previousTime < 0 || sample.seconds() - previousTime > SwingTrace.PLOT_GAP) chart.moveTo(x, y);
                else chart.lineTo(x, y);
                previousTime = sample.seconds();
            }
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(117, 235, 212, 29));
            g.draw(chart);
            g.setColor(GREEN);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(chart);
            if (ended && me.equals(winner)) paintConfetti(g, width, height * .58);
            if (resultStarted != 0) paintResult(g, width);
            g.dispose();
        }

        private void paintResult(Graphics2D g, int width) {
            double alpha = Math.min(1, (System.nanoTime() - resultStarted) / 380_000_000.0);
            Graphics2D banner = (Graphics2D) g.create();
            banner.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
            double panelWidth = Math.min(310, width - 24);
            double left = (width - panelWidth) / 2;
            double y = 7 - (1 - alpha) * 12;
            Shape panel = new RoundRectangle2D.Double(left, y, panelWidth, 39, 15, 15);
            banner.setPaint(new GradientPaint(0, (float) y, new Color(36, 85, 111), 0, (float) y + 39,
                    new Color(26, 48, 77)));
            banner.fill(panel);
            banner.setColor(new Color(117, 235, 212, 124));
            banner.draw(panel);
            String title = winner.equals("TIE") ? "It’s a tie" : winner.equals(me) ?
                    "Congratulations, you won!" : playerName(winner) + " wins";
            centered(banner, title, width / 2.0, y + 25, 16, true, INK);
            banner.dispose();
        }

        private void paintToss(Graphics2D g, int width, int height) {
            double progress = flipping ? Math.min(1, (System.nanoTime() - flipStarted) / 1_350_000_000.0) : 0;
            double x = width / 2.0;
            double restingY = height * .40;
            double flight = Math.min(1, progress / .83);
            double hop = progress < .83 ? 4 * flight * (1 - flight) : .12 * Math.sin(Math.PI * (progress - .83) / .17);
            double y = restingY - (flipping ? hop * Math.min(89, height * .18) : 0);
            double rotation = (coinFace.equals("TAILS") ? 7 : 6) * Math.PI * (1 - Math.pow(1 - flight, 1.65));
            double facing = Math.cos(rotation);
            double stretch = flipping ? Math.max(.035, Math.abs(facing)) : 1;
            boolean landed = !coinFace.isEmpty() && !flipping;
            glow(g, (float) x, (float) y, (float) Math.min(185, height * .37), new Color(228, 160, 77, 62));
            g.setColor(SOFT);
            double shadow = 96 - 35 * hop;
            g.fill(new Ellipse2D.Double(x - shadow / 2, restingY + 68, shadow, 14));
            Graphics2D coin = (Graphics2D) g.create();
            coin.translate(x, y);
            coin.rotate(flipping ? Math.sin(flight * Math.PI) * .18 : 0);
            double rim = 6 + (1 - stretch) * 12;
            coin.setColor(GOLD_EDGE.darker());
            coin.fill(new Ellipse2D.Double(-55 * stretch - rim / 2, -53, 110 * stretch + rim, 110));
            coin.scale(stretch, 1);
            coin.setColor(GOLD_EDGE);
            coin.fill(new Ellipse2D.Double(-57, -54, 114, 114));
            coin.setPaint(new GradientPaint(-46, -50, new Color(255, 219, 151), 52, 58, GOLD));
            coin.fill(new Ellipse2D.Double(-55, -56, 110, 110));
            coin.setColor(GOLD_EDGE);
            coin.setStroke(new BasicStroke(2));
            coin.draw(new Ellipse2D.Double(-45, -46, 90, 90));
            String face = landed ? coinFace.substring(0, 1) :
                    flipping ? (facing >= 0 ? "H" : "T") : "H / T";
            if (stretch > .14) centered(coin, face, 0, landed || flipping ? 13 : 7, landed || flipping ? 39 : 22, true, GOLD_EDGE);
            coin.dispose();
            String heading = landed ? (me.equals(tossWinner) ? "Congratulations!" : playerName(tossWinner) + " won the toss") :
                    flipping ? "Coin in the air" : "Heads or tails?";
            String subtitle = landed ? (coinFace.equals("HEADS") ? "Heads" : "Tails") + " landed" : " ";
            double textY = Math.max(restingY + 115, height * .67);
            centered(g, heading, x, textY, 29, true, INK);
            if (landed) {
                centered(g, subtitle, x, textY + 28, 15, false, MUTED);
                if (me.equals(tossWinner)) paintConfetti(g, width, height * .66);
            }
        }

        private void centered(Graphics2D g, String text, double x, double baseline, int size, boolean bold, Color color) {
            g.setFont(BODY.deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
            g.setColor(color);
            g.drawString(text, (float) (x - g.getFontMetrics().stringWidth(text) / 2.0), (float) baseline);
        }

        private void paintConfetti(Graphics2D g, int width, double area) {
            if (celebrationStarted == 0) return;
            double elapsed = (System.nanoTime() - celebrationStarted) / 1_000_000_000.0;
            if (elapsed > 2.9) return;
            Color[] colors = {new Color(167, 148, 246), GOLD, GREEN, new Color(250, 151, 165)};
            for (int i = 0; i < 36; i++) {
                double time = elapsed - (i % 9) * .075;
                if (time < 0) continue;
                double x = width * (i % 2 == 0 ? .17 : .83) + ((i * 37) % 101 - 50) * time;
                double y = area * .56 - (65 + (i * 19) % 89) * time + 92 * time * time;
                if (y < 0 || y > area) continue;
                Graphics2D piece = (Graphics2D) g.create();
                piece.translate(x, y);
                piece.rotate(time * ((i % 5) - 2));
                piece.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, (2.9 - elapsed) / .6)));
                piece.setColor(colors[i % colors.length]);
                piece.fillRoundRect(-3, -2, 7 + i % 3, 5, 2, 2);
                piece.dispose();
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Desktop(args));
    }
}
