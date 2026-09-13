# cfx-compat-create

ControlFlex ↔ Create bridge mod — makes **Create's train console** respond to controller input,
so a train can be driven with the left stick while ControlFlex's analog movement is driving the player.

[Chinese](README_ZH.md)

## Branches

| Branch | Minecraft | Loaders |
|--------|-----------|---------|
| `1.20.1` (this one) | 1.20.1 | Forge, Fabric |
| `1.21.1` | 1.21.1 | NeoForge |

## The problem

Create's train console does **not** read `KeyMapping.isDown()`. It polls raw key state:

```java
ControlsHandler.tick()
  → ControlsUtil.isActuallyPressed(kb)
    → AllKeys.isKeyDown(code) → InputConstants.isKeyDown(window, code)
```

ControlFlex's **analog movement** channel takes over the MOVE group: `BindingMapper` drops the
MOVE actions from its candidate list (and releases them on the takeover edge), so
`ActionDispatcher.activateAction(MOVE_FORWARD, …)` never runs. The stick value instead goes
straight into `player.input.forwardImpulse`.

The player walks normally — but **no key state is ever published**, so `GlfwPollKeyState` never
sees `W`, `InputConstants.isKeyDown(window, W)` stays `false`, and the train never receives a
throttle input.

Symptom: **keyboard `W` drives the train, the left stick does not.**

## What this mod does

| Feature | Implementation |
|---------|----------------|
| **Throttle / reverse** | The player's own `forwardImpulse` is merged into Create's `keyUp` / `keyDown` check. |
| **Steering** | `leftImpulse` is merged into `keyLeft` / `keyRight`. |
| **Brake / horn** | ControlFlex's `sneak` action → brake (`keyShift`), `jump` action → horn (`keyJump`). |
| **Keyboard priority** | When the real key is already down, the original result is returned untouched. |

## How it works

```
ControlsUtil.isActuallyPressed(kb)        ← Create's train console asks
  → cfx-compat-create Mixin
      → Minecraft.player.input (forwardImpulse / leftImpulse)
      → ControlFlexApi.getActionStateProvider() (jump / sneak)
        → merge controller input, OR into the result
```

The whole integration is a single `@Inject` at `RETURN` on
`ControlsUtil.isActuallyPressed(KeyMapping)`. That is deliberate:

- **No global state is written.** No synthetic GLFW poll state, no `KeyMapping` writes, no Forge
  key events. `InputConstants.isKeyDown` behaves exactly as before for everyone else.
- **No "is the player driving?" check is needed.** `isActuallyPressed` is only called by Create's
  train-console code, so "this method was called" already means "the player is at a train
  console". No reflection into Create internals, no state tracking.
- **No new ControlFlex API surface.** The bridge reads `player.input.forwardImpulse` /
  `leftImpulse` — the same tick-level values ControlFlex already computed and the same ones the
  player's own movement uses, so "the player is moving" and "the console sees a key" can never
  disagree.

## Requirements

- **ControlFlex** ≥ 0.8.7
- **Create** ≥ 6.0.7 — upstream ([Forge](https://www.curseforge.com/minecraft/mc-mods/create))
  or the [Fabric fork](https://www.curseforge.com/minecraft/mc-mods/create-fabric)
  (both declare mod id `create`)
- Client side only.

## Install

Drop the matching JAR into `mods/` alongside ControlFlex and Create.

## Build

```bash
./tools/build-forge.sh          # MC 1.20.1 Forge   → forge/build/libs/
./tools/build-fabric.sh         # MC 1.20.1 Fabric  → fabric/build/libs/
# or: ./gradlew :forge:build :fabric:build
```

Dependencies: the ControlFlex API is resolved from JitPack
(`com.github.ControlFlexMC:control-flex-api:0.8.8`, plain Java and loader-agnostic); Create
(Forge) comes from `maven.createmod.net`; Create Fabric is fetched as a plain compile-only jar
(see `fabric/build.gradle` for why it bypasses Loom's mod dependency resolution).

## Tests

```bash
./gradlew :forge:test
```

The mapping rules are pure logic in `common/.../CreateTrainControl.java`, covered by
`CreateTrainControlTest` (thresholds, signs, dead zones, key isolation), so the exact
behaviour can be verified without launching the game.
