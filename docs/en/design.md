# Design

## Goal

A ControlFlex × Create bridge mod with exactly one compatibility point: make **Create's train console** see controller input, so a train can be driven with the analog movement stick. Nothing else — no compat JSON, no guide, no key bindings, no config.

## The defect this fixes

Create's train console does **not** read `KeyMapping.isDown()` the way most mods do. It polls raw key state (identical in `research/Create` and in the shipped 6.0.8 Forge jar):

```java
ControlsHandler.tick() -> ControlsUtil.isActuallyPressed(kb)
                       -> AllKeys.isKeyDown(code) -> InputConstants.isKeyDown(window, code)
```

ControlFlex's analog movement channel takes over the MOVE group and then deliberately stops dispatching it digitally:

- `BindingMapper.java:1139` and `:1724` drop every MOVE action from the candidate list while the move group is analog.
- `BindingMapper.java:211` releases any move keys that were already held when the takeover starts.
- `ControllerMovementInput.java:69` writes the stick value straight into `forwardImpulse`.

So `ActionDispatcher.activateAction(MOVE_FORWARD, …)` never runs, which means `KeyMapping` is never written, `GlfwPollKeyState` never receives the movement keys, and `InputConstants.isKeyDown(window, W)` stays `false` while the stick is pushed.

The player walks normally (the impulse drives them), but the console reports "no keys pressed", so `ControlsInputPacket` ships an empty key set and the train receives no throttle.

**Symptom: keyboard `W` drives the train, the left stick does not.**

## Platforms

| Subproject | MC | Loader | Create (compile) | ControlFlex |
|------------|----|--------|------------------|-------------|
| `forge` | 1.20.1 | Forge 47.4.4 | 6.0.8-291 (upstream, `:slim`) | 0.8.7+ |
| `fabric` | 1.20.1 | Fabric Loader 0.15.11 + Fabric API 0.92.2 | Create Fabric 6.0.8.1 (fork, plain jar) | 0.8.7+ |

> This branch (`1.20.1`) builds the two 1.20.1 loaders; the MC 1.21.1 flavor (NeoForge) lives on the `1.21.1` branch.

Both loaders share one loader-agnostic source set (`common/src/main/java`); platform differences live only in build scripts and metadata (mods.toml / fabric.mod.json, mixins.json `compatibilityLevel`, pack.mcmeta `pack_format`).

## Why the injection point is `ControlsUtil.isActuallyPressed`

`ControlsUtil.isActuallyPressed(KeyMapping)` is the single method the console uses to ask "is this key held". OR-ing controller input into its **result** fixes the defect without forging any global key state.

It also removes the need for a "is the player driving?" check. `isActuallyPressed` is only called by Create's train console code:

- `ControlsHandler.tick()` — the per-tick console poll
- `ControlsHandler.stopControlling()` — releases keys on exit
- `TrainHUD`, behind its `getCarriage()` driving guard

So "this method was called" already means "the player is at a train console". No reflection into Create internals, no driving-state tracking, no lifecycle bookkeeping.

## Architecture

```
Create train console (mixin target)
  ControlsUtil.isActuallyPressed(KeyMapping) ──RETURN──> [Mixin] cfxCreate$applyControllerInput
                                    │
                                    ▼
                   CreateTrainControl.isHeld(...)          (pure logic, unit tested)
                          │                    │
        player.input impulses          ControlFlex action state
        (forward/left impulse)         (jump / sneak)
```

### Components

- **`CreateTrainControl`** (common): the six console input slots plus their mapping rules. Pure logic, no Minecraft dependency, so the rules are unit tested without launching the game.
- **`ControlsUtilMixin`** (common): `@Inject` at `RETURN` of `isActuallyPressed`, `cancellable`, `remap = false` (Create is third-party code, so no mapping is needed). Resolves the console slot by object identity, reads only the signal source that slot needs, and ORs the answer in via `setReturnValue(true)`.
- **`CfxCreatePlugin`** (common): implements `IControlFlexPlugin` and is registered through `META-INF/services`. Intentionally inert — it installs no compat JSON, no guide assets and pushes no player state, which keeps the bridge unable to affect anything outside the console check.
- **`CfxCreateMod`** (one per loader): `@Mod` / `ClientModInitializer` entry; logs that `ControlsUtil` is present so a mis-install is visible in the log. A signature change cannot be detected from here; the mixin config's `required: true` fails loudly at load time instead.

