"""Validate an exported AcousticLens recording session."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np

from session_io import SessionData, load_session


def _safe_dbfs(value: float) -> float:
    return float(20.0 * np.log10(max(value, 1e-12) / 32768.0))


def _longest_true_run(mask: np.ndarray) -> int:
    edges = np.diff(np.r_[False, mask, False].astype(np.int8))
    starts = np.flatnonzero(edges == 1)
    ends = np.flatnonzero(edges == -1)
    return int(np.max(ends - starts)) if len(starts) else 0


def _spectrum_peaks(samples: np.ndarray, rate: int) -> list[float]:
    mono = samples if samples.ndim == 1 else samples[:, 0]
    size = min(65_536, 1 << (len(mono).bit_length() - 1))
    if size < 1024:
        return []
    powers = []
    for start in range(0, len(mono) - size + 1, size):
        block = mono[start : start + size].astype(np.float64)
        block = (block - block.mean()) * np.hanning(size)
        powers.append(np.abs(np.fft.rfft(block)) ** 2)
    average = np.mean(powers, axis=0)
    frequencies = np.fft.rfftfreq(size, 1.0 / rate)
    candidates = np.flatnonzero((frequencies >= 50.0) & (frequencies <= 20_000.0))
    count = min(8, len(candidates))
    strongest = candidates[np.argpartition(average[candidates], -count)[-count:]]
    strongest = strongest[np.argsort(average[strongest])[::-1]]
    return [round(float(frequencies[index]), 2) for index in strongest]


def _timestamp_metrics(session: SessionData) -> dict[str, Any]:
    rows = session.timestamps
    required = {
        "first_frame",
        "frame_count",
        "read_end_monotonic_ns",
        "audio_frame_position",
        "audio_monotonic_ns",
    }
    if not rows or not required.issubset(rows[0]):
        return {"rows": len(rows), "valid": False, "reason": "Missing required columns"}

    app_first = np.array([int(row["first_frame"]) for row in rows], dtype=np.int64)
    counts = np.array([int(row["frame_count"]) for row in rows], dtype=np.int64)
    read_time = np.array(
        [int(row["read_end_monotonic_ns"]) for row in rows], dtype=np.int64
    )
    valid = [
        index
        for index, row in enumerate(rows)
        if row["audio_frame_position"] and row["audio_monotonic_ns"]
    ]
    result: dict[str, Any] = {
        "rows": len(rows),
        "missing_audio_timestamps": len(rows) - len(valid),
        "app_frames_contiguous": bool(
            np.all(app_first[1:] == app_first[:-1] + counts[:-1])
        ),
        "recorded_frames_from_csv": int(np.sum(counts)),
        "read_interval_ms_median": round(float(np.median(np.diff(read_time)) / 1e6), 6),
        "read_interval_ms_min": round(float(np.min(np.diff(read_time)) / 1e6), 6),
        "read_interval_ms_max": round(float(np.max(np.diff(read_time)) / 1e6), 6),
    }
    if len(valid) < 2:
        result.update(valid=False, reason="Fewer than two hardware timestamps")
        return result

    selected = np.array(valid)
    audio_position = np.array(
        [int(rows[index]["audio_frame_position"]) for index in valid], dtype=np.int64
    )
    audio_time = np.array(
        [int(rows[index]["audio_monotonic_ns"]) for index in valid], dtype=np.int64
    )
    position_step = np.diff(audio_position)
    time_step = np.diff(audio_time)
    effective_rate = (
        (audio_position[-1] - audio_position[0])
        * 1e9
        / (audio_time[-1] - audio_time[0])
    )
    read_minus_audio = read_time[selected] - audio_time
    result.update(
        valid=True,
        audio_position_non_monotonic_steps=int(np.sum(position_step <= 0)),
        audio_time_non_monotonic_steps=int(np.sum(time_step <= 0)),
        audio_interval_ms_median=round(float(np.median(time_step) / 1e6), 6),
        audio_interval_ms_min=round(float(np.min(time_step) / 1e6), 6),
        audio_interval_ms_max=round(float(np.max(time_step) / 1e6), 6),
        effective_sample_rate_hz=round(float(effective_rate), 6),
        sample_rate_error_ppm=round(
            float((effective_rate / session.sample_rate - 1.0) * 1e6), 3
        ),
        read_minus_audio_ms_median=round(
            float(np.median(read_minus_audio) / 1e6), 6
        ),
        read_minus_audio_ms_min=round(float(np.min(read_minus_audio) / 1e6), 6),
        read_minus_audio_ms_max=round(float(np.max(read_minus_audio) / 1e6), 6),
    )
    return result


def analyze(session: SessionData) -> dict[str, Any]:
    samples = session.samples
    mono = samples if samples.ndim == 1 else samples[:, 0]
    numeric = mono.astype(np.float64)
    frame_count = len(mono)
    peak = float(np.max(np.abs(numeric))) if frame_count else 0.0
    rms = float(np.sqrt(np.mean(numeric * numeric))) if frame_count else 0.0
    dc = float(np.mean(numeric)) if frame_count else 0.0
    clipped = int(np.sum(np.abs(numeric) >= 32760))
    startup_zeros = 0
    for sample in mono:
        if sample != 0:
            break
        startup_zeros += 1

    timestamps = _timestamp_metrics(session)
    warnings: list[str] = []
    metadata_frames = session.metadata.get("audio_frame_count")
    if metadata_frames != frame_count:
        warnings.append("metadata audio_frame_count does not match WAV")
    if not timestamps.get("app_frames_contiguous", False):
        warnings.append("App frame sequence contains a gap or overlap")
    if timestamps.get("missing_audio_timestamps", 0):
        warnings.append("Some audio hardware timestamps are missing")
    if clipped / max(frame_count, 1) > 0.0001:
        warnings.append("More than 0.01% of samples are clipped")
    if startup_zeros / session.sample_rate > 0.1:
        warnings.append("Audio startup contains more than 100 ms of zeros")

    return {
        "source": str(session.source),
        "device": {
            "model": session.metadata.get("device_model"),
            "android_version": session.metadata.get("android_version"),
            "audio_source": session.metadata.get("audio_source"),
        },
        "wav": {
            "sample_rate_hz": session.sample_rate,
            "channels": session.channels,
            "sample_width_bits": session.sample_width * 8,
            "frame_count": frame_count,
            "duration_seconds": round(frame_count / session.sample_rate, 6),
        },
        "signal": {
            "peak_dbfs": round(_safe_dbfs(peak), 4),
            "rms_dbfs": round(_safe_dbfs(rms), 4),
            "dc_samples": round(dc, 6),
            "clipped_samples": clipped,
            "clipped_percent": round(clipped / max(frame_count, 1) * 100, 8),
            "startup_zero_samples": startup_zeros,
            "startup_zero_ms": round(startup_zeros / session.sample_rate * 1000, 6),
            "longest_zero_run_samples": _longest_true_run(mono == 0),
            "spectral_peaks_50_to_20000_hz": _spectrum_peaks(mono, session.sample_rate),
        },
        "timestamps": timestamps,
        "warnings": warnings,
        "passed": not warnings,
    }


def as_markdown(report: dict[str, Any]) -> str:
    wav = report["wav"]
    signal = report["signal"]
    timing = report["timestamps"]
    warnings = report["warnings"]
    lines = [
        "# AcousticLens session 检查报告",
        "",
        f"- 数据：{report['source']}",
        f"- 设备：{report['device']['model']}，Android {report['device']['android_version']}",
        f"- 音频源：{report['device']['audio_source']}",
        f"- WAV：{wav['sample_rate_hz']} Hz，{wav['channels']} 通道，"
        f"{wav['sample_width_bits']}-bit，{wav['duration_seconds']} s",
        f"- 电平：peak {signal['peak_dbfs']} dBFS，RMS {signal['rms_dbfs']} dBFS",
        f"- 削波：{signal['clipped_samples']} samples（{signal['clipped_percent']}%）",
        f"- 启动零值：{signal['startup_zero_ms']} ms",
        f"- 时间戳：{timing['rows']} 行，缺失 {timing.get('missing_audio_timestamps', 0)} 行",
        f"- 样本连续：{timing.get('app_frames_contiguous')}",
        f"- 实际采样率：{timing.get('effective_sample_rate_hz')} Hz",
        f"- 采样率误差：{timing.get('sample_rate_error_ppm')} ppm",
        "",
        "## 判定",
        "",
        "通过。" if report["passed"] else "存在需要处理的问题。",
    ]
    if warnings:
        lines.extend(["", "## 警告", ""])
        lines.extend(f"- {warning}" for warning in warnings)
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", help="Exported session ZIP or extracted directory")
    parser.add_argument("--json", type=Path, dest="json_output")
    parser.add_argument("--markdown", type=Path, dest="markdown_output")
    args = parser.parse_args()

    report = analyze(load_session(args.session))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.json_output:
        args.json_output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    if args.markdown_output:
        args.markdown_output.write_text(as_markdown(report), encoding="utf-8")


if __name__ == "__main__":
    main()
