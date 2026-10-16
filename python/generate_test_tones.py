"""Generate phase-continuous AcousticLens reference tones."""

from __future__ import annotations

import argparse
import hashlib
import json
import wave
from pathlib import Path

import numpy as np


def generate_tone(
    destination: Path,
    frequency: float,
    duration: float,
    sample_rate: int,
    peak_dbfs: float,
    fade_seconds: float,
) -> dict[str, object]:
    total = int(round(duration * sample_rate))
    index = np.arange(total, dtype=np.float64)
    peak = 32767.0 * 10.0 ** (peak_dbfs / 20.0)
    signal = peak * np.sin(2.0 * np.pi * frequency * index / sample_rate)
    fade_length = min(int(round(fade_seconds * sample_rate)), total // 2)
    if fade_length:
        phase = np.linspace(0.0, np.pi, fade_length, endpoint=False)
        fade_in = 0.5 - 0.5 * np.cos(phase)
        signal[:fade_length] *= fade_in
        signal[-fade_length:] *= fade_in[::-1]
    pcm = np.rint(signal).astype("<i2")
    with wave.open(str(destination), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(pcm.tobytes())
    digest = hashlib.sha256(destination.read_bytes()).hexdigest().upper()
    return {
        "file": destination.name,
        "frequency_hz": frequency,
        "duration_seconds": duration,
        "sample_rate_hz": sample_rate,
        "channels": 1,
        "encoding": "PCM_16BIT_LE",
        "peak_dbfs": peak_dbfs,
        "fade_seconds": fade_seconds,
        "frames": total,
        "sha256": digest,
    }


def generate_band_limited_noise(
    destination: Path,
    low_frequency: float,
    high_frequency: float,
    duration: float,
    sample_rate: int,
    peak_dbfs: float,
    fade_seconds: float,
    seed: int,
) -> dict[str, object]:
    total = int(round(duration * sample_rate))
    generator = np.random.default_rng(seed)
    signal = generator.standard_normal(total)
    spectrum = np.fft.rfft(signal)
    frequencies = np.fft.rfftfreq(total, 1.0 / sample_rate)
    spectrum[(frequencies < low_frequency) | (frequencies > high_frequency)] = 0.0
    signal = np.fft.irfft(spectrum, n=total)
    signal /= np.max(np.abs(signal))
    signal *= 32767.0 * 10.0 ** (peak_dbfs / 20.0)
    fade_length = min(int(round(fade_seconds * sample_rate)), total // 2)
    if fade_length:
        phase = np.linspace(0.0, np.pi, fade_length, endpoint=False)
        fade_in = 0.5 - 0.5 * np.cos(phase)
        signal[:fade_length] *= fade_in
        signal[-fade_length:] *= fade_in[::-1]
    pcm = np.rint(signal).astype("<i2")
    with wave.open(str(destination), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(pcm.tobytes())
    digest = hashlib.sha256(destination.read_bytes()).hexdigest().upper()
    return {
        "file": destination.name,
        "signal": "deterministic band-limited Gaussian noise",
        "low_frequency_hz": low_frequency,
        "high_frequency_hz": high_frequency,
        "duration_seconds": duration,
        "sample_rate_hz": sample_rate,
        "channels": 1,
        "encoding": "PCM_16BIT_LE",
        "peak_dbfs": peak_dbfs,
        "fade_seconds": fade_seconds,
        "seed": seed,
        "frames": total,
        "sha256": digest,
    }


def generate_multitone(
    destination: Path,
    frequencies: list[float],
    duration: float,
    sample_rate: int,
    peak_dbfs: float,
    fade_seconds: float,
) -> dict[str, object]:
    """Generate equal-energy FFT-bin-locked tones with deterministic low-crest phases."""
    total = int(round(duration * sample_rate))
    index = np.arange(total, dtype=np.float64)
    signal = np.zeros(total, dtype=np.float64)
    tone_count = len(frequencies)
    phases: list[float] = []
    for tone_index, frequency in enumerate(frequencies):
        phase = np.pi * tone_index * (tone_index - 1) / tone_count
        phases.append(float(phase))
        signal += np.sin(2.0 * np.pi * frequency * index / sample_rate + phase)
    signal /= np.max(np.abs(signal))
    signal *= 32767.0 * 10.0 ** (peak_dbfs / 20.0)
    fade_length = min(int(round(fade_seconds * sample_rate)), total // 2)
    if fade_length:
        phase = np.linspace(0.0, np.pi, fade_length, endpoint=False)
        fade_in = 0.5 - 0.5 * np.cos(phase)
        signal[:fade_length] *= fade_in
        signal[-fade_length:] *= fade_in[::-1]
    pcm = np.rint(signal).astype("<i2")
    with wave.open(str(destination), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        output.writeframes(pcm.tobytes())
    digest = hashlib.sha256(destination.read_bytes()).hexdigest().upper()
    return {
        "file": destination.name,
        "signal": "deterministic equal-energy FFT-bin-locked multitone",
        "frequencies_hz": frequencies,
        "initial_phases_rad": phases,
        "duration_seconds": duration,
        "sample_rate_hz": sample_rate,
        "channels": 1,
        "encoding": "PCM_16BIT_LE",
        "peak_dbfs": peak_dbfs,
        "fade_seconds": fade_seconds,
        "frames": total,
        "sha256": digest,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).parents[1] / "doc")
    parser.add_argument("--duration", type=float, default=30.0)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = [
        generate_tone(
            args.output / f"test_tone_{frequency // 1000}kHz_30s_-12dBFS.wav",
            frequency=float(frequency),
            duration=args.duration,
            sample_rate=48_000,
            peak_dbfs=-12.0,
            fade_seconds=0.25,
        )
        for frequency in (1000, 4000)
    ]
    manifest.append(
        generate_band_limited_noise(
            args.output / "test_noise_500Hz-8kHz_30s_-12dBFS.wav",
            low_frequency=500.0,
            high_frequency=8_000.0,
            duration=args.duration,
            sample_rate=48_000,
            peak_dbfs=-12.0,
            fade_seconds=0.25,
            seed=20260919,
        )
    )
    fft_bin_frequencies = [bin_index * 48_000.0 / 4096.0 for bin_index in (47, 60, 73, 85, 98)]
    manifest.append(
        generate_multitone(
            args.output / "test_multitone_fft_bins_550-1148Hz_40s_-12dBFS.wav",
            frequencies=fft_bin_frequencies,
            duration=40.0,
            sample_rate=48_000,
            peak_dbfs=-12.0,
            fade_seconds=0.25,
        )
    )
    manifest.append(
        generate_tone(
            args.output / "test_tone_fftbin_550.781Hz_40s_-12dBFS.wav",
            frequency=47 * 48_000.0 / 4096.0,
            duration=40.0,
            sample_rate=48_000,
            peak_dbfs=-12.0,
            fade_seconds=0.25,
        )
    )
    manifest_path = args.output / "audio_test_manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