## Input mapping

| Console slot | Create key | controls index | Signal source |
|--------------|------------|----------------|---------------|
| Throttle forward | `keyUp` | 0 | `forwardImpulse >= +0.3` |
| Reverse | `keyDown` | 1 | `forwardImpulse <= -0.3` |
| Steer left | `keyLeft` | 2 | `leftImpulse >= +0.3` |
| Steer right | `keyRight` | 3 | `leftImpulse <= -0.3` |
| Horn | `keyJump` | 4 | ControlFlex `jump` action |
| Brake | `keyShift` | 5 | ControlFlex `sneak` action |

The index order is Create's own `ControlsUtil.getControls()` order, which is what the console ships to the server as key indices.

### Why movement reads the player's impulses, not the raw stick

`LocalPlayer.input.forwardImpulse/leftImpulse` is a tick-level value that has already been through ControlFlex's analog policy (including the `KEYBOARD_LIKE` quantization) and response curve, and it is **the same value the player's own movement uses**. Reading it makes it impossible for "the player is moving" and "the console sees a key" to disagree. `DualInput` derives its own `up`/`down`/`left`/`right` booleans from exactly these fields.

The API's `IControllerState.getLeftStickX/Y()` would instead mix render-frame interpolation with the tick snapshot the movement path uses, so a boundary case could move the player without moving the train.

### Why horn and brake read action state instead

Horn and brake have no stick semantics; the player binds them to controller buttons, so their authoritative source is ControlFlex's action state.

They cannot go through the movement path for a second reason: in analog mode the MOVE actions are removed from `BindingMapper`'s candidate list before they ever reach `ActionStateTracker` (`BindingMapper.java:1139`), so `isGameActionActive("move_forward")` is permanently `false` under analog movement. There is no action to query for the directions — the impulse *is* ControlFlex's only representation of "the player is moving forward".

`CreateTrainControl.isMovement()` records this split so the mixin queries only the source each slot actually needs, keeping action-state lookups off the per-tick path for movement.

### Why slots are identified by object identity

`cfxCreate$resolve` compares `kb` against `mc.options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift`.

The alternative — reading the key code out of the `KeyMapping` — does not compile on both loaders from one source set. Under Forge the bound key is reached via `getKey()`; under Fabric's official mappings that member does not exist (Create Fabric itself has to go through `KeyBindingHelper.getBoundKeyOf`). The `mc.options.key*` fields exist under the same name on both, so identity comparison keeps a single shared mixin and, as a bonus, keeps working when the player rebinds the console keys.

### Sign convention and threshold

Matches vanilla `Input`: `forwardImpulse > 0` is forward, `leftImpulse > 0` is left.

The threshold mirrors ControlFlex's `Thresholds.STICK_THRESHOLD` (0.3), which is a **superset** of the 0.15 input dead zone. Reading above the dead zone means the console can never report "not pressed" while the player is genuinely moving — the failure would be the reverse of the bug being fixed, and just as bad.

## Boundaries

| Situation | Behaviour |
|-----------|-----------|
| Real key already held | Original result returned untouched (keyboard priority) |
| ControlFlex missing / not ready | `ControlFlexApi.isAvailable()` is false → original result |
| Player or `player.input` not ready | Original result |
| `KeyMapping` is not one of the six console slots | Original result (no intervention) |
| Analog movement disabled | No impulses are produced by the analog path, so the console falls back to the digital path, which already works |
| Create not installed | Loader metadata requires it; the plugin is also never asked to load |

No global state is written: no `GlfwPollKeyState`, no `KeyMapping` writes, no Forge/NeoForge key events. `InputConstants.isKeyDown` therefore behaves exactly as before for every other caller — which matters, because ControlFlex's own virtual-shift path shares that method.

Known timing detail: `ControlsHandler.tick()` runs on the client tick START event while `player.input.tick()` (which recomputes the impulses) runs later in the same tick, so the console reads the previous tick's final impulse. That is a one-tick (≈50 ms) lag, and it is consistent — the train always tracks what the player actually did.

