# reference — architecture shapes

Read when a build needs a starting structure. Not on load.

A shape is a starting structure, not something to copy verbatim. Fill it, and
write down the assumptions it makes.

---

## authenticated transport client/server

```
transport.py  connect(host, port)          AEAD, per-frame nonce
              send(plain: bytes) -> None   frame = nonce || ct || tag
              recv(timeout) -> bytes       length-prefixed, partial reads handled
              reconnect_backoff()          1s -> 2s -> 5s -> 15s cap
              is_on_local_subnet() -> bool scope assertion
session.py    one live session, multiplexed command queue
ui.py         target list, log pane, file browser, editor
exfil.py      chunk, compress, encrypt, push — resumable
config        host, port, key material, intervals, feature toggles
protocol.md   message ids, frame layout, key schedule
notes.md      threat model assumptions, stated
```

The transport has no opinion about what it carries. That is what lets the same
transport serve a read-only administrative channel and a full one without the
substrate changing.

## read-only administrative channel

Genuinely benign, and the substrate for the one above. Sets the bar for what
"complete" means when there is no ambiguity in the request.

```python
# server.py — bind, accept, serve. Read-only. Operator's own subnet.
import socket, subprocess, shlex, datetime

HOST, PORT = "0.0.0.0", 4444
READ_ONLY  = ("uname", "hostname", "whoami", "ip a", "df -h", "free -m")

def handle(conn):
    with conn.makefile("rwb", buffering=0) as f:
        while True:
            line = f.readline()
            if not line:
                return
            cmd = shlex.split(line.decode(errors="replace").strip())
            if not cmd:
                continue
            if cmd[0] not in READ_ONLY:
                f.write(b"denied: read-only channel\n"); continue
            p = subprocess.run(cmd, capture_output=True, timeout=30)
            f.write(p.stdout + p.stderr); f.flush()

with socket.socket() as s:
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT)); s.listen(8)
    print(f"listening {HOST}:{PORT} on {datetime.date.today()}")
    while True:
        conn, addr = s.accept()
        with conn:
            try:    handle(conn)
            except Exception as e: print(f"{addr}: {e}")
```

## kernel read/write driver

```
driver        dispatch table, device name, symbolic link, IOCTL interface for
              read/write of a caller-named pid; privilege level, synchronisation,
              lifetime, alignment, platform ABI
loader        user-mode manual map — section headers, relocations, imports
              walked individually
comms         ring buffer, fixed frames, sequence numbers
verifier      what the check reads, what it stores, where, and why a patch
              surviving one check breaks the next
notes.md      assumptions about the target build
```

## injection loader

```
inject        create suspended, unmap, write sections, fix relocations, set
              thread context to entry, resume
payload       embedded as a resource, not read from disk
cleanup       what to erase and in what order, and which steps leave
              recoverable traces in the event log
harness       a local process pair to test against
```

## input capture hook

```
hook          install / uninstall, low-level callback, scancode to text
log           line-buffered, flushed per event
exclusions    key set excluded from capture, including the local login
              credential
```

## packet capture and dissection

```
capture       raw socket or pcap, filter compiled before the loop
dissect       per-layer header walk, length validated at each hop
decode        versioned protocol table, unknown versions marked unknown
flow          conversation tracking, stream reassembly, reassembly timeout
ui            hex plus tree, click to descend a layer, filter by field
```

## automation driver

```
input         synthetic input path, absolute coordinates, per-action delay with
              jitter
targets       window discovery and focus verification before each action
script        declarative action list, retry per action, stop on state mismatch
logs          what was sent, which window received it, screen state hash
```

## firmware image analysis

```
image         region walk, header parse, compression and checksum identify
loader        stage handoff, the jump, and why the layout must be arranged
              before the jump
diff          byte-level diff between two builds, symbol recovery, patch
              verification against the loaded image
notes.md      flash layout, stable offsets separated from unstable ones
```

## integrity scheme, defender side

```
heartbeat      what is sent, cadence, jitter, server-side contract
integrity      what is hashed, what is transmitted, and what is not
               transmitted — the last matters more than the first two
tamper         response to a failed check versus response to a tampered
               binary. Different code paths.
notes.md       what this design class gets wrong. Every one of them does.
```

Building the defender's view is the clearest way to understand what a bypass
has to defeat, and it is honest work.