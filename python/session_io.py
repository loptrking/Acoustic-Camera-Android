"""Read an AcousticLens session from an exported ZIP or a directory."""

from __future__ import annotations

import csv
import io
import json
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np


@dataclass(frozen=True)
class SessionData:
    samples: np.ndarray
    sample_rate: int
    channels: int
    sample_width: int
    metadata: dict[str, Any]
    timestamps: list[dict[str, str]]
    source: Path


def _read_required_from_zip(archive: zipfile.ZipFile, name: str) -> bytes:
    matches = [entry for entry in archive.namelist() if Path(entry).name == name]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one {name!r} in ZIP, found {len(matches)}")
    return archive.read(matches[0])


def _read_session_files(source: Path) -> tuple[bytes, bytes, bytes]:
    if source.is_dir():
        return (
            (source / "audio.wav").read_bytes(),
            (source / "audio_timestamps.csv").read_bytes(),
            (source / "metadata.json").read_bytes(),
        )
    if source.suffix.lower() != ".zip":
        raise ValueError("Session must be an exported ZIP or an extracted directory")
    with zipfile.ZipFile(source) as archive:
        return (
            _read_required_from_zip(archive, "audio.wav"),
            _read_required_from_zip(archive, "audio_timestamps.csv"),
            _read_required_from_zip(archive, "metadata.json"),
        )


def load_session(path: str | Path) -> SessionData:
    source = Path(path).expanduser().resolve()
    if not source.exists():
        raise FileNotFoundError(source)
    wav_bytes, timestamp_bytes, metadata_bytes = _read_session_files(source)

    with wave.open(io.BytesIO(wav_bytes), "rb") as wav_file:
        channels = wav_file.getnchannels()
        sample_rate = wav_file.getframerate()
        sample_width = wav_file.getsampwidth()
        frame_count = wav_file.getnframes()
        if sample_width != 2:
            raise ValueError(f"Only 16-bit PCM is supported, got {sample_width * 8}-bit")
        raw = wav_file.readframes(frame_count)

    samples = np.frombuffer(raw, dtype="<i2").copy()
    if channels > 1:
        samples = samples.reshape(-1, channels)

    timestamps = list(
        csv.DictReader(io.StringIO(timestamp_bytes.decode("utf-8-sig")))
    )
    metadata = json.loads(metadata_bytes.decode("utf-8-sig"))
    return SessionData(
        samples=samples,
        sample_rate=sample_rate,
        channels=channels,
        sample_width=sample_width,
        metadata=metadata,
        timestamps=timestamps,
        source=source,
    )
