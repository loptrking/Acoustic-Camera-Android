"""Estimate camera-to-microphone directional calibration from a front-source sweep."""

from __future__ import annotations

import argparse
import csv
import io
import json
from pathlib import Path

import numpy as np

from analyze_rotation_scan_session import orientation_heading, read_optional_text
from session_io import load_session


def wrap_degrees(value: float) -> float:
    return (value + 180.0) % 360.0 - 180.0


def circular_distance_degrees(first: float, second: float) -> float:
    return abs(wrap_degrees(first - second))


def circular_mean_degrees(values: list[float], weights: list[float]) -> float:
    radians = np.deg2rad(values)
    vector = np.sum(np.asarray(weights) * np.exp(1j * radians))
    return wrap_degrees(float(np.rad2deg(np.angle(vector))))


def physical_cluster(results: list[dict[str, float]]) -> list[dict[str, float]]:
    """Cluster bands only when both direction and effective baseline agree."""
    if not results:
        return []

    def neighbors(candidate: dict[str, float]) -> list[dict[str, float]]:
        output = []
        for other in results:
            minimum = max(min(candidate["effective_baseline_cm"], other["effective_baseline_cm"]), 0.01)
            baseline_ratio = max(candidate["effective_baseline_cm"], other["effective_baseline_cm"]) / minimum
            if (
                circular_distance_degrees(
                    candidate["fit_direction_deg"], other["fit_direction_deg"]
                )
                <= 25.0
                and baseline_ratio <= 1.75
            ):
                output.append(other)
        return output

    center = max(results, key=lambda candidate: sum(item["score"] for item in neighbors(candidate)))
    return neighbors(center)


def fit_band(
    heading_radians: np.ndarray,
    phase: np.ndarray,
    frequency_hz: float,
) -> dict[str, float]:
    best_score = -1.0
    best_baseline = 0.0
    best_direction = 0.0
    best_offset = 0.0
    for baseline in np.linspace(0.0, 0.20, 161):
        amplitude = 2.0 * np.pi * frequency_hz * baseline / 343.0
        for direction in np.linspace(-np.pi, np.pi, 241):
            predicted = amplitude * np.cos(heading_radians - direction)
            mean_residual = np.mean(np.exp(1j * (phase - predicted)))
            score = float(abs(mean_residual))
            if score > best_score:
                best_score = score
                best_baseline = float(baseline)
                best_direction = float(direction)
                best_offset = float(np.angle(mean_residual))
    amplitude = 2.0 * np.pi * frequency_hz * best_baseline / 343.0
    residual = np.angle(
        np.exp(
            1j
            * (
                phase
                - best_offset
                - amplitude * np.cos(heading_radians - best_direction)
            )
        )
    )
    return {
        "frequency_hz": frequency_hz,
        "score": best_score,
        "residual_rms_deg": float(np.sqrt(np.mean(residual**2)) * 180.0 / np.pi),
        "effective_baseline_cm": best_baseline * 100.0,
        "fit_direction_deg": wrap_degrees(float(np.rad2deg(best_direction))),
        "phase_offset_rad": best_offset,
        "sample_count": int(len(phase)),
    }


