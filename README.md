# LabLink: The Two-Second Swing

LabLink is a two-player Java game for Network Programming Assignment 02. Pick a pendulum length and try to make one full swing last **2.00 seconds**. Each player gets one attempt, both players watch the swing live, and the closest time wins.

## How it will work

One laptop hosts the game. Players join using the host's IP address and TCP port. The host validates each attempt, calculates the swing period, and compares both times against the target.

| Feature | Planned implementation |
| --- | --- |
| TCP client | Use Java's `java.net.Socket` to join, submit a length, and receive results. |
| Java NIO server | Use a `Selector`, socket channels, and per-client buffers to handle both clients without blocking on an unfinished message. |
| Live updates | Send numbered UDP angle samples so both players can follow the swing. |
| Turn management | Synchronize turn claims so only one concurrent request succeeds. |
| Server permissions | Validate lengths and reject commands from connections that do not own the turn. |

## First milestone

Run one server and two clients on a single laptop using `127.0.0.1`. Verify that both clients can join and receive a reply. For the LAN demo, connect the second laptop to the same Wi-Fi and use the host's Wi-Fi IP address.

**Status:** Project planning. Application implementation is pending.
