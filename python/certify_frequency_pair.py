"""Compare two calibration sessions and certify the primary frequency independently."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from calibrate_device_direction import calibrate, circular_distance_degrees, circular_mean_degrees


def certify(first_source: Path, second_source: Path) -> dict[str, object]:
    first = calibrate(first_source)
    second = calibrate(second_source)
    first_profile = first.get("calibration_profile")
    second_profile = second.get("calibration_profile")
    if not isinstance(first_profile, dict) or not isinstance(second_profile, dict):
        return {"status": "NOT_READY", "reason": "primary frequency result is missing"}
    if first_profile.get("status") != "CANDIDATE" or second_profile.get("status") != "CANDIDATE":
        return {"status": "NOT_READY", "reason": "primary frequency quality gate failed"}
    axis_difference = circular_distance_degrees(
        float(first_profile["camera_to_microphone_axis_offset_deg"]),
        float(second_profile["camera_to_microphone_axis_offset_deg"]),
    )
    first_baseline = float(first_profile["effective_baseline_cm"])
    second_baseline = float(second_profile["effective_baseline_cm"])
    baseline_difference = abs(first_baseline - second_baseline) / (
        (first_baseline + second_baseline) / 2.0
    )
    passed = axis_difference <= 10.0 and baseline_difference <= 0.30
    return {
        "status": "PASS" if passed else "CANDIDATE_MISMATCH",
        "frequency_hz": first_profile["frequency_hz"],
        "axis_difference_deg": axis_difference,
        "baseline_relative_difference": baseline_difference,
        "merged_axis_offset_deg": circular_mean_degrees(
            [
                float(first_profile["camera_to_microphone_axis_offset_deg"]),
                float(second_profile["camera_to_microphone_axis_offset_deg"]),
            ],
            [1.0, 1.0],
        ),
        "merged_effective_baseline_cm": (first_baseline + second_baseline) / 2.0,
        "sources": [str(first_source), str(second_source)],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=Path)
    parser.add_argument("second", type=Path)
    args = parser.parse_args()
    print(json.dumps(certify(args.first.resolve(), args.second.resolve()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