def calibrate(source: Path) -> dict[str, object]:
    session = load_session(source)
    feature_text = read_optional_text(source, "acoustic_features.csv")
    orientation_text = read_optional_text(source, "orientation.csv")
    if feature_text is None:
        raise ValueError("acoustic_features.csv is missing; use app version 0.4.0 or later")
    if orientation_text is None:
        raise ValueError("orientation.csv is missing")
    features = list(csv.DictReader(io.StringIO(feature_text)))
    orientations = list(csv.DictReader(io.StringIO(orientation_text)))
    if len(features) < 8 or len(orientations) < 8:
        raise ValueError("Not enough acoustic feature or orientation rows")

    clock_points = [
        (int(row["audio_frame_position"]), int(row["audio_monotonic_ns"]))
        for row in session.timestamps
        if row.get("audio_frame_position") and row.get("audio_monotonic_ns")
    ]
    if len(clock_points) < 2:
        raise ValueError("Not enough valid AudioTimestamp rows")
    clock_frames = np.asarray([point[0] for point in clock_points], dtype=np.float64)
    clock_ns = np.asarray([point[1] for point in clock_points], dtype=np.float64)
    slope_ns_per_frame, intercept_ns = np.polyfit(clock_frames, clock_ns, 1)

    feature_frames = np.asarray([int(row["center_frame"]) for row in features], dtype=np.float64)
    feature_times = intercept_ns + slope_ns_per_frame * feature_frames
    orientation_times = np.asarray(
        [int(row["callback_monotonic_ns"]) for row in orientations], dtype=np.float64
    )
    heading = np.unwrap(
        np.deg2rad(np.asarray([orientation_heading(row) for row in orientations]))
    )
    overlap = (feature_times >= orientation_times[0]) & (feature_times <= orientation_times[-1])
    if int(np.sum(overlap)) < 8:
        raise ValueError("Audio features and orientation clocks do not overlap")
    aligned_heading = np.interp(feature_times, orientation_times, heading)
    initial_mask = orientation_times <= orientation_times[0] + 2_000_000_000
    initial_heading_deg = wrap_degrees(
        float(np.rad2deg(np.median(heading[initial_mask])))
    )
    heading_span_deg = float(np.rad2deg(np.max(heading) - np.min(heading)))

    requested_bands = [550, 700, 850, 1000, 1150]
    band_results: list[dict[str, float]] = []
    for requested in requested_bands:
        frequency_key = f"phase_{requested}_frequency_hz"
        phase_key = f"phase_{requested}_rad"
        magnitude_key = f"cross_{requested}"
        if frequency_key not in features[0] or phase_key not in features[0]:
            continue
        phases = np.asarray([
            float(row[phase_key]) if row.get(phase_key) else np.nan for row in features
        ])
        magnitudes = np.asarray([
            float(row[magnitude_key]) if row.get(magnitude_key) else 0.0 for row in features
        ])
        frequency_values = [
            float(row[frequency_key]) for row in features if row.get(frequency_key)
        ]
        if not frequency_values:
            continue
        selected = overlap & np.isfinite(phases)
        if np.any(selected):
            selected &= magnitudes >= np.percentile(magnitudes[selected], 20)
        if int(np.sum(selected)) < 8:
            continue
        band_results.append(
            fit_band(
                aligned_heading[selected],
                phases[selected],
                float(np.median(frequency_values)),
            )
        )

    frequency_candidates: list[dict[str, object]] = []
    for result in band_results:
        axis_offset = wrap_degrees(initial_heading_deg - result["fit_direction_deg"])
        eligible = (
            heading_span_deg >= 60.0
            and result["score"] >= 0.98
            and result["residual_rms_deg"] <= 10.0
            and 2.0 <= result["effective_baseline_cm"] <= 18.0
        )
        result["camera_to_microphone_axis_offset_deg"] = axis_offset
        frequency_candidates.append({
            "frequency_hz": result["frequency_hz"],
            "status": "CANDIDATE" if eligible else "NOT_READY",
            "camera_to_microphone_axis_offset_deg": axis_offset,
            "effective_baseline_cm": result["effective_baseline_cm"],
            "median_phase_residual_deg": result["residual_rms_deg"],
            "score": result["score"],
        })

    primary = next(
        (
            candidate
            for candidate in frequency_candidates
            if abs(float(candidate["frequency_hz"]) - 550.78125) < 1.0
        ),
        None,
    )
    primary_ready = primary is not None and primary["status"] == "CANDIDATE"
    profile = None
    if primary is not None:
        profile = {
            "format_version": 3,
            "status": primary["status"],
            "frequency_hz": primary["frequency_hz"],
            "device_model": session.metadata.get("device_model"),
            "sample_rate_hz": session.sample_rate,
            "source_center_heading_deg": initial_heading_deg,
            "camera_to_microphone_axis_offset_deg": primary["camera_to_microphone_axis_offset_deg"],
            "effective_baseline_cm": primary["effective_baseline_cm"],
            "median_phase_residual_deg": primary["median_phase_residual_deg"],
            "score": primary["score"],
            "heading_source": (
                "camera_heading_deg" if orientations[0].get("camera_heading_deg") else "yaw_deg fallback"
            ),
        }
    return {
        "source": str(source),
        "format_version": 3,
        "status": "CANDIDATE" if primary_ready else "NOT_READY",
        "certification_mode": "per_frequency_cross_session",
        "primary_frequency_hz": 550.78125,
        "heading_span_deg": heading_span_deg,
        "initial_center_heading_deg": initial_heading_deg,
        "band_results": band_results,
        "frequency_candidates": frequency_candidates,
        "calibration_profile": profile,
        "acceptance": {
            "minimum_heading_span_deg": 60.0,
            "baseline_range_cm": [2.0, 18.0],
            "minimum_score": 0.98,
            "maximum_residual_deg": 10.0,
            "cross_session_maximum_axis_difference_deg": 10.0,
            "cross_session_maximum_baseline_relative_difference": 0.30,
            "note": "Each frequency is certified independently across two sessions",
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = args.session.expanduser().resolve()
    report = calibrate(source)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    print(text, end="")
    if args.output:
        args.output.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
