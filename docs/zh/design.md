# 方案设计

## 目标

做一个 ControlFlex × Create 兼容模组，只有一个兼容点：**让 Create 的列车驾驶台看得见手柄输入**，从而能用模拟量移动摇杆开车。不包含任何其他功能 —— 无 compat JSON、无 guide、无按键绑定、无配置项。

## 修复的缺陷

Create 的列车驾驶台**不读** `KeyMapping.isDown()`，它轮询的是原始按键状态（`research/Create` 源码与实例中实际安装的 6.0.8 Forge jar 一致）：

```java
ControlsHandler.tick() -> ControlsUtil.isActuallyPressed(kb)
                       -> AllKeys.isKeyDown(code) -> InputConstants.isKeyDown(window, code)
```

ControlFlex 的模拟量移动通道接管 MOVE 组后，会**刻意停止**该组的数字派发：

- `BindingMapper.java:1139` 与 `:1724`：MOVE 组为模拟量时，把每个 MOVE 动作从候选列表剔除。
- `BindingMapper.java:211`：接管开始时释放此前已按下的移动键。
- `ControllerMovementInput.java:69`：摇杆值直接写入 `forwardImpulse`。

因此 `ActionDispatcher.activateAction(MOVE_FORWARD, …)` 永不执行 —— `KeyMapping` 不会被写、`GlfwPollKeyState` 收不到移动键、摇杆推动期间 `InputConstants.isKeyDown(window, W)` 恒为 `false`。

玩家走动正常（impulse 驱动了他），但驾驶台读到「无键按下」，于是 `ControlsInputPacket` 发出空键集，列车收不到油门。

**症状：键盘 `W` 能开车，左摇杆不能。**

## 平台

| 子项目 | MC | Loader | Create（编译） | ControlFlex |
|--------|----|--------|----------------|-------------|
| `forge` | 1.20.1 | Forge 47.4.4 | 6.0.8-291（上游，`:slim`） | 0.8.7+ |
| `fabric` | 1.20.1 | Fabric Loader 0.15.11 + Fabric API 0.92.2 | Create Fabric 6.0.8.1（分支版，普通 jar） | 0.8.7+ |

> 本分支（`1.20.1`）构建 1.20.1 的两个 loader；MC 1.21.1 版本（NeoForge）见 `1.21.1` 分支。

两个 loader 共享同一份与 loader 无关的源码（`common/src/main/java`）；平台差异仅在构建脚本与元数据（mods.toml / fabric.mod.json、mixins.json 的 `compatibilityLevel`、pack.mcmeta 的 `pack_format`）。

## 为什么注入点是 `ControlsUtil.isActuallyPressed`

`ControlsUtil.isActuallyPressed(KeyMapping)` 是驾驶台询问「这个键是否按住」的唯一方法。把控制器输入 OR 进它的**返回值**即可修好缺陷，且无需伪造任何全局按键状态。

这同时省掉了「玩家是否在驾驶」的判定。`isActuallyPressed` 只被 Create 的驾驶台代码调用：

- `ControlsHandler.tick()` —— 每 tick 的驾驶台轮询
- `ControlsHandler.stopControlling()` —— 退出时释放按键
- `TrainHUD` —— 在其 `getCarriage()` 驾驶守卫之后

所以「本方法被调用」本身就等价于「玩家正坐在驾驶台上」。无需反射 Create 内部状态，无需维护驾驶状态，也无需生命周期记账。

## 架构

```
Create 驾驶台（Mixin 目标）
  ControlsUtil.isActuallyPressed(KeyMapping) ──RETURN──> [Mixin] cfxCreate$applyControllerInput
                                    │
                                    ▼
                   CreateTrainControl.isHeld(...)          （纯逻辑，有单测）
                          │                    │
               玩家 input impulse       ControlFlex 动作状态
               （forward/left）          （jump / sneak）
```

### 组件

- **`CreateTrainControl`**（common）：驾驶台六个输入位及其映射规则。纯逻辑、无 Minecraft 依赖，因此规则无需启动游戏即可单测。
- **`ControlsUtilMixin`**（common）：在 `isActuallyPressed` 的 `RETURN` 处 `@Inject`，`cancellable`、`remap = false`（Create 是第三方类，无需映射）。按对象身份解析输入位，只读取该输入位真正需要的信号源，再经 `setReturnValue(true)` OR 进结果。
- **`CfxCreatePlugin`**（common）：实现 `IControlFlexPlugin`，经 `META-INF/services` 注册。刻意保持空实现 —— 不装 compat JSON、不装 guide、不推送任何玩家状态，使本桥接不可能影响驾驶台判定之外的任何行为。
- **`CfxCreateMod`**（每个 loader 一份）：`@Mod` / `ClientModInitializer` 入口；记录 `ControlsUtil` 是否存在，便于在日志中发现装错。签名变化无法在这里检出，由 mixin 配置的 `required: true` 在加载期直接报错。

