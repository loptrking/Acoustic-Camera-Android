# 021 M3 真双麦录音路径确认

日期：2026-09-26

## 隐私边界

本轮经用户明确许可播放电脑测试音并使用手机麦克风。诊断仅计算汇总特征，`raw_audio_saved` 为 `false`，没有保存或导出原始环境录音；手机侧只操作 AcousticLens 包及其私有诊断文件。

## 测试条件

- 设备：HONOR PTP-AN10
- 手机背部正对电脑扬声器，距离约 20 cm。
- 电脑播放项目内 `test_noise_500Hz-8kHz_30s_-12dBFS.wav`。
- 每次手机采集约 8.02 秒，48 kHz、双通道 PCM。

## VOICE_RECOGNITION 结果

- Android 只报告 `bottom` 麦克风处于活动状态。
- `AudioRecord` 虽返回两个声道，但并非 `bottom + back` 两颗独立物理麦克风。
- 左右 RMS：0.00562 / 0.00312。
- 相关系数：0.196。
- GCC 中位时延：5.30 samples，峰值比 1.108。

结论：旧版使用的 `VOICE_RECOGNITION` 路径属于系统处理后的伪双声道，不适合依据物理麦克风基线进行 TDOA/相位定位。这是此前频率校准反复变化的重要根因。

## CAMCORDER 结果

- Android 同时报告 `bottom` 与 `back` 两颗物理麦克风处于活动状态。
- 左右 RMS：0.00716 / 0.00953；背部朝向声源时第二通道能量约为第一通道的 1.33 倍。
- 相关系数：0.532。
- GCC 中位时延：2.87 samples，峰值比 1.087。

结论：`CAMCORDER` 是本机能够同时启用两颗物理内置麦克风的正确录音源。两路仍由厂商音频链处理，但至少具备真实双麦输入基础。

## 代码修改

- 正式采集源从 `VOICE_RECOGNITION` 改为 `CAMCORDER`。
- 每次会话的 `metadata.json` 格式升级为 3，并写入 `active_microphones`、物理地址、组内索引和通道映射。
- 应用版本升级为 0.9.0（versionCode 13）。
- 构建、单元测试与 lint 全部通过。

## 尚待确认

需要再做一次“充电口所在短边正对扬声器、约 20 cm”的 CAMCORDER 测试，与背部朝向结果对比，最终确认两个 PCM 声道和 `bottom`/`back` 的对应关系。

