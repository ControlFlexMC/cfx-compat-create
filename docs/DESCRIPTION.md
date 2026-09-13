# Control Flex × Create

A compatibility bridge that lets you **drive Create's trains with a controller**, using the same left stick that moves your character.

---

## What This Does

Without this mod, Create's train console **cannot see controller input at all**.

Create's train console does not ask "is the W key mapped down?" the way most mods do. It polls the **raw key state** directly:

```
ControlsHandler.tick() -> ControlsUtil.isActuallyPressed(kb)
                       -> AllKeys.isKeyDown(code) -> InputConstants.isKeyDown(window, code)
```

ControlFlex's **analog movement** takes over the movement stick and stops publishing key state for it entirely — the stick value goes straight into the player's movement instead. Your character walks normally, but the console sees no key press, so the train never receives a throttle input.

**The result: keyboard `W` drives the train, the left stick does not.**

This bridge merges ControlFlex's controller input into the single check Create's console makes.

**The result:** push the left stick forward and the train pulls away; steer with the same stick; brake and sound the horn with your bound buttons.

---

## Features

- 🚂 **Left stick throttle** — push forward to accelerate, pull back to reverse.
- ↔️ **Left stick steering** — the same stick steers left and right, and diagonals work.
- 🛑 **Brake** — ControlFlex's sneak action maps to Create's brake key.
- 📯 **Horn** — ControlFlex's jump action maps to Create's horn key.
- ⌨️ **Keyboard still wins** — if you are already holding the real key, the mod leaves it alone.
- 🎛️ **Respects your setup** — the stick follows ControlFlex's analog movement switches and response curve, and keeps working if you rebind Create's console keys.
- 🧩 **No side effects** — it writes no global key state, so no other mod's input handling changes.
- 🛡️ **Safe without ControlFlex** — if ControlFlex is missing or not ready, the bridge no-ops instead of crashing.

---

## Dependencies (Required)

| Mod | Version |
|-----|---------|
| [ControlFlex](https://www.curseforge.com/minecraft/mc-mods/controlflex) | ≥ 0.8.7 |
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | ≥ 6.0.9 on Minecraft 1.21.1 (NeoForge) |

---

## Compatibility

- ✅ Works in singleplayer and multiplayer (client-side only).
- Requires ControlFlex's **analog movement** to be enabled — that is the mode this bridge exists to fix.
- This bridge does **one thing**: the train console. It does not add key configs, in-game guides, or extra Create features. It does not affect Create's other controls.

---

## How It Works

```
Create's train console asks "is this key held?"
                ↓
        cfx-compat-create (mixin on that one check)
                ↓
   ControlFlex's already-computed movement input
                ↓
        Console sees a throttle / steering input
```

The mod merges controller input into `ControlsUtil.isActuallyPressed` — the single method Create's console uses to ask that question. Movement comes from the player's own movement values, so "you are moving" and "the console sees a key" can never disagree. Brake and horn come from ControlFlex's action state.

Nothing global is written: no synthetic key state, no key-mapping changes. Every other mod continues to see exactly the input it saw before.

---

## Notes

- This mod is **client-side only** — it does not need to be installed on servers.
- If trains still will not move, check that ControlFlex has detected your controller and that analog movement is enabled.
- Install the JAR that matches your loader and Minecraft version (Forge / Fabric / NeoForge).
