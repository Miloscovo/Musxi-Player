# Shared 音频保真验证

本次保留 FFmpeg + libswresample + WASAPI Shared，不新增生产依赖。

## 链路与边界

- 解码直接转换到 Shared 混音采样率和声道布局，公共 PCM 保持交错 Float32。
- 同率且无需混音时使用 libswresample 自带的直接格式转换，不强制 SRC。
- 仅异率 SRC 使用 128 长度 Kaiser 核及内部 Double 平面精度，最终输出一次 Float32。
- 仅输入声道数大于输出时设置 `rematrix_maxval=1`，固定归一化下混矩阵。
  这可能降低下混音量；没有全曲衰减、自动增益或 Limiter。
- 浮点输出保留有限超满幅样本，不加入 Dither；NaN/Inf 清理为零。
- 整数混音格式优先查询同采样率、同声道布局的 Float32 Shared 输入；仅接受 `S_OK`。
  不采用 `S_FALSE` 的近似格式。Float32 初始化被拒绝时重新激活客户端并回退原混音格式。
- 必须输出整数时，根据真实输入格式判断是否发生量化，最终加入一次 TPDF 并舍入、饱和、按有效位左对齐。
  同率、同布局的已知 16/24 位整数源在不降低有效位时不加 Dither。
- `exactIntegerBits` 仅为解码器与输出线程之间的精度信息，不改变 CEF/React 桥契约。

Float32 可精确表示归一化 16/24 位整数，但不能完整保留任意 32 位整数源。
Shared 后续的 Windows 音量、系统混音、APO 和硬件行为不在应用样本一致性的保证范围内。
矩阵归一化不能保证重采样过冲或原本超满幅的浮点源永不削波。

## 2026-10-14 测量

独立正弦源幅度为 0.5，舍弃首尾各 512 输出帧，按理想采样时刻计算 RMS 误差。
阻带输入的期望输出为零。下表为单一测试信号，不代表所有频率或设备。

| 测试 | 原默认 SWR | 64 核 + Double | 128 核 + Double |
| --- | ---: | ---: | ---: |
| 48→44.1 kHz，23 kHz 阻带残留 | −28.96 dBFS | −61.94 dBFS | −103.70 dBFS |
| 48→44.1 kHz，20 kHz RMS 误差 | −26.29 dBFS | −49.28 dBFS | −101.91 dBFS |
| 44.1→48 kHz，20 kHz RMS 误差 | −31.12 dBFS | −98.02 dBFS | −104.05 dBFS |

- 同率 16/24 位随机 PCM、极值和 Float32 有限样本：严格一致。
- 5.1/7.1 逐声道脉冲：位置正确；5.1 back→side 不误标为布局未变化。
- 高相关、各声道幅度 0.75 的 5.1→双声道：峰值由 1.81066 降为 0.75。
- 低电平正弦的 TPDF 最终量化误差：方差约 0.251 LSB²，与输入的相关系数约 0.0044。
- 本机 128 核 192→48 kHz、8 声道处理：2 秒音频约耗时 0.071 秒，约 28 倍实时。
  该耗时包含解码和测试收集，不是跨机器性能承诺。
- 真实 48 kHz、8 声道 Shared 设备通过暂停、恢复、切换、缓冲和各格式尾部排空测试。

## 重复验证

在项目根目录使用 PowerShell 7，并使用本项目已配置的 CMake：

```powershell
cmake --build build/cef-msvc --config Release --target audio-fidelity audio-decoder audio-output
ctest --test-dir build/cef-msvc -C Release -R '^(audio-fidelity|audio-fixtures|audio-decoder)$' --output-on-failure
# 以下测试会以 10% 流音量播放测试音频。
./build/cef-msvc/Release/audio-output.exe ./build/cef-msvc/audio-fixtures
```

`audio-fidelity` 自行生成测试 WAV，不依赖额外编码器。它验证严格旁路、整数打包、Dither 跨块连续性和统计、独立 SRC 指标及下混。
`audio-decoder` 保留八种格式、连续性、尾部与跳转回归；其 CLI 参考同步到新 SRC 参数，不能替代独立 SRC 测量。

未验证硬件端数字捕获及真实整数混音设备的 Float32 协商回退。整数打包与量化通过纯 PCM 测试验证。
本项目不据此宣称 Shared 严格 Bit-perfect，也不承诺这些数值改善必然可听。
