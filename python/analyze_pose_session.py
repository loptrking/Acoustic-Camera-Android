"""Validate ARCore pose data in an AcousticLens session ZIP or directory."""

from __future__ import annotations

import argparse
import csv
import io
import json
import zipfile
from collections import Counter
from pathlib import Path

import numpy as np


def read_pose(path: Path) -> tuple[list[dict[str, str]], dict[str, object]]:
    if path.is_dir():
        pose_text = (path / "pose.csv").read_text(encoding="utf-8-sig")
        metadata = json.loads((path / "metadata.json").read_text(encoding="utf-8-sig"))
    else:
        with zipfile.ZipFile(path) as archive:
            pose_names = [name for name in archive.namelist() if Path(name).name == "pose.csv"]
            metadata_names = [
                name for name in archive.namelist() if Path(name).name == "metadata.json"
            ]
            if len(pose_names) != 1 or len(metadata_names) != 1:
                raise ValueError("ZIP must contain one pose.csv and one metadata.json")
            pose_text = archive.read(pose_names[0]).decode("utf-8-sig")
            metadata = json.loads(archive.read(metadata_names[0]).decode("utf-8-sig"))
    return list(csv.DictReader(io.StringIO(pose_text))), metadata


def analyze(path: str | Path) -> dict[str, object]:
    source = Path(path).expanduser().resolve()
    rows, metadata = read_pose(source)
    if not rows:
        return {
            "source": str(source),
            "rows": 0,
            "valid": False,
            "reason": "pose.csv contains no rows",
        }

    frame_ns = np.array([int(row["frame_timestamp_ns"]) for row in rows], dtype=np.int64)
    camera_ns = np.array(
        [int(row["android_camera_timestamp_ns"]) for row in rows], dtype=np.int64
    )
    callback_ns = np.array(
        [int(row["callback_monotonic_ns"]) for row in rows], dtype=np.int64
    )
    positions = np.array(
        [[float(row["x"]), float(row["y"]), float(row["z"])] for row in rows],
        dtype=np.float64,
    )
    states = [row["tracking_state"] for row in rows]
    tracking_mask = np.array([state == "TRACKING" for state in states])
    tracking_count = int(np.sum(tracking_mask))

    frame_steps = np.diff(frame_ns)
    callback_steps = np.diff(callback_ns)
    tracking_position = positions[tracking_mask]
    tracking_time = frame_ns[tracking_mask]
    if tracking_count >= 2:
        displacement = tracking_position - tracking_position[0]
        radius = np.linalg.norm(displacement, axis=1)
        aperture = float(np.max(radius))
        position_step = np.linalg.norm(np.diff(tracking_position, axis=0), axis=1)
        time_step = np.diff(tracking_time) / 1e9
        valid_step = time_step > 0
        speed = position_step[valid_step] / time_step[valid_step]
        path_length = float(np.sum(position_step))
        duration = float((tracking_time[-1] - tracking_time[0]) / 1e9)
    else:
        aperture = 0.0
        path_length = 0.0
        duration = 0.0
        speed = np.array([], dtype=np.float64)

    median_step = float(np.median(frame_steps)) if len(frame_steps) else 0.0
    dropped_intervals = (
        int(np.sum(frame_steps > 1.5 * median_step)) if median_step > 0 else 0
    )
    frame_callback_delta = callback_ns - frame_ns
    camera_frame_delta = camera_ns - frame_ns
    tracking_ratio = tracking_count / len(rows)
    scan_ready = (
        tracking_ratio >= 0.8
        and aperture >= 0.15
        and not np.any(frame_steps <= 0)
        and (len(speed) == 0 or float(np.percentile(speed, 95)) <= 0.25)
    )

    return {
        "source": str(source),
        "valid": True,
        "rows": len(rows),
        "tracking_rows": tracking_count,
        "tracking_ratio": round(tracking_ratio, 6),
        "tracking_states": dict(Counter(states)),
        "frame_timestamp": {
            "non_monotonic_steps": int(np.sum(frame_steps <= 0)),
            "interval_ms_median": round(median_step / 1e6, 6),
            "interval_ms_min": round(float(np.min(frame_steps)) / 1e6, 6)
            if len(frame_steps)
            else None,
            "interval_ms_max": round(float(np.max(frame_steps)) / 1e6, 6)
            if len(frame_steps)
            else None,
            "estimated_fps": round(1e9 / median_step, 4) if median_step > 0 else None,
            "dropped_intervals": dropped_intervals,
        },
        "clock_relationships": {
            "callback_minus_frame_ms_median": round(
                float(np.median(frame_callback_delta)) / 1e6, 6
            ),
            "callback_minus_frame_ms_min": round(
                float(np.min(frame_callback_delta)) / 1e6, 6
            ),
            "callback_minus_frame_ms_max": round(
                float(np.max(frame_callback_delta)) / 1e6, 6
            ),
            "camera_minus_frame_ms_median": round(
                float(np.median(camera_frame_delta)) / 1e6, 6
            ),
        },
        "trajectory": {
            "tracking_duration_seconds": round(duration, 6),
            "aperture_meters": round(aperture, 6),
            "path_length_meters": round(path_length, 6),
            "speed_mps_median": round(float(np.median(speed)), 6)
            if len(speed)
            else None,
            "speed_mps_p95": round(float(np.percentile(speed, 95)), 6)
            if len(speed)
            else None,
            "speed_mps_max": round(float(np.max(speed)), 6) if len(speed) else None,
        },
        "metadata_pose_rows_match": metadata.get("pose_row_count") == len(rows),
        "scan_ready": bool(scan_ready),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session")
    args = parser.parse_args()
    print(json.dumps(analyze(args.session), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
