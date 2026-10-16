# AcousticLens 音频测试指南

本目录中的两个参考音用于检查手机录音链路的幅度稳定性、频率准确性和跨时间相位连续性：

- test_tone_1kHz_30s_-12dBFS.wav
- test_tone_4kHz_30s_-12dBFS.wav

两者均为 48 kHz、单声道、16-bit PCM，时长 30 秒，峰值电平 −12 dBFS。开头与结尾各有 0.25 秒淡入淡出，避免播放器启停产生强瞬态；中间部分是相位连续的稳定正弦信号。

## 播放设备

使用另一台手机、电脑或音箱播放测试音。不要使用正在运行 AcousticLens 的同一部手机播放，因为 Android 的扬声器处理、回声消除和内部耦合会改变实验条件。

关闭播放器的均衡器、音效、空间音频和响度归一化。音量先设为约 30%，录制一次后根据检查结果调整。扬声器与测试手机相距约 1 m，环境尽量安静。

## 实验 A：固定手机检查

1. 手机与扬声器保持不动。
2. 先在 AcousticLens 中开始录音。
3. 播放 1 kHz 文件，完整播放 30 秒。
4. 停止录音并导出 ZIP。
5. 对 4 kHz 文件重复以上步骤。

固定手机实验用于检查频率峰值、幅度随时间的稳定性、削波、采样时钟和相位连续性。录制时不要触碰手机或遮挡麦克风。

## 实验 B：缓慢扫描

固定手机实验通过后，再播放 4 kHz 文件并录音。保持手机朝向不变，用约 10 秒沿水平方向缓慢移动 30 cm，然后保持不动直至录音结束。该数据将用于后续位姿接入后的合成孔径验证。

## session 自动检查

在项目的 python 目录安装依赖后执行：

    python -m pip install -r requirements.txt
    python analyze_session.py ..\result\AcousticLens_xxx.zip

也可以输出 JSON 和 Markdown 报告：

    python analyze_session.py session.zip --json report.json --markdown report.md

如果输出中削波比例明显上升，应降低扬声器音量。如果 1 kHz 或 4 kHz 主峰不稳定、相位频繁跳变，需先分析手机音频处理，再进入移动扫描实验。