## 输入映射

| 驾驶台输入位 | Create 按键 | controls 索引 | 信号来源 |
|--------------|-------------|---------------|----------|
| 前进油门 | `keyUp` | 0 | `forwardImpulse >= +0.3` |
| 后退 | `keyDown` | 1 | `forwardImpulse <= -0.3` |
| 左转 | `keyLeft` | 2 | `leftImpulse >= +0.3` |
| 右转 | `keyRight` | 3 | `leftImpulse <= -0.3` |
| 鸣笛 | `keyJump` | 4 | ControlFlex `jump` 动作 |
| 刹车 | `keyShift` | 5 | ControlFlex `sneak` 动作 |

索引顺序即 Create `ControlsUtil.getControls()` 的顺序，也正是驾驶台发给服务端的按键序号。

### 为什么方向键读玩家 impulse，而不是摇杆原始轴

`LocalPlayer.input.forwardImpulse/leftImpulse` 是 tick 级数值，已经过 ControlFlex 的模拟量策略（含 `KEYBOARD_LIKE` 量化）与响应曲线，并且**正是玩家自身移动所用的同一份值**。读它可保证「玩家在动」与「驾驶台看到按键」不可能不一致 —— `DualInput` 自己的 `up`/`down`/`left`/`right` 布尔也是从这两个字段派生的。

若改用 API 的 `IControllerState.getLeftStickX/Y()`，会把**渲染帧插值**与移动路径所用的 **tick 快照**混在一起，边界情况下可能出现「玩家动了、列车没动」。

### 为什么刹车/鸣笛读动作状态

刹车与鸣笛没有摇杆语义，由玩家绑到手柄按钮，因此其权威来源是 ControlFlex 的动作状态。

它们也无法走移动路径，还有第二个原因：模拟量模式下 MOVE 动作在到达 `ActionStateTracker` 之前就被 `BindingMapper` 从候选列表剔除了（`BindingMapper.java:1139`），所以 `isGameActionActive("move_forward")` 在模拟量移动下恒为 `false`。**方向根本没有可查的 Action** —— impulse 才是 ControlFlex 对「玩家正在前进」的唯一表述。

`CreateTrainControl.isMovement()` 记录了这一分工，使 mixin 只查询每个输入位真正需要的来源，让动作状态查询不出现在方向键的每 tick 路径上。

### 为什么用对象身份识别输入位

`cfxCreate$resolve` 把 `kb` 与 `mc.options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift` 做身份比较。

另一种做法 —— 从 `KeyMapping` 里取出绑定键码 —— 无法让同一份源码在两个 loader 上都编译：Forge 下取绑定键是 `getKey()`，而 Fabric 的官方 mappings 下该成员不存在（Create Fabric 自己都得走 `KeyBindingHelper.getBoundKeyOf`）。`mc.options.key*` 在两个 loader 下同名存在，因此身份比较既保住了单一共用 mixin，又顺带保证玩家改键后依然生效。

### 符号约定与阈值

与 vanilla `Input` 一致：`forwardImpulse > 0` 为前进，`leftImpulse > 0` 为向左。

阈值沿用 ControlFlex 的 `Thresholds.STICK_THRESHOLD`（0.3），它是输入死区（0.15）的**超集**。在死区之上取值，意味着「玩家确实在移动时驾驶台绝不报告未按下」—— 反向不一致是和原缺陷同样糟糕的失败。

## 边界

| 情形 | 行为 |
|------|------|
| 真实按键已按住 | 原结果原样返回（键盘优先） |
| ControlFlex 未安装 / 未就绪 | `ControlFlexApi.isAvailable()` 为 false → 原结果 |
| 玩家或 `player.input` 未就绪 | 原结果 |
| `KeyMapping` 不属于这六个输入位 | 原结果（不介入） |
| 模拟量移动被关闭 | 模拟量路径不产生 impulse，驾驶台自然回落到本就可用的数字路径 |
| Create 未安装 | loader 元数据已要求它；且插件也不会被加载 |

不写任何全局状态：不写 `GlfwPollKeyState`、不写 `KeyMapping`、不发 Forge/NeoForge 按键事件。因此 `InputConstants.isKeyDown` 对其他所有调用方的行为与改动前完全一致 —— 这一点很重要，因为 ControlFlex 自己的虚拟 Shift 路径共用该方法。

已知时序细节：`ControlsHandler.tick()` 挂在客户端 tick START 事件，而 `player.input.tick()`（重算 impulse）在同一 tick 稍后执行，因此驾驶台读到的是**上一 tick 的最终 impulse**。这是 1 tick（约 50ms）延迟，且是稳定的 —— 列车始终跟随玩家实际动作。