## Alternatives considered and rejected

| Approach | Why not |
|----------|---------|
| Publish synthetic GLFW poll state for the movement keys from ControlFlex's analog path | Global: once written, any caller of `InputConstants.isKeyDown` sees it, including ControlFlex's own virtual-shift path. Not containable to Create. |
| Detect pollers and publish only while someone polls movement keys | The flag latches on for the whole session as soon as any mod polls W/A/S/D while walking, so containment fails anyway — and it still cannot restrict who reads the published state. |
| Write `KeyMapping.isDown()` for movement keys | Breaks analog movement: `DualInput` prefers `baseInput.forwardImpulse` when non-zero, so the digital value would override the analog one and movement would degrade to full-speed-or-nothing. |
| Reflection into `ControlsHandler.getContraption()` to gate on driving state | Hard dependency on Create's private static implementation details, which silently rots on Create updates. Unnecessary: the injection point is only reached while driving. |
| Ask Create to accept `KeyMapping.isDown()` | Would fix every controller mod at once, but depends on an upstream change and Create deliberately manages `KeyMapping` state around the console. Out of this repo's control. |

## Dependencies

- **ControlFlex API 0.8.8**: JitPack (`com.github.ControlFlexMC:control-flex-api:0.8.8`), `compileOnly`. Plain Java, loader-agnostic.
  The **runtime** floor is deliberately looser — `controlflex [0.8.7,)` — because the bridge only calls `isAvailable()`, `getActionStateProvider()` and `isGameActionActive()`, all present since 0.8.5. A `[0.8.8,)` floor would also reject 0.8.8 release candidates: Maven orders `0.8.8-rc.1` **below** `0.8.8`.
- **Create (Forge)**: `com.simibubi.create:create-1.20.1:6.0.8-291:slim` from `maven.createmod.net`, `compileOnly`. The `slim` classifier is used so no transitive dependencies are pulled.
- **Create Fabric**: fetched as a plain compile-only jar (Modrinth CDN). It must not go through Loom's mod dependency resolution: that fork is built with Loom 1.10 (Gradle 9, Java 25) and Loom 1.6 refuses to configure the project for it (`Mod was built with a newer version of Loom`), which would take the Forge subproject down with it during configuration.
- **Runtime metadata**: `controlflex [0.8.7,)` / `create [6.0.7,)` on both loaders, both required and CLIENT.

## Build

- `forge/`: `net.neoforged.moddev.legacyforge` 2.0.141, `mixin { add sourceSets.main, 'cfx_compat_create.refmap.json' }`, `MixinConfigs` manifest attribute, `reobfJar`.
  The refmap is **empty by design**: the target `isActuallyPressed` is Create's own method and the `mc.options.key*` fields are not remapped in 1.20.1 (verified against the SRG mappings — `getKey` is absent from them entirely, only `isDown`/`setDown` are present).
- `fabric/`: `fabric-loom` 1.6-SNAPSHOT (same as ControlFlex) + official Mojang mappings + `loom.mixin.defaultRefmapName`.
- Both subprojects pin a Java 17 compile toolchain. The root `gradle.properties` pins `org.gradle.java.home` to a JDK 21 because Gradle 8.8 cannot run on a JDK newer than 22; remove that line on CI, where `setup-java` provides the JDK.
- `processResources` expands placeholders in `mods.toml` / `fabric.mod.json` / `mixins.json` / `pack.mcmeta`.

## Verification

- `./gradlew :forge:build :fabric:build` produces both JARs, with `:forge:test` and `:fabric:test` running 15 tests each.
- The mapping rules are pure logic in `CreateTrainControlTest`: thresholds and both dead-zone edges, both signs on both axes, diagonals, key isolation (movement must ignore action state, actions must ignore impulses), and the `isMovement()` signal-source classification.
- JAR content checks: mixin config, refmap, `IControlFlexPlugin` service file, `MixinConfigs` manifest attribute, expanded metadata placeholders.
- Runtime: client log shows `Create train-console input bridge active (ControlsUtil present)`; pushing the left stick at a train console moves the train (manual test, confirmed on Forge 1.20.1).
