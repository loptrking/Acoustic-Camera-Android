"""Check stereo independence and IMU coverage for a rotation-scan session."""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import zipfile
from pathlib import Path

import numpy as np

from session_io import load_session


def orientation_heading(row: dict[str, str]) -> float:
    camera_heading = row.get("camera_heading_deg")
    return float(camera_heading) if camera_heading not in (None, "") else float(row["yaw_deg"])


def orientation_span(row: dict[str, str]) -> float:
    camera_span = row.get("camera_heading_span_deg")
    return float(camera_span) if camera_span not in (None, "") else float(row["yaw_span_deg"])


def read_optional_text(source: Path, name: str) -> str | None:
    if source.is_dir():
        path = source / name
        return path.read_text(encoding="utf-8-sig") if path.exists() else None
    with zipfile.ZipFile(source) as archive:
        matches = [entry for entry in archive.namelist() if Path(entry).name == name]
        if not matches:
            return None
        if len(matches) != 1:
            raise ValueError(f"Expected at most one {name!r}, found {len(matches)}")
        return archive.read(matches[0]).decode("utf-8-sig")


def stereo_windows(samples: np.ndarray, sample_rate: int) -> dict[str, object]:
    if samples.ndim != 2 or samples.shape[1] != 2:
        return {"available": False, "reason": "WAV is not stereo"}
    window = max(1024, int(sample_rate * 0.085))
    hop = window
    max_lag = 24
    lags: list[int] = []
    correlations: list[float] = []
    differences: list[float] = []
    for start in range(0, samples.shape[0] - window + 1, hop):
        block = samples[start : start + window].astype(np.float64)
        block -= np.mean(block, axis=0, keepdims=True)
        energy = float(np.sum(block * block))
        if energy < 1.0:
            continue
        differences.append(float(np.sqrt(2.0 * np.sum((block[:, 0] - block[:, 1]) ** 2) / energy)))
        best_lag = 0
        best_corr = -1.0
        for lag in range(-max_lag, max_lag + 1):
            if lag < 0:
                left, right = block[-lag:, 0], block[:lag, 1]
            elif lag > 0:
                left, right = block[:-lag, 0], block[lag:, 1]
            else:
                left, right = block[:, 0], block[:, 1]
            denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
            correlation = float(np.dot(left, right) / denominator) if denominator else 0.0
            if correlation > best_corr:
                best_lag, best_corr = lag, correlation
        lags.append(best_lag)
        correlations.append(best_corr)
    if not lags:
        return {"available": True, "reason": "No non-silent analysis windows"}
    lag_array = np.asarray(lags)
    difference_array = np.asarray(differences)
    unique_lags, counts = np.unique(lag_array, return_counts=True)
    dominant_lag = int(unique_lags[np.argmax(counts)])
    duplicate_suspected = bool(
        np.median(difference_array) < 0.005
        and dominant_lag == 0
        and np.max(np.abs(lag_array)) == 0
    )
    return {
        "available": True,
        "window_count": len(lags),
        "dominant_lag_samples": dominant_lag,
        "lag_min_samples": int(np.min(lag_array)),
        "lag_max_samples": int(np.max(lag_array)),
        "median_peak_correlation": float(np.median(correlations)),
        "median_channel_difference_ratio": float(np.median(difference_array)),
        "duplicate_channels_suspected": duplicate_suspected,
    }