## 评估过但否决的方案

| 方案 | 否决原因 |
|------|----------|
| 由 ControlFlex 模拟量路径发布移动键的合成 GLFW 轮询状态 | **全局**：一旦写入，任何 `InputConstants.isKeyDown` 调用方都能读到，包括 ControlFlex 自己的虚拟 Shift 路径。无法收敛到 Create。 |
| 探测轮询方，仅在有人轮询移动键时发布 | 只要有任意模组在走路时轮询 W/A/S/D，标志就整局常亮，收敛同样失败；且依然无法限制「谁读得到」。 |
| 为移动键写 `KeyMapping.isDown()` | 会破坏模拟量移动：`DualInput` 在 `baseInput.forwardImpulse` 非零时优先取 base，数字值会覆盖模拟量，移动退化为「全速或不动」。 |
| 反射 `ControlsHandler.getContraption()` 以驾驶状态做闸门 | 对 Create 私有静态实现细节形成硬依赖，Create 升级后静默失效。且**没有必要** —— 注入点只在驾驶时才会被触达。 |
| 请 Create 接受 `KeyMapping.isDown()` | 能一次性修好所有手柄模组，但依赖上游改动，且 Create 是刻意在驾驶台周围管理 `KeyMapping` 状态的。不在本仓可控范围内。 |

## 依赖

- **ControlFlex API 0.8.8**：JitPack（`com.github.ControlFlexMC:control-flex-api:0.8.8`），`compileOnly`。纯 Java，与 loader 无关。
  **运行期**下限刻意更宽 —— `controlflex [0.8.7,)` —— 因为桥接只调用 `isAvailable()`、`getActionStateProvider()` 与 `isGameActionActive()`，这些自 0.8.5 就存在。若下限写 `[0.8.8,)`，还会拒绝 0.8.8 的预发布版：Maven 把 `0.8.8-rc.1` 排在 `0.8.8` **之前**。
- **Create（Forge）**：`com.simibubi.create:create-1.20.1:6.0.8-291:slim`，来自 `maven.createmod.net`，`compileOnly`。用 `slim` 分类器以避免拉入传递依赖。
- **Create Fabric**：以普通 compile-only jar 方式获取（Modrinth CDN）。**不能**走 Loom 的 mod 依赖解析：该分支版用 Loom 1.10（Gradle 9、Java 25）构建，Loom 1.6 会拒绝为其配置项目（`Mod was built with a newer version of Loom`），而这会**在配置阶段连带把 Forge 子项目一起拖挂**。
- **运行时元数据**：两个 loader 均声明 `controlflex [0.8.7,)`、`create [6.0.7,)`，均为 required、CLIENT。

## 构建

- `forge/`：`net.neoforged.moddev.legacyforge` 2.0.141，`mixin { add sourceSets.main, 'cfx_compat_create.refmap.json' }`，manifest 写 `MixinConfigs`，`reobfJar`。
  refmap **为空是正确结果**：目标 `isActuallyPressed` 是 Create 自己的方法，而 `mc.options.key*` 字段在 1.20.1 未被重映射（已对照 SRG 映射核实 —— 其中根本没有 `getKey`，只有 `isDown`/`setDown`）。
- `fabric/`：`fabric-loom` 1.6-SNAPSHOT（与 ControlFlex 一致）+ 官方 Mojang mappings + `loom.mixin.defaultRefmapName`。
- 两个子项目均固定 Java 17 编译 toolchain。根 `gradle.properties` 把 `org.gradle.java.home` 固定到 JDK 21，因为 Gradle 8.8 无法运行在高于 22 的 JDK 上；CI 上请删除该行，由 `setup-java` 提供 JDK。
- `processResources` 展开 `mods.toml` / `fabric.mod.json` / `mixins.json` / `pack.mcmeta` 中的占位符。

## 验证

- `./gradlew :forge:build :fabric:build` 产出两个 jar，且 `:forge:test`、`:fabric:test` 各跑 15 个测试。
- 映射规则是 `CreateTrainControlTest` 中的纯逻辑：阈值与死区两侧边界、两轴两个方向符号、对角线、按键隔离（方向键忽略动作状态、动作键忽略 impulse）、以及 `isMovement()` 的信号来源分类。
- jar 内容检查：mixin 配置、refmap、`IControlFlexPlugin` 服务文件、manifest `MixinConfigs`、元数据占位符已展开。
- 运行时：客户端日志出现 `Create train-console input bridge active (ControlsUtil present)`；在驾驶台推动左摇杆列车开动（手动测试，Forge 1.20.1 已确认）。
