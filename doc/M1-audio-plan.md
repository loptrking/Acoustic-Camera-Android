# M1 音频采集说明

当前实现支持 48 kHz、单声道、16-bit PCM 录音。录制期间显示由实际音频块 RMS 驱动的电平；停止后保存 WAV、逐块时间戳 CSV 和 JSON 元数据。最近一次会话可从 App 导出为 ZIP。

## 使用

1. 在真机打开 AcousticLens，授予麦克风权限。
2. 点击“开始录音”，录制 10–30 秒，观察录音计时和电平。
3. 点击“停止并保存录音”，再点击“导出上一次采集”。
4. 检查 ZIP 中的 audio.wav、audio_timestamps.csv、metadata.json。

## 时间戳含义

first_frame 是当前 WAV 音频块中第一个样本的零基索引。read_end_monotonic_ns 是该块读取完成时的 System.nanoTime()，只能作为软件读取时间。audio_frame_position 和 audio_monotonic_ns 是 Android 音频管线给出的帧位置与单调时钟时间。某些设备或录制初期可能拿不到后两项，此时留空，不用读取完成时间冒充采样时间。

## 设备限制

如果设备声明支持未处理音频，使用 UNPROCESSED；否则使用 VOICE_RECOGNITION。这两种选择都不能证明设备完全没有处理音频。AGC、降噪和回声消除状态在元数据中标为未知，后续要通过设备检查和实验确认。

现阶段是音频模块；CameraX、ARCore 位姿和热图会沿现有页面结构逐步加入。