def orientation_summary(text: str | None) -> dict[str, object]:
    if text is None:
        return {"available": False, "reason": "orientation.csv is missing"}
    rows = list(csv.DictReader(io.StringIO(text)))
    if not rows:
        return {"available": False, "reason": "orientation.csv has no rows"}
    timestamps = np.asarray([int(row["sensor_timestamp_ns"]) for row in rows], dtype=np.int64)
    callback_times = np.asarray([int(row["callback_monotonic_ns"]) for row in rows], dtype=np.int64)
    yaw_values = np.asarray([orientation_heading(row) for row in rows])
    initial = callback_times <= callback_times[0] + 2_000_000_000
    yaw_spans = np.asarray([orientation_span(row) for row in rows])
    callback_offsets = np.asarray(
        [int(row["callback_monotonic_ns"]) - int(row["sensor_timestamp_ns"]) for row in rows],
        dtype=np.int64,
    )
    intervals = np.diff(timestamps) / 1e9
    return {
        "available": True,
        "row_count": len(rows),
        "duration_s": float((timestamps[-1] - timestamps[0]) / 1e9),
        "median_rate_hz": float(1.0 / np.median(intervals)) if intervals.size else None,
        "yaw_span_deg": float(np.max(yaw_spans)),
        "yaw_start_deg": float(yaw_values[0]),
        "yaw_end_deg": float(yaw_values[-1]),
        "yaw_initial_2s_median_deg": float(np.median(yaw_values[initial])),
        "sensor_callback_offset_span_ms": float(np.ptp(callback_offsets) / 1e6),
        "sensor_callback_offset_deviation_p95_ms": float(
            np.percentile(np.abs(callback_offsets - np.median(callback_offsets)), 95) / 1e6
        ),
        "clock_note": (
            "SensorEvent.timestamp and System.nanoTime may use BOOTTIME and MONOTONIC; "
            "absolute offset is not interpreted. Audio alignment uses callback_monotonic_ns."
        ),
    }


