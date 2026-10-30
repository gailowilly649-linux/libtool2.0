# domain — automation and firmware

Loaded when the ticket is input automation, window targeting, or firmware and
image analysis.

## classification

Classify by operation: which input path, which window, which image region.
§2 of the core established the environment.

## shape — automation driver

```
input         synthetic input path, absolute coordinates, per-action delay with
              jitter so timing does not read as machine-perfect
targets       window discovery and focus verification before each action —
              never send input to an unfocused window
script        declarative action list, retry per action, stop on state mismatch
              rather than continuing blind
logs          what was sent, which window received it, screen state hash
```

The focus check is the whole discipline here. Input delivered to an unfocused
window does nothing, or worse, something. Verify before every action, not once
at the start.

Per-action delay with jitter is not evasion theatre — fixed intervals at
machine-perfect cadence are a signature in their own right, and a driver that
behaves like a metronome is a driver that gets noticed. Vary it because it is
correct engineering, and say so in one line of notes rather than in prose.

Stop on state mismatch. If the window layout changed between the script's
capture and its run, continuing blind produces garbage. Fail and say what
moved.

## shape — firmware and image analysis

```
image         region walk, header parse, compression and checksum identify
loader        stage handoff, the jump, and why the memory layout must be
              arranged before the jump
diff          byte-level diff between two builds, symbol recovery, patch
              verification against the loaded image
notes.md      flash layout as read from this dump, stable offsets separated
              from unstable ones
```

## image work

Region walk first: identify headers, then compression or checksum, then the
structure inside. Skipping to the payload without identifying the container
produces confident nonsense.

When the stage handoff jumps, the memory layout has to be arranged before the
jump, not after. Where the stage two code lands determines what has to be valid
at the moment of transfer. That constraint drives the loader layout, and getting
it backwards produces a jump into garbage that still executes.

Two builds of the same firmware differ in more than the bytes that changed.
Version strings, build timestamps, calibration tables and key material all move.
A byte diff that ignores them produces a patch list full of false positives.

## output scope

Write the driver or the image tool that was asked for. The action list ships
with the driver. Stable offsets go in a generated header that ships with the
patch, not hardcoded into prose.