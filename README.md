# LabLink · The Two-Second Swing

LabLink is a two-player Java desktop game for Network Programming Assignment 02. Pick a pendulum length and try to make one full swing last **2.00 seconds**. Both players watch the live swing. The host scores each attempt, and the closest time wins.

## Run on one laptop

From the project directory, build with **JDK 17 or newer** and Maven. The application uses the Java standard library.

```powershell
mvn package
```

Open three terminals in that directory and run one command in each.

```powershell
java -cp target/classes lablink.Host 127.0.0.1 5050
java -cp target/classes lablink.Desktop Asha 127.0.0.1 5050
java -cp target/classes lablink.Desktop Kavin 127.0.0.1 5050
```

Click **Connect** in both windows. One player clicks **Claim turn**, enters a length from **0.25 to 2.00 m**, and clicks **Start swing**. After the first score appears, the other player claims the turn. Try **0.95 m** and **1.08 m** for a round where the first length wins. The first joined player can start another round after the results.

To compile without Maven in PowerShell, use the following and substitute `out` for `target/classes` in the run commands.

```powershell
$files = Get-ChildItem src/main/java -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
javac --release 17 -encoding UTF-8 -d out $files
```

## Run on two laptops

Use the same Wi-Fi network. Start the host with its Wi-Fi IPv4 address as the bind address, for example `java -cp target/classes lablink.Host 192.168.1.20 5050`. Enter that same address in both clients, including the client running on the host laptop. Each client binds its own UDP port and announces it over TCP. The local network must allow peer traffic.

## Networking workflow

| Feature | Implementation | Demonstration |
| --- | --- | --- |
| TCP client | `Connection` uses `java.net.Socket` for room commands and reliable events. | Join two clients and compare the final scores on both. |
| Java NIO host | `Host` multiplexes non-blocking channels with a `Selector`, per-connection buffers, and queued writes. | Send half a request on one socket while another receives a response. |
| UDP swing updates | The host streams sequenced angle samples with `DatagramSocket`. The client drops stale samples and interpolates the display between updates. | Start the host with a third argument of `7` to skip sample 7 for the second client. Its monitor reports the gap and keeps moving. |
| Concurrent turn claims | Two workers process requests from different sessions. The room synchronizes each state transition. | Claim from both clients at once and observe one turn owner. |
| Server permissions | The host binds identity to the TCP session and checks room state, turn ownership, and length before starting a swing. | A spectator’s control request is rejected while the swing continues. The integration check sends that request. |

The host calculates the small-angle pendulum period and compares unrounded errors from 2.00 seconds. TCP handles joins, turns, swing starts, scores, and results. UDP carries only transient angle samples. A departure during an unfinished round aborts it. The remaining player can reset and admit a replacement.

## Verify the workflow

Run the integration check with local TCP and UDP available.

```powershell
mvn test-compile
java -cp "target/classes;target/test-classes" lablink.WorkflowCheck
```

The checker starts its own host on an ephemeral loopback port. It covers partial TCP frames, competing turn claims, invalid lengths, spectator permissions, UDP loss, scoring, ties, reset, disconnect, and rejoin.
