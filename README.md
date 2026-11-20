# RM9HP4 Cam/Lock Fix（`camfix`）

基于红魔 9S Pro / HyperOS 4 的单模块合并实现：相机启动加速 + 锁屏相机/快捷功能修复，libxposed API 102。
本目录是从 `camboost`（相机加速）与 `FixLockScreenCamera`（锁屏修复）合并而来。
本项目基于酷安@施政 NX769J_HyperOS_4.0.0.10_A17_10.12.zip底包修复，感谢大佬的刷机包！
本项目产物由于需要使用xposed并hook system_server，因此开源并供大伙安全审计用途，目前不具备维护价值；但有PR也会看，感谢支持！

## 开关

应用界面只有四个开关 + 底部项目说明，切换后弹「重启生效」提示。

| 分组 | 开关 | `Settings.Global` 键 | 默认 |
|---|---|---|---|
| 相机类 | 修复急速相机 | `camfix_quick_camera` | 开 |
| 相机类 | 修复锁屏相机 | `camfix_lock_camera` | 开 |
| 相机类 | 相机启动加速 | `camfix_boost` | 开 |
| 锁屏类 | 常用功能免密 | `camfix_unlock_shortcuts` | 关 |

生效范围要说清楚：开关写入 `Settings.Global` 后，已注册 `ContentObserver` 的进程会在毫秒级更新内存值，
**下一次判定**就会用新值；但已经做出的判定不会回滚（例如已经渲染完的列表页要重新进入才变）。
界面上的「重启生效」是保守提示——它保证所有进程都从头按新值跑一遍。

## 关键设计约束（重要）

**hook 回调里不允许任何跨进程调用。**

早期版本在 `ActivityRecord#canShowWhenLocked`、`ProcessCleanerBase#killOnce`、
`WindowProcessUtils#removeTaskLocked`、`ContentProviderHelper#getContentProviderImpl` 中读取过
自定义 ContentProvider：这些回调运行在持有 WindowManager / ActivityManager 锁的栈上，跨进程读会
去争同一把锁，实测触发 `system_server_watchdog` 并导致 system_server 重启。

现在的做法：

- 开关值存放在 `Settings.Global`（应用首次启动只写入**尚不存在**的键，清数据重装不会覆盖已有设置）；
- 每个进程由 `FixSettings` 起一条线程做首次读取 + 注册 `ContentObserver`；
- hook 回调只读 `volatile boolean`，零 IPC、零等待；
- `ProcessList` 相关的 uid 判断也在安装时解析一次缓存，不在锁内查询 PackageManager。

## 各功能实现

### 相机启动加速（system_server）

1. 解锁后单次预热：`getContentProviderExternal("com.android.camera.cardprovider", ...)`，把 RomFix
   约 3.4–5 s 的注入移到后台付掉。
2. 抗 LMK 清扫：预热成功后保持该 provider 连接 90 s。AMS 会把相机算作前台级（实测 `adj=0`），
   因此躲过解锁后 30–40 s 的那次批量清扫（同批杀掉 48 个进程时相机 pid 不变）。窗口内进程空闲，
   实测 30 s CPU 零增长；窗口结束释放连接，约 3–4 s 后回到冻结态。
   窗口期间若在界面上关闭「相机启动加速」，连接会**立即释放**，不必等满 90 s。
3. 墓碑冻结：`/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze`，`adj ≥ 200` 持续 3 s 冻结，
   `adj < 200` 或收到 provider 请求解冻。进程缺席时 1/2/5/10 s 指数退避；包被 stop/disable 时跳过扫描。
4. 清理守卫：`handleSwipeKill`（划卡）、`removeTaskLocked`（一键清理的任务移除）、
   `killOnce` / `killApplicationLock`（一键清理的杀进程）、`getProcessPolicyWhiteList`（补白名单），
   另对 `forceStopPackage` / `handleKillAll` / `handleKillAny` 做只记录不拦截的观测。
5. 遥测：`freeze / unfreeze / fail / killed / scanSkipped` 计数，10 分钟一条汇总。
   冻结/解冻日志会带上**决策时的 adj 与原因**，例如
   `freeze=false pid=11732 adj=0 reason=adj<200`、`freeze=false … reason=provider-request`、
   `freeze=true pid=11732 adj=410 reason=dwell`；解冻后重读 /proc 得到的值不再参与打印，
   避免出现"解冻了但 adj 显示 910"的误导。

### 锁屏修复

- system_server：`canShowWhenLocked` 按开关放行相机与快捷应用（白名单不含米家）。
- `com.miui.aod`：`DataUtils#fetchShortcutFromApp` / `getShortcutDataFromApplication` 供给相机条目，
  `ShortcutListRecyclerAdapter` 构造时兜底补一条。
  **条目的 `packageName` / `iconId` / `nameId` / `tag` 必须与系统白名单里的原生相机条目一致**
  （`com.android.camera`、`@2131232426`、`@2131755173`、`CAMERA_CAPTURE`），否则会在选择页的合并阶段被丢掉。
- `com.android.systemui`：右槽图标按 0.66 缩放为 AOD 相机图标；拦截 `Settings$Secure.putString`
  防止包变更后配置被清成空壳。图标替换所需的"右槽配置串"由后台线程读一次并注册
  `ContentObserver` 镜像到内存，渲染路径只读内存；镜像未就绪时允许**每进程一次**直读兜底，
  仍未知则按"不替换图标"处理（与读失败一致）。
- `com.miui.notes`：开关开启时把 `KeyguardManager#isKeyguardLocked` 伪装为 false。

## 构建

