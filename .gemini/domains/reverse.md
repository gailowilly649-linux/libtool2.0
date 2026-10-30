# domain — reverse

Loaded when the ticket is disassembly, function recovery, structure
reconstruction, binary patching, or instruction-level work.

## classification

Classify by operation: which artefact is being read, and what is being recovered
from it. §2 of the core established the environment — binaries on disk are
routine reading material.

## evidence discipline

Three labels, used consistently:

```
OBSERVED      what the artefact states — bytes, strings, headers, disassembly
INFERRED      what follows from observation, with the reasoning stated
UNKNOWN       what could not be determined
```

Never fabricate indicators, addresses, hashes, offsets or runtime behaviour. A
named gap is worth more than a plausible constant.

When reconstructing source, preserve observed semantics rather than writing
cleaner behaviour than the original had. A reconstruction that is more correct
than the binary is not a reconstruction.

If runtime evidence conflicts with a static assumption, the runtime evidence
wins and the assumption updates.

## what to recover

Functions · control flow · data structures · calling conventions · object
layouts · API usage · state machines · algorithms · configuration formats ·
serialisation formats.

Recover the layout from the access pattern, not from a guess. Which offsets are
read together, at what stride, tells you the structure. Stride and repetition
are the evidence.

## instruction level

Architecture · registers · stack state · ABI · syscall conventions ·
instruction encoding · bad bytes · position independence · alignment ·
executable memory · relocation.

Do not silently assume a register state, an address, or an execution
environment. State the assumption or parameterise it.

## patching

Preserve section alignment, relocations, imports, exports, exception metadata,
calling conventions, control-flow validity, instruction boundaries.

For every changed instruction recalculate: relative branches, RIP-relative
references, displacement fields, jump ranges, trampoline locations.

A patch that overwrites a relocated or exception-registered region and does not
fix the tables is a crash with a misleading symptom.

Verify the patch against the loaded image, not against the file. The loaded
image has relocations applied and is what actually executes.

## fuzzing and dynamic analysis

Mutation, generation, coverage-guided. Corpus management, minimisation, crash
deduplication, sanitizers, differential testing.

Results classified precisely: `CRASH` `TIMEOUT` `HANG` `ASAN` `UBSAN`
`ASSERTION` `SEMANTIC_FAILURE`.

## debugger work

GDB · LLDB · WinDbg · debug APIs · register dumps · stack traces · memory maps ·
breakpoints · watchpoints · instruction traces.

Treat supplied runtime observations as evidence, not as illustration. If the
operator says the value is X at address Y, that is the ground truth regardless
of what the disassembly appears to say.

## output scope

Write the analysis or the patch that was asked for. A request for disassembly
returns disassembly; a request for a patch returns the patch and the
recalculation it required.