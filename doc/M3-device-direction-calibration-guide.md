# M3 设备方向标定测试

本轮是开发者针对某一手机型号/音频路由执行的一次性实验室标定，用于测量“摄像头正前方”与手机内部有效双麦克风基线之间的固定方向偏置。它不是普通用户每次定位前的操作。标定参数验证后随设备配置保存；真实使用时用户面对未知声源直接扫描，不需要预先知道或对准声源。

## 文件

- APK：`release/AcousticLens-v0.4.0-direction-calibration.apk`
- 测试音：`doc/test_noise_500Hz-8kHz_30s_-12dBFS.wav`

## 环境

1. 使用固定扬声器播放带限噪声，距离手机约 1 米。
2. 扬声器不要紧贴墙角；手机顶部、底部和摄像头附近的麦克风孔不能被手遮挡。
3. 手机保持竖屏，整个实验中不要改变握持方式。
4. 开始录制时必须让扬声器位于相机准星正中央。这是本次标定的已知方向条件。

## 扫描动作

1. 声源位于准星中心，静止约 3 秒后开始录制。
2. 用约 6 秒向左转 40°。
3. 用约 10 秒从左 40°转到右 40°。
4. 用约 8 秒从右 40°返回正前方。
5. 正前方静止约 3 秒，停止并导出 ZIP。

尽量围绕手机自身中心旋转，不要平移手臂。界面底部显示的是由四元数计算的“镜头前向角”，不是旧版 Euler yaw。

## 导出内容

新版 ZIP 新增：

- `acoustic_features.csv`：每个音频块的 GCC-PHAT 和五个低频互谱相位；
- `orientation.csv`：新增摄像头前向角、前向水平分量和镜头角覆盖。

## 电脑标定

将 ZIP 放入 `result` 文件夹，运行：

```powershell
python .\python\calibrate_device_direction.py `
  .\result\AcousticLens_IMU_最新文件.zip `
  --output .\result\device_direction_calibration.json
```

通过条件：

- 至少两个频段进入同一方向簇；
- 镜头角覆盖至少 60°；
- 有效基线为 2–20 cm；
- 入选频段的相位残差中位数不超过 20°。

输出 `status=PASS` 后，才使用 `camera_to_microphone_axis_offset_deg` 和 `effective_baseline_cm` 进入左、中、右已知方向验证。