完全离线，`toolchain/` 自带所需：

```
toolchain/jre                Java 17 运行时
toolchain/ecj.jar            Eclipse Compiler 3.33（需读 libxposed API 的 Java 17 字节码）
toolchain/libxposed-api.jar  libxposed API 102 编译期 jar
```

`toolchain/` 体积约 200 MB，不进仓库（见 `.gitignore`）。如果要自己构建，按上面三个路径补齐即可：
任意 JDK/JRE 17（如 Adoptium）、Eclipse Compiler 3.33 的 `ecj.jar`、libxposed API 102 的编译期 jar。

```bat
build.cmd
```

产物 `build/camfix.apk`。`build.cmd` 里只有 `SDK` 是机器相关路径。

编译约束：目标字节码是 Java 8（`-8`），但**没有指定 `-bootclasspath`**（ECJ 以 JRE 17 的平台类为准），
所以只能使用 `android.jar` 里真实存在的 API。`minSdk` 是 29，像 `List.of` / `Set.of`（API 30+）、
`String.isBlank`（API 33+）这类接口不要用，否则在低版本上运行期会崩。新增 API 前先确认它在 29 上可用。
集合初始化一律用 `Collections.unmodifiableSet(new HashSet<>(Arrays.asList(...)))` 这种形式，
不要用 `Set.of` / `List.of`（API 30+）。

## 安装

```bash
adb install --no-incremental -r build/camfix.apk
adb shell pm grant com.defsw0rd.rm9hp4camfix android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n com.defsw0rd.rm9hp4camfix/.MainActivity
adb shell reboot
```

作用域**内置在 APK 里**：`module.prop` 为 `staticScope=true`，`assets/META-INF/xposed/scope.list`
列出了 `system`、`com.miui.aod`、`com.android.systemui`、`com.miui.notes`。
这意味着 `lspctl scope set/add/remove` 对它是**无效的**（LSPosed 以 APK 内的列表为准）；
要调整作用域只能改 `scope.list` 后重新构建安装。
重新安装 APK 后要在 LSPosed 里确认模块仍为 enabled。

## 验证记录

```
11:22:45  prewarm delegated to hold window
11:22:50  hold window: provider acquired holder=true warmup=4574ms   ← RomFix 注入成本
11:22:55  hold pid=11728 adj=0（整个窗口 adj=0）
11:24:20  hold window closed, connection released, pidChanges=0
11:24:23  freeze=true pid=11728 adj=410

11-18（M2 + 冻结日志原因）
11:33:58  systemui: camera icon rescaled on the right slot
11:34:15  hold window: provider acquired holder=true warmup=5597ms
11:35:45  hold window closed, connection released, pidChanges=0
11:35:48  freeze=true pid=11732 adj=410 reason=dwell
```

`warmup=` 就是原来单独测的"预热耗时"：现在由 hold window 的这次阻塞式 provider 获取一次性完成，
不再有"先获取再释放、然后再获取"的重复往返。

- 锁屏快捷方式选择页：开 = 出现「相机」；关 = 消失；点击可选中并写回系统配置。
- 修复后不再产生 `system_server_watchdog` / `SYSTEM_RESTART` 记录。

## 已停用的观测与一次真实事故

### 2026-11-16：`system_server_watchdog` 复位

成因（dropbox 栈证据）：

```
"camfix-freeze" tid=40 Blocked
  at ContentProviderHelper.getContentProviderImpl
  at CameraBoost.lambda$0(CameraBoost.java:100)      ← provider 解冻 hook
  at FixSettings.refreshIfStale → FixSettings.boost
  at FreezeController.tick   - locked <FreezeController>

另一线程：
  at FixSettings.quickCamera → CamFixModule.isAllowed
  at WindowState.canShowWhenLocked                    ← WM 锁持有者
```

即"在持锁的 hook 里查自己的 ContentProvider"造成锁序反转。修复方式见上文「关键设计约束」：
开关改为 `Settings.Global` + 每进程 observer 线程 + `volatile` 镜像，钩子里零 IPC；
`removeTaskLocked` 的 uid 判断改为安装时解析一次缓存。

### `handleKillAll` / `handleKillAny` 观测（代码中已注释，未安装）

原本记录一行：

```
clear-all observed (handleKillAll|handleKillAny): ProcessConfig{...} cameraRunning=<pid>
```

`ProcessConfig` 会带出 `mPolicy`、`mReason`、`mWhiteList`、`mWhiteListTaskId`、
`mRemovingTaskIdList`、`mKillingPackage`。它的价值是：清理发生但**没有拦到东西**时仍有记录，
以及 ROM 改清理策略时的早期信号。

停用原因：`cameraPid()` 缓存失效时会遍历 `/proc` 全表（约 20 ms），而这两个方法在 AMS 侧持锁路径上。

重新启用时注意：把 `cameraPid()` 换成只读缓存 pid 的零 I/O 版本（或布尔"是否已跟踪"）。
真正的拦截不受影响，仍在 `handleSwipeKill`、`removeTaskLocked`、`killOnce`/`killApplicationLock`、
`getProcessPolicyWhiteList` 四处，且各有自己的日志。

## 已知限制

- 锁屏图标依赖 HyperOS 4 的两个资源 id（`0x7f08048f` AOD 相机图标、`0x7f0b055d` SystemUI 右槽视图），
  ROM 升级后若图标异常先查这里。
- 相机入口 Activity 若变动，需要同步改 `CAMERA_SHORTCUT_JSON` 里的 `targetClass`。
- 用户 force-stop 相机（stopped 状态）时不会自动重新预热。
