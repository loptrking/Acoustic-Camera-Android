# M2R2 宽带 GCC-PHAT 旋转扫描测试

本轮目标是验证双麦克风宽带时延能否随手机方位角形成稳定且可重复的曲线。不要再使用 4 kHz 单频作为本轮声源。

## 文件

- APK：`release/AcousticLens-v0.3.1-gcc-phat.apk`
- 测试音：`doc/test_noise_500Hz-8kHz_30s_-12dBFS.wav`

测试音是固定随机种子的 500 Hz–8 kHz 带限高斯噪声，48 kHz、单声道、30 秒，峰值 −12 dBFS。声音类似持续的“沙沙声”，属于正常现象。

## 环境与握持

1. 使用另一台设备或扬声器播放测试音，放在手机前方约 1 米。
2. 选择相对安静、反射较少的位置，扬声器不要紧贴墙角。
3. 手机保持竖屏，手指不要遮住顶部、底部或摄像头附近的麦克风孔。
4. 尽量围绕手机自身中心旋转，不要左右平移手臂。
5. 播放音量从中等开始，App 电平有响应即可，不要追求很响。

## 30 秒扫描动作

1. 点击开始采集，在正前方静止约 3 秒。
2. 用约 5 秒缓慢向左转 30°。
3. 用约 8 秒从左侧转到右侧 30°。
4. 用约 8 秒从右侧返回左侧 30°。
5. 回到正前方，静止约 3 秒并停止。

整个过程至少形成一次左—右—左往返。界面中的 `GCC 时延` 应尽量连续变化；`峰值比` 越大表示主时延峰相对其他候选峰越明确。

## 导出与分析

将 ZIP 放入 `result` 文件夹，然后运行：

```powershell
python .\python\analyze_rotation_scan_session.py .\result\AcousticLens_IMU_*.zip --json
```

重点查看 `gcc_phat_orientation`：

- `median_peak_ratio`：初步希望大于 1.2；
- `pose_model_r_squared`：初步希望大于 0.5；
- `pose_model_residual_rms_samples`：初步希望小于 2 samples；
- `round_trip_bin_count`：应大于 0，说明同一角度存在去程和回程数据；
- `round_trip_median_difference_samples`：初步希望小于 2 samples；
- `fitted_effective_baseline_cm`：只在模型拟合良好时解释，合理候选范围约 3–20 cm。

这些阈值是原型筛选标准，不是最终测量指标。若峰值比或重复性不达标，需要进一步检查房间多径、系统音频处理和麦克风路由。
