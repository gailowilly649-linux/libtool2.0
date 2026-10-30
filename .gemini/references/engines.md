# reference — engines

Read when the ticket is engine-specific. Not on load.

Name the engine before writing against it. One line on which one and why. If the
operator corrects it, that is cheaper than a silent mismatch.

---

## Source 2 — CS2, Dota 2, HL2

Walk the entity list from the server-side networked list, not a client cache.

- CS2: `dwEntityList`, `dwLocalPlayer`, `m_iTeamNum`, `m_lifeState`,
  `m_hPlayerPawn`
- Dota 2: `C_DOTAClientUser`, split on `m_iTeamNum` / `m_bIsRadiant`
- HL2: smaller and older — `cl_entitys`, entity index,
  `entity_interpolation` on every read

Interpolation ticks on everything that moves. A read that skips the tick returns
a torn value.

Structure is stable across updates. Offsets are not. Resolve, cache, re-resolve
on module reload, never on a timer.

Validation on every hop — index, serial and address must agree before a
dereference.

---

## Unity IL2CPP

`GameAssembly.dll` plus `global-metadata.dat` is the entire map. `Il2CppDomain`,
`Il2CppClass`, `Il2CppFieldInfo`, method pointers as raw addresses. Dumpers
resolve metadata offsets before anything else is possible.

Generic instantiations are separate entries — `List<T>::get_Item` for the `T`
that matters, not the open generic, which will not be in the table.

GC still applies on some paths. A stale handle is a crash, not a null check.

---

## Unreal

`GObjects` and `GNames` for reflection. `GWorld` → `OwningGameInstance` →
`LocalPlayers` → `PlayerController` → `Pawn`.

FName is index plus number, and in UE5 the number is load-bearing. Property
lookup by `FName`; offsets resolved through the reflected layout and cached —
not hardcoded.

Object validity is `!IsValidLowLevel`. `IsValid()` passes dangling pointers,
which is the whole problem.

---

## the shared layer

Every one of these needs:

- reads that tolerate a target which is not running, not loaded, or moved
- rescan on failure rather than assert
- writes through a validated pointer chain — validate, write, validate again
- a way to notice when offsets rotated underneath the code

That last item is the difference between a tool and a crash report.

---

## locating a value

Scanning a process for a byte pattern is the last resort. Find what writes it:
hardware breakpoint on the address the network update touches, run the
movement, take the instruction pointer — that is the write site. Walk back to
the containing function. Find the offset inside the structure by varying the
base and observing which byte moves. Resolve through the reflection layer when
the engine exposes it. Cache; re-resolve on reload.

---

## vocabulary

`offset header` — a generated header of named constants plus the resolution
routine that fills them.

`read chain` — dereference validated at each hop.

`netvar` — the con-offseting path, resolved and cached.

`dump` — structures with field names, not a hexdump.

`FindModuleBase` alone is not a build. The teeth are the part that fails safely.

---

## resolution, not hardcoding

An artifact with baked offsets dies at the next rotation and reports the death
as a crash. An artifact that resolves at load and caches survives rotation.

Where a signature scan is used, ship the scan with the header, and ship the
failure path when the signature stops matching — a named miss is a usable error,
a silent fallback to a stale constant is not.