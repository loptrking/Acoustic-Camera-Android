# 025 M4 宽带方向诊断探针

日期：2026-09-26  
版本：0.10.0  
状态：本地实现与构建完成，等待真机受控角度实验

## 本阶段目标

在实时界面之外增加一个可重复、可机器读取的 8 秒方向诊断实验，避免仅凭界面动画判断算法是否有效。

## 已完成工作

- 将 `BroadbandDirectionTracker` 接入 `MicRoutingDiagnosticActivity`。
- 诊断继续使用 `CAMCORDER` 双物理麦克风路由和 48 kHz 双声道 PCM。
- 每个音频块计算 GCC-PHAT 时延、峰值比、通道差异度和 RMS，再输入与主界面相同的稳健方向跟踪器。
- 仅把质量达到 `TRACKING` 的窗口纳入最终方向统计。
- 诊断 JSON 新增：
  - `median_tracked_direction_degrees`
  - `median_tracked_direction_confidence`
  - `direction_tracking_window_count`
  - `direction_quality_last`
  - `direction_degrees_last`
  - `max_physical_lag_samples`
- 诊断不保存原始音频，只保存聚合指标。

## 本地验证结果

- `:app:testDebugUnitTest`：通过。
- `:app:assembleM2`：通过。
- `:app:lintM2`：通过。
- 已生成 `AcousticLens-v0.10.0-broadband-live-doa.apk`。

## 下一实验

先在手机固定、扬声器位于后置摄像头正前方约 1 米的位置执行一次 8 秒宽带噪声实验。中心实验通过后，再依次验证屏幕右侧和左侧 30°，检查方向符号、角度单调性、跟踪窗口比例和置信度。

## 判定原则

中心实验不以“必须精确为 0°”作为唯一标准。第一轮重点确认：

1. 双物理麦克风仍被同时激活；
2. 有持续的 `TRACKING` 窗口，而非偶发单帧；
3. 中心角稳定且没有长时间贴住 ±90°；
4. 左右实验的符号相反，且绝对角度随偏转增加。

只有完成多角度验证后，才能认定单手机水平声源方向估计在该机型上有效；本地构建通过本身不等于算法目标已经实现。
