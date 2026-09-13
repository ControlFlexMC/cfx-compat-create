# cfx-compat-create

ControlFlex ↔ Create 兼容模组 —— 让 **Create 的列车驾驶台**响应手柄输入，玩家用模拟量移动开车时可以**用左摇杆驱动列车**。

[English](README.md)

## 分支

| 分支 | Minecraft | Loader |
|------|-----------|--------|
| `1.20.1`（当前） | 1.20.1 | Forge、Fabric |
| `1.21.1` | 1.21.1 | NeoForge |

## 问题所在

Create 的列车驾驶台**不读** `KeyMapping.isDown()`，它轮询的是原始按键状态：

```java
ControlsHandler.tick()
  → ControlsUtil.isActuallyPressed(kb)
    → AllKeys.isKeyDown(code) → InputConstants.isKeyDown(window, code)
```

而 ControlFlex 的**模拟量移动**通道会接管 MOVE 组：`BindingMapper` 把 MOVE 动作从候选列表中整组剔除（并在接管边沿释放已按下的键），因此 `ActionDispatcher.activateAction(MOVE_FORWARD, …)` 永不执行，摇杆值直接写进 `player.input.forwardImpulse`。

结果是**玩家走动正常，但没有任何按键状态被发布**：`GlfwPollKeyState` 收不到 `W`，`InputConstants.isKeyDown(window, W)` 恒为 `false`，列车永远收不到油门。

症状：**键盘 `W` 能开车，左摇杆不能。**

## 本模组做什么

| 功能 | 实现 |
|------|------|
| **油门 / 倒车** | 把玩家自身的 `forwardImpulse` 合并进 Create 的 `keyUp` / `keyDown` 判定 |
| **转向** | 把 `leftImpulse` 合并进 `keyLeft` / `keyRight` |
| **刹车 / 鸣笛** | ControlFlex 的 `sneak` 动作 → 刹车（`keyShift`），`jump` 动作 → 鸣笛（`keyJump`） |
| **键盘优先** | 真实按键已按下时，原结果原样返回，不做改动 |

## 工作原理

```
ControlsUtil.isActuallyPressed(kb)        ← Create 驾驶台发问
  → cfx-compat-create 的 Mixin
      → Minecraft.player.input（forwardImpulse / leftImpulse）
      → ControlFlexApi.getActionStateProvider()（jump / sneak）
        → 合并手柄输入，OR 进结果
```

整个集成只是 `ControlsUtil.isActuallyPressed(KeyMapping)` 上的一个 `RETURN` 处 `@Inject`。这是刻意的：

- **不写任何全局状态。** 不写合成 GLFW 轮询状态、不写 `KeyMapping`、不发 Forge 按键事件。对其他所有代码来说，`InputConstants.isKeyDown` 的行为与改动前完全一致。
- **不需要判断「玩家是否在驾驶」。** `isActuallyPressed` 只被 Create 驾驶台相关代码调用，因此「本方法被调用」本身就等于「玩家正坐在驾驶台上」。无需反射 Create 内部状态，也无需维护驾驶状态。
- **不新增 ControlFlex API。** 桥接读的是 `player.input.forwardImpulse` / `leftImpulse` —— 这正是 ControlFlex 已经算好、且**玩家自身移动所用的同一份 tick 级数值**，所以「玩家在动」与「驾驶台看到按键」不可能不一致。

## 依赖要求

- **ControlFlex** ≥ 0.8.7
- **Create** ≥ 6.0.7 —— 上游 [Forge 版](https://www.curseforge.com/minecraft/mc-mods/create) 或 [Fabric 分支](https://www.curseforge.com/minecraft/mc-mods/create-fabric)（两者 mod id 都是 `create`）
- 仅客户端。

## 安装

把对应 loader 的 JAR 与 ControlFlex、Create 一起放进 `mods/`。

## 构建

```bash
./tools/build-forge.sh          # MC 1.20.1 Forge   → forge/build/libs/
./tools/build-fabric.sh         # MC 1.20.1 Fabric  → fabric/build/libs/
# 或：./gradlew :forge:build :fabric:build
```

依赖来源：ControlFlex API 走 JitPack（`com.github.ControlFlexMC:control-flex-api:0.8.8`，纯 Java、与 loader 无关）；Create（Forge）来自 `maven.createmod.net`；Create Fabric 以普通 compile-only jar 方式获取（原因见 `fabric/build.gradle`）。

## 测试

```bash
./gradlew :forge:test
```

映射规则是 `common/.../CreateTrainControl.java` 里的纯逻辑，由 `CreateTrainControlTest` 覆盖（阈值、方向符号、死区、按键隔离），无需启动游戏即可验证其确切行为。
