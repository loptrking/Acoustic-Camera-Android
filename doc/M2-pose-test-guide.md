# M2 相机与 ARCore 位姿测试指南

本阶段 APK：

    app/build/outputs/apk/m2/app-m2.apk

版本为 0.2.0，使用与前一版相同的包名和 Debug 签名，可以覆盖安装。

## 安装后首次启动

1. 授予相机权限。
2. 如果系统要求安装或更新 Google Play Services for AR，完成安装后返回 AcousticLens。
3. 将手机摄像头对准纹理清楚、光照充足的环境。
4. 缓慢移动手机，等待状态变为“追踪正常”。

ARCore 是可选能力。设备不支持 ARCore 时，App 仍可录音，但不会产生有效空间位姿。

## 第一次位姿录制

第一次只验证轨迹，不播放测试音：

1. 等待“追踪正常”。
2. 点击“开始同步采集”。
3. 保持手机朝向基本不变，在约 10 秒内沿水平方向缓慢移动 20–30 cm。
4. 观察孔径进度、位姿数量和右下角轨迹。
5. 点击“停止并保存会话”。
6. 导出 ZIP。

ZIP 应包含：

    audio.wav
    audio_timestamps.csv
    pose.csv
    metadata.json

pose.csv 保存 ARCore 帧时间、Android Camera 时间、System.nanoTime 回调时间、三维位置、四元数和追踪状态。

## 自动检查

将 ZIP 放入 result 文件夹后执行：

    python analyze_session.py ..\result\session.zip
    python analyze_pose_session.py ..\result\session.zip

位姿检查会报告追踪比例、帧率、时间戳连续性、扫描孔径、路径长度、速度和可能的掉帧。首次扫描建议满足：

- TRACKING 比例至少 80%
- 孔径至少 15 cm
- 95% 移动速度不高于 0.25 m/s
- 位姿时间戳无倒退

## 第二次联合实验

位姿检查通过后，再用另一台设备播放 4 kHz 测试音并重复 20–30 cm 水平扫描。该 session 将用于音频帧到位姿时间轴的插值验证。
