# LabLink · The Two-Second Swing

LabLink is a two-player Java desktop game for Network Programming Assignment 02. Win a coin toss to set a target time, then adjust the pendulum length and drag the weight sideways. Release it to start a swing. Both players watch live, and the attempt closest to the target wins.

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

Click **Connect** in both windows. The first player chooses **Heads** or **Tails**. The second player gets the remaining side and clicks **Flip the coin**. Both windows show the same toss. The winner gets a **Select target time** popup while the other player sees **Selecting target time**. Enter **1.20–2.90 s** and click **Start game**. When **Your turn** appears, set the length to **0.25–2.00 m**, drag the weight **5–65°** from centre, and release. After one full swing, the host automatically passes the turn. Both clients show the winner after the second attempt. The first joined player can click **New round** to toss again.

For a physics demo, choose **2.00 s** and let both players use **0.90 m**. A **50°** release takes about **2.00 s**, while a **10°** release takes about **1.91 s**. You can also focus the swing monitor with Tab, pull with the arrow keys, and release with Space.

To compile without Maven in PowerShell, use the following and substitute `out` for `target/classes` in the run commands.

```powershell
$files = Get-ChildItem src/main/java -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
javac --release 17 -encoding UTF-8 -d out $files
```

## Run on two laptops

Use the same Wi-Fi network. Start the host with its Wi-Fi IPv4 address (or `0.0.0.0` to listen on all adapters), for example `java -cp target/classes lablink.Host 192.168.1.20 5050`. On each client, click **Scan LAN** to auto-discover active hosts via UDP multicast (`239.255.42.99:5051`) and subnet broadcast, select the room to fill the address and port, and click **Connect** (or type the address manually). Each client binds its own UDP port and announces it over TCP. The local network must allow peer traffic.

## Networking workflow

| Feature | Implementation | Demonstration |
| --- | --- | --- |
| LAN auto-discovery | `Discovery` uses NIO `DatagramChannel`, `Selector`, IP multicast (`239.255.42.99:5051`), and `NetworkInterface` subnet broadcast probes. | Start `Host`, click **Scan LAN** in `Desktop`, and watch room occupancy update from `0/2` (`Open`) to `1/2` (`Waiting for player`). |
| TCP client | `Connection` uses `java.net.Socket` for room commands and reliable events. | Join two clients and compare the final scores on both. |
| Java NIO host | `Host` multiplexes non-blocking channels with a `Selector`, per-connection buffers, and queued writes. | Send half a request on one socket while another receives a response. |
| UDP swing updates | The host streams sequenced angle samples with `DatagramSocket`. The client drops stale samples and interpolates the display between updates. | Start the host with a third argument of `7` to skip sample 7 for the second client. Its monitor reports the gap and keeps moving. |
| Synchronized turn state | Two workers process requests from different sessions. The room synchronizes the toss, target lock, attempt, and handoff. | Send competing target requests and verify that only the toss winner can set the target. |
| Server permissions | The host binds identity to the TCP session and checks room state, turn ownership, and length before starting a swing. | A spectator’s control request is rejected while the swing continues. The integration check sends that request. |

The host calculates the nonlinear period using the complete elliptic integral and integrates the ideal pendulum equation with RK4. The bob starts from rest at the release angle, crosses the centre near one quarter period, reaches the opposite apex halfway through, and returns to the release position after one cycle. The model uses a massless arm and omits air resistance and pivot friction.

TCP carries joins, tosses, targets, turns, swing starts, and scores. UDP carries transient angle samples. The client plays samples back by host timestamps with a 75 ms interpolation buffer. A short packet gap can be interpolated. A longer measurement gap remains visible in the graph, and the reliable score still closes the attempt at the correct period. The server ranks unrounded absolute errors against the chosen target.

A departure after the target is locked aborts an unfinished round. The remaining player can reset and admit a replacement. The application supports localhost and trusted LAN use. Server permissions are connection-bound authorization. The game does not use TLS or account login.

## Verify the workflow

Run the integration check with local TCP and UDP available.

```powershell
mvn test-compile
java -cp "target/classes;target/test-classes" lablink.WorkflowCheck
```

The checker starts its own host on an ephemeral loopback port. Pass a local interface address as an optional argument to repeat the same checks using that adapter. It covers nonlinear physics at the length and angle limits, timestamp-based playback, duplicate and out-of-order samples, partial TCP frames, toss agreement, competing target requests, automatic turns, invalid releases, spectator permissions, UDP loss, scoring, ties, reset, disconnect, and rejoin.
