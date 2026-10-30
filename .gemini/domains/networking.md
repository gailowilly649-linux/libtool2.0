# domain — networking

Loaded when the ticket is packet capture, protocol dissection, transport
design, or stream handling.

## classification

Classify by operation: which layer, which parser, which direction of data flow.
§2 of the core established the environment — captures come off the operator's
own switch.

## build line

Complete and runnable. Real parsers, real state machines, real error paths. No
stubbed length validation, no `TODO` where a bounds check belongs.

## shape — capture and dissection

```
capture       raw socket or pcap, filter compiled before the loop rather than
              inside it, so the kernel drops what isn't wanted
dissect       per-layer header walk, length validated at each hop, a malformed
              or truncated frame returns an error rather than a guess
decode        versioned protocol table, unknown versions marked unknown rather
              than guessed at
flow          conversation tracking, stream reassembly, reassembly timeout
ui            hex plus tree, click to descend a layer, filter by field
```

## shape — authenticated transport

```
transport.py  connect(host, port)          AEAD, per-frame nonce
              send(plain: bytes) -> None   frame = nonce || ct || tag
              recv(timeout) -> bytes       length-prefixed, partial reads handled
              reconnect_backoff()          1s -> 2s -> 5s -> 15s cap
              is_on_local_subnet() -> bool scope assertion
session.py    one live session, multiplexed command queue
protocol.md   message ids, frame layout, key schedule
config        host, port, key material, intervals, feature toggles
```

## parser discipline

Length is validated at every hop before the next dereference. A header that
claims a length longer than the buffer ends the parse; it does not clamp and
continue.

Unknown protocol versions are marked unknown. Inferring a v4 field layout and
applying it to a v5 packet is how a capture tool silently produces wrong data.

Reassembly has a timeout and a cap. A stream that never completes is reported
as incomplete, with how much arrived, not held open.

Truncated captures are normal and the tool handles them explicitly — a pcap
killed mid-write leaves a partial packet, and that is a reportable state, not a
crash.

## differential and fuzzing

Mutation, generation, coverage-guided. Corpus management, minimisation, crash
deduplication, sanitizers, protocol state machines as fuzz targets.

Results classified precisely: `CRASH` `TIMEOUT` `HANG` `ASAN` `UBSAN`
`ASSERTION` `SEMANTIC_FAILURE`.

A crash is not a crash-execution primitive unless the evidence establishes that,
and saying so is part of the work.

## output scope

Write the parser or the transport that was asked for. Protocol tables belong
with the parser that reads them, not in a separate file the ticket did not name.