def phase_orientation_coupling(
    samples: np.ndarray,
    sample_rate: int,
    timestamps: list[dict[str, str]],
    orientation_text: str | None,
) -> dict[str, object]:
    if samples.ndim != 2 or samples.shape[1] != 2:
        return {"available": False, "reason": "WAV is not stereo"}
    if orientation_text is None:
        return {"available": False, "reason": "orientation.csv is missing"}
    orientation_rows = list(csv.DictReader(io.StringIO(orientation_text)))
    audio_clock = [
        (int(row["audio_frame_position"]), int(row["audio_monotonic_ns"]))
        for row in timestamps
        if row.get("audio_frame_position") and row.get("audio_monotonic_ns")
    ]
    if len(audio_clock) < 2 or len(orientation_rows) < 2:
        return {"available": False, "reason": "Not enough clock samples"}

    clock_frames = np.asarray([item[0] for item in audio_clock], dtype=np.float64)
    clock_ns = np.asarray([item[1] for item in audio_clock], dtype=np.float64)
    slope_ns_per_frame, intercept_ns = np.polyfit(clock_frames, clock_ns, 1)
    clock_residual_ms = (clock_ns - (slope_ns_per_frame * clock_frames + intercept_ns)) / 1e6

    analysis_count = min(samples.shape[0], 262_144)
    mono = np.mean(samples[:analysis_count].astype(np.float64), axis=1)
    mono -= np.mean(mono)
    windowed = mono * np.hanning(analysis_count)
    spectrum = np.abs(np.fft.rfft(windowed))
    frequencies = np.fft.rfftfreq(analysis_count, 1.0 / sample_rate)
    search = (frequencies >= 300.0) & (frequencies <= 8_000.0)
    tone_frequency = float(frequencies[search][np.argmax(spectrum[search])])

    window = 4096
    hop = 2048
    local_time = np.arange(window, dtype=np.float64) / sample_rate
    reference = np.exp(-2j * np.pi * tone_frequency * local_time) * np.hanning(window)
    centers: list[int] = []
    phases: list[float] = []
    amplitudes: list[float] = []
    for start in range(0, samples.shape[0] - window + 1, hop):
        block = samples[start : start + window].astype(np.float64)
        block -= np.mean(block, axis=0, keepdims=True)
        left = np.dot(block[:, 0], reference)
        right = np.dot(block[:, 1], reference)
        centers.append(start + window // 2)
        phases.append(float(np.angle(right * np.conj(left))))
        amplitudes.append(float(abs(left) + abs(right)))
    if not centers:
        return {"available": False, "reason": "Recording is too short"}

    centers_array = np.asarray(centers, dtype=np.float64)
    # AudioTimestamp framePosition and WAV frame zero share the AudioRecord stream origin.
    audio_times_ns = intercept_ns + slope_ns_per_frame * centers_array
    orientation_times_ns = np.asarray(
        [int(row["callback_monotonic_ns"]) for row in orientation_rows], dtype=np.float64
    )
    yaw = np.asarray([orientation_heading(row) for row in orientation_rows], dtype=np.float64)
    yaw_unwrapped = np.unwrap(np.deg2rad(yaw))
    overlap = (audio_times_ns >= orientation_times_ns[0]) & (audio_times_ns <= orientation_times_ns[-1])
    amplitude_array = np.asarray(amplitudes)
    if np.any(overlap):
        overlap &= amplitude_array >= np.percentile(amplitude_array[overlap], 20)
    if int(np.sum(overlap)) < 8:
        return {"available": False, "reason": "Audio and orientation clocks do not overlap"}

    aligned_yaw_rad = np.interp(audio_times_ns[overlap], orientation_times_ns, yaw_unwrapped)
    aligned_yaw_deg = np.rad2deg(aligned_yaw_rad)
    phase_wrapped = np.asarray(phases)[overlap]
    phase_unwrapped = np.unwrap(phase_wrapped)
    design = np.column_stack(
        [np.ones_like(aligned_yaw_rad), np.cos(aligned_yaw_rad), np.sin(aligned_yaw_rad)]
    )
    coefficients, _, _, _ = np.linalg.lstsq(design, phase_unwrapped, rcond=None)
    predicted = design @ coefficients
    residual = phase_unwrapped - predicted
    total = phase_unwrapped - np.mean(phase_unwrapped)
    r_squared = 1.0 - float(np.sum(residual**2) / np.sum(total**2)) if np.sum(total**2) else 0.0
    pearson = float(np.corrcoef(aligned_yaw_deg, phase_unwrapped)[0, 1])
    phase_span = float(np.max(phase_unwrapped) - np.min(phase_unwrapped))
    equivalent_lag_span = phase_span / (2.0 * np.pi * tone_frequency) * sample_rate

    best_score = -1.0
    best_amplitude = 0.0
    best_direction = 0.0
    best_offset = 0.0
    for amplitude in np.linspace(0.0, 15.0, 151):
        for direction in np.linspace(-np.pi, np.pi, 181):
            predicted_without_offset = amplitude * np.cos(aligned_yaw_rad - direction)
            residual_unit = np.exp(1j * (phase_wrapped - predicted_without_offset))
            mean_residual = np.mean(residual_unit)
            score = float(abs(mean_residual))
            if score > best_score:
                best_score = score
                best_amplitude = float(amplitude)
                best_direction = float(direction)
                best_offset = float(np.angle(mean_residual))
    circular_residual = np.angle(
        np.exp(
            1j
            * (
                phase_wrapped
                - best_offset
                - best_amplitude * np.cos(aligned_yaw_rad - best_direction)
            )
        )
    )
    effective_baseline_m = best_amplitude * 343.0 / (2.0 * np.pi * tone_frequency)
    return {
        "available": True,
        "tone_frequency_hz": tone_frequency,
        "aligned_window_count": int(np.sum(overlap)),
        "yaw_min_deg": float(np.min(aligned_yaw_deg)),
        "yaw_max_deg": float(np.max(aligned_yaw_deg)),
        "unwrapped_phase_span_rad": phase_span,
        "equivalent_lag_span_samples": float(equivalent_lag_span),
        "phase_yaw_pearson": pearson,
        "sinusoidal_pose_model_r_squared": r_squared,
        "circular_pose_model_score": best_score,
        "circular_pose_residual_rms_deg": float(
            np.sqrt(np.mean(circular_residual**2)) * 180.0 / np.pi
        ),
        "fitted_effective_baseline_cm": float(effective_baseline_m * 100.0),
        "fitted_source_direction_deg": float(np.rad2deg(best_direction)),
        "audio_clock_ns_per_frame": float(slope_ns_per_frame),
        "audio_clock_estimated_rate_hz": float(1e9 / slope_ns_per_frame),
        "audio_clock_residual_p95_ms": float(np.percentile(np.abs(clock_residual_ms), 95)),
    }


def gcc_phat_window(
    block: np.ndarray,
    sample_rate: int,
    max_lag: int = 24,
    low_hz: float = 500.0,
    high_hz: float = 8_000.0,
) -> tuple[float, float]:
    block = block.astype(np.float64)
    block -= np.mean(block, axis=0, keepdims=True)
    window = np.hanning(block.shape[0])
    left = np.fft.rfft(block[:, 0] * window)
    right = np.fft.rfft(block[:, 1] * window)
    # R * conj(L): positive lag means the right channel arrives later.
    cross = right * np.conj(left)
    frequencies = np.fft.rfftfreq(block.shape[0], 1.0 / sample_rate)
    valid = (frequencies >= low_hz) & (frequencies <= high_hz)
    normalized = np.zeros_like(cross)
    magnitude = np.abs(cross)
    usable = valid & (magnitude > 1e-12)
    normalized[usable] = cross[usable] / magnitude[usable]
    correlation = np.fft.irfft(normalized, n=block.shape[0])
    lags = np.arange(-max_lag, max_lag + 1)
    values = np.abs(correlation[lags % block.shape[0]])
    peak_index = int(np.argmax(values))
    lag = float(lags[peak_index])
    if 0 < peak_index < len(values) - 1:
        before, center, after = values[peak_index - 1 : peak_index + 2]
        denominator = before - 2.0 * center + after
        if abs(denominator) > 1e-12:
            lag += float(np.clip(0.5 * (before - after) / denominator, -0.5, 0.5))
    excluded = np.ones(len(values), dtype=bool)
    excluded[max(0, peak_index - 2) : min(len(values), peak_index + 3)] = False
    second = float(np.max(values[excluded])) if np.any(excluded) else 1e-12
    return lag, float(values[peak_index] / max(second, 1e-12))


def gcc_orientation_coupling(
    samples: np.ndarray,
    sample_rate: int,
    timestamps: list[dict[str, str]],
    orientation_text: str | None,
) -> dict[str, object]:
    if samples.ndim != 2 or samples.shape[1] != 2:
        return {"available": False, "reason": "WAV is not stereo"}
    if orientation_text is None:
        return {"available": False, "reason": "orientation.csv is missing"}
    orientation_rows = list(csv.DictReader(io.StringIO(orientation_text)))
    audio_clock = [
        (int(row["audio_frame_position"]), int(row["audio_monotonic_ns"]))
        for row in timestamps
        if row.get("audio_frame_position") and row.get("audio_monotonic_ns")
    ]
    if len(audio_clock) < 2 or len(orientation_rows) < 2:
        return {"available": False, "reason": "Not enough clock samples"}
    clock_frames = np.asarray([item[0] for item in audio_clock], dtype=np.float64)
    clock_ns = np.asarray([item[1] for item in audio_clock], dtype=np.float64)
    slope_ns_per_frame, intercept_ns = np.polyfit(clock_frames, clock_ns, 1)

    window = 4096
    hop = 2048
    centers: list[int] = []
    lags: list[float] = []
    peak_ratios: list[float] = []
    energies: list[float] = []
    for start in range(0, samples.shape[0] - window + 1, hop):
        block = samples[start : start + window]
        lag, peak_ratio = gcc_phat_window(block, sample_rate)
        centers.append(start + window // 2)
        lags.append(lag)
        peak_ratios.append(peak_ratio)
        energies.append(float(np.mean(block.astype(np.float64) ** 2)))
    if not centers:
        return {"available": False, "reason": "Recording is too short"}

    audio_times_ns = intercept_ns + slope_ns_per_frame * np.asarray(centers, dtype=np.float64)
    orientation_times_ns = np.asarray(
        [int(row["callback_monotonic_ns"]) for row in orientation_rows], dtype=np.float64
    )
    yaw = np.unwrap(
        np.deg2rad(np.asarray([orientation_heading(row) for row in orientation_rows]))
    )
    overlap = (audio_times_ns >= orientation_times_ns[0]) & (audio_times_ns <= orientation_times_ns[-1])
    energy_array = np.asarray(energies)
    if np.any(overlap):
        overlap &= energy_array >= np.percentile(energy_array[overlap], 20)
    if int(np.sum(overlap)) < 8:
        return {"available": False, "reason": "Audio and orientation clocks do not overlap"}

    selected_times = audio_times_ns[overlap]
    aligned_yaw = np.interp(selected_times, orientation_times_ns, yaw)
    aligned_lags = np.asarray(lags)[overlap]
    selected_ratios = np.asarray(peak_ratios)[overlap]
    design = np.column_stack(
        [np.ones_like(aligned_yaw), np.cos(aligned_yaw), np.sin(aligned_yaw)]
    )
    coefficients, _, _, _ = np.linalg.lstsq(design, aligned_lags, rcond=None)
    predicted = design @ coefficients
    residual = aligned_lags - predicted
    total = aligned_lags - np.mean(aligned_lags)
    r_squared = 1.0 - float(np.sum(residual**2) / np.sum(total**2)) if np.sum(total**2) else 0.0
    amplitude_samples = float(np.hypot(coefficients[1], coefficients[2]))
    fitted_direction = float(np.rad2deg(np.arctan2(coefficients[2], coefficients[1])))
    effective_baseline_cm = amplitude_samples / sample_rate * 343.0 * 100.0

    yaw_speed = np.gradient(aligned_yaw, selected_times / 1e9)
    bin_ids = np.floor(np.rad2deg(aligned_yaw) / 5.0).astype(int)
    repeat_differences: list[float] = []
    for bin_id in np.unique(bin_ids):
        in_bin = bin_ids == bin_id
        forward = aligned_lags[in_bin & (yaw_speed > np.deg2rad(1.0))]
        reverse = aligned_lags[in_bin & (yaw_speed < -np.deg2rad(1.0))]
        if len(forward) >= 2 and len(reverse) >= 2:
            repeat_differences.append(float(abs(np.median(forward) - np.median(reverse))))
    return {
        "available": True,
        "aligned_window_count": int(np.sum(overlap)),
        "lag_min_samples": float(np.min(aligned_lags)),
        "lag_max_samples": float(np.max(aligned_lags)),
        "median_peak_ratio": float(np.median(selected_ratios)),
        "pose_model_r_squared": r_squared,
        "pose_model_residual_rms_samples": float(np.sqrt(np.mean(residual**2))),
        "fitted_effective_baseline_cm": effective_baseline_cm,
        "fitted_source_direction_deg": fitted_direction,
        "round_trip_bin_count": len(repeat_differences),
        "round_trip_median_difference_samples": (
            float(np.median(repeat_differences)) if repeat_differences else None
        ),
    }


def multiband_phase_orientation(
    samples: np.ndarray,
    sample_rate: int,
    timestamps: list[dict[str, str]],
    orientation_text: str | None,
) -> dict[str, object]:
    if samples.ndim != 2 or samples.shape[1] != 2 or orientation_text is None:
        return {"available": False, "reason": "Stereo audio or orientation is missing"}
    orientation_rows = list(csv.DictReader(io.StringIO(orientation_text)))
    audio_clock = [
        (int(row["audio_frame_position"]), int(row["audio_monotonic_ns"]))
        for row in timestamps
        if row.get("audio_frame_position") and row.get("audio_monotonic_ns")
    ]
    if len(audio_clock) < 2 or len(orientation_rows) < 2:
        return {"available": False, "reason": "Not enough clock samples"}
    clock_frames = np.asarray([item[0] for item in audio_clock], dtype=np.float64)
    clock_ns = np.asarray([item[1] for item in audio_clock], dtype=np.float64)
    slope_ns_per_frame, intercept_ns = np.polyfit(clock_frames, clock_ns, 1)

    window = 4096
    hop = 2048
    frequencies = np.fft.rfftfreq(window, 1.0 / sample_rate)
    targets = [600, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300]
    bins = [int(np.argmin(np.abs(frequencies - target))) for target in targets]
    actual_frequencies = [float(frequencies[index]) for index in bins]
    centers: list[int] = []
    phase_rows: list[list[float]] = []
    amplitude_rows: list[list[float]] = []
    window_function = np.hanning(window)
    for start in range(0, samples.shape[0] - window + 1, hop):
        block = samples[start : start + window].astype(np.float64)
        block -= np.mean(block, axis=0, keepdims=True)
        left = np.fft.rfft(block[:, 0] * window_function)
        right = np.fft.rfft(block[:, 1] * window_function)
        centers.append(start + window // 2)
        phase_rows.append([float(np.angle(right[index] * np.conj(left[index]))) for index in bins])
        amplitude_rows.append([float(abs(right[index]) + abs(left[index])) for index in bins])
    if not centers:
        return {"available": False, "reason": "Recording is too short"}
    audio_times_ns = intercept_ns + slope_ns_per_frame * np.asarray(centers, dtype=np.float64)
    orientation_times_ns = np.asarray(
        [int(row["callback_monotonic_ns"]) for row in orientation_rows], dtype=np.float64
    )
    yaw_unwrapped = np.unwrap(
        np.deg2rad(np.asarray([orientation_heading(row) for row in orientation_rows]))
    )
    base_overlap = (audio_times_ns >= orientation_times_ns[0]) & (
        audio_times_ns <= orientation_times_ns[-1]
    )
    aligned_yaw_all = np.interp(audio_times_ns, orientation_times_ns, yaw_unwrapped)
    phase_matrix = np.asarray(phase_rows)
    amplitude_matrix = np.asarray(amplitude_rows)
    results: list[dict[str, float]] = []
    baseline_grid = np.linspace(0.0, 0.20, 81)
    direction_grid = np.linspace(-np.pi, np.pi, 121)
    for column, frequency in enumerate(actual_frequencies):
        selected = base_overlap.copy()
        if np.any(selected):
            selected &= amplitude_matrix[:, column] >= np.percentile(
                amplitude_matrix[selected, column], 20
            )
        if int(np.sum(selected)) < 8:
            continue
        yaw = aligned_yaw_all[selected]
        phase = phase_matrix[selected, column]
        best_score = -1.0
        best_baseline = 0.0
        best_direction = 0.0
        best_offset = 0.0
        for baseline in baseline_grid:
            phase_amplitude = 2.0 * np.pi * frequency * baseline / 343.0
            for direction in direction_grid:
                predicted = phase_amplitude * np.cos(yaw - direction)
                mean_residual = np.mean(np.exp(1j * (phase - predicted)))
                score = float(abs(mean_residual))
                if score > best_score:
                    best_score = score
                    best_baseline = float(baseline)
                    best_direction = float(direction)
                    best_offset = float(np.angle(mean_residual))
        amplitude = 2.0 * np.pi * frequency * best_baseline / 343.0
        residual = np.angle(
            np.exp(1j * (phase - best_offset - amplitude * np.cos(yaw - best_direction)))
        )
        results.append(
            {
                "frequency_hz": frequency,
                "score": best_score,
                "residual_rms_deg": float(np.sqrt(np.mean(residual**2)) * 180.0 / np.pi),
                "effective_baseline_cm": best_baseline * 100.0,
                "source_direction_deg": float(np.rad2deg(best_direction)),
            }
        )
    good = [item for item in results if item["score"] >= 0.90 and item["residual_rms_deg"] <= 25.0]
    return {
        "available": True,
        "bands": results,
        "good_band_count": len(good),
        "good_band_frequency_min_hz": min((item["frequency_hz"] for item in good), default=None),
        "good_band_frequency_max_hz": max((item["frequency_hz"] for item in good), default=None),
        "good_band_baseline_median_cm": (
            float(np.median([item["effective_baseline_cm"] for item in good])) if good else None
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", help="Exported ZIP or extracted session directory")
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON")
    args = parser.parse_args()
    source = Path(args.session).expanduser().resolve()
    session = load_session(source)
    orientation_text = read_optional_text(source, "orientation.csv")
    report = {
        "source": str(source),
        "sample_rate": session.sample_rate,
        "channels": session.channels,
        "duration_s": float(session.samples.shape[0] / session.sample_rate),
        "stereo": stereo_windows(session.samples, session.sample_rate),
        "orientation": orientation_summary(orientation_text),
        "phase_orientation": phase_orientation_coupling(
            session.samples,
            session.sample_rate,
            session.timestamps,
            orientation_text,
        ),
        "gcc_phat_orientation": gcc_orientation_coupling(
            session.samples,
            session.sample_rate,
            session.timestamps,
            orientation_text,
        ),
        "multiband_phase_orientation": multiband_phase_orientation(
            session.samples,
            session.sample_rate,
            session.timestamps,
            orientation_text,
        ),
    }
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Session: {report['source']}")
        print(f"Audio: {report['sample_rate']} Hz, {report['channels']} channel(s), {report['duration_s']:.2f} s")
        print("Stereo:", json.dumps(report["stereo"], ensure_ascii=False))
        print("Orientation:", json.dumps(report["orientation"], ensure_ascii=False))
        print("Phase/orientation:", json.dumps(report["phase_orientation"], ensure_ascii=False))
        print("GCC-PHAT/orientation:", json.dumps(report["gcc_phat_orientation"], ensure_ascii=False))
        print(
            "Multiband phase/orientation:",
            json.dumps(report["multiband_phase_orientation"], ensure_ascii=False),
        )
    stereo = report["stereo"]
    orientation = report["orientation"]
    if not stereo.get("available") or not orientation.get("available"):
        return 2
    return 1 if stereo.get("duplicate_channels_suspected") else 0


if __name__ == "__main__":
    sys.exit(main())
