"""Analyze amplitude and cross-time phase stability of a recorded test tone."""

from __future__ import annotations

import argparse
import json

import numpy as np

from session_io import load_session


def analyze_tone(path: str, target_hz: float) -> dict[str, object]:
    session = load_session(path)
    samples = session.samples
    if samples.ndim != 1:
        samples = samples[:, 0]
    samples = samples.astype(np.float64)
    rate = session.sample_rate
    block_size = 4096
    hop = 2048
    window = np.hanning(block_size)
    local_index = np.arange(block_size)
    starts = np.arange(0, len(samples) - block_size + 1, hop)
    centers = (starts + block_size / 2) / rate
    complex_amplitude = []
    noise_amplitude = []

    for start in starts:
        block = samples[start : start + block_size]
        block = block - np.mean(block)
        demodulated = (
            2
            * np.sum(
                block
                * window
                * np.exp(-2j * np.pi * target_hz * local_index / rate)
            )
            / np.sum(window)
        )
        # Local demodulation resets its phase in every block. Restore the
        # global sample origin so unwrapped phase describes only frequency error.
        demodulated *= np.exp(-2j * np.pi * target_hz * start / rate)
        complex_amplitude.append(demodulated)
        sidebands = []
        for probe_hz in (target_hz - 83.0, target_hz + 83.0):
            sidebands.append(
                abs(
                    2
                    * np.sum(
                        block
                        * window
                        * np.exp(-2j * np.pi * probe_hz * local_index / rate)
                    )
                    / np.sum(window)
                )
            )
        noise_amplitude.append(float(np.mean(sidebands)))

    complex_amplitude = np.asarray(complex_amplitude)
    amplitude = np.abs(complex_amplitude)
    noise_amplitude = np.asarray(noise_amplitude)
    active = amplitude > max(float(np.max(amplitude)) * 0.15, 2 * float(np.median(noise_amplitude)))
    indices = np.flatnonzero(active)
    if len(indices) < 10:
        raise ValueError("No stable tone segment was detected")
    groups = np.split(indices, np.where(np.diff(indices) > 1)[0] + 1)
    selected = max(groups, key=len)

    time = centers[selected]
    selected_complex = complex_amplitude[selected]
    selected_amplitude = np.abs(selected_complex)
    phase = np.unwrap(np.angle(selected_complex))
    phase_fit = np.polyfit(time, phase, 1)
    phase_residual = phase - np.polyval(phase_fit, time)
    phase_residual -= np.mean(phase_residual)
    estimated_hz = target_hz + phase_fit[0] / (2 * np.pi)
    amplitude_dbfs = 20 * np.log10(np.maximum(selected_amplitude, 1e-12) / 32768)

    harmonic_amplitudes = []
    for harmonic in range(1, 6):
        harmonic_hz = harmonic * estimated_hz
        if harmonic_hz >= rate / 2:
            break
        values = []
        for block_index in selected:
            start = starts[block_index]
            block = samples[start : start + block_size]
            block = block - np.mean(block)
            values.append(
                abs(
                    2
                    * np.sum(
                        block
                        * window
                        * np.exp(-2j * np.pi * harmonic_hz * local_index / rate)
                    )
                    / np.sum(window)
                )
            )
        harmonic_amplitudes.append(float(np.median(values)))

    thd = (
        np.sqrt(np.sum(np.square(harmonic_amplitudes[1:])))
        / harmonic_amplitudes[0]
    )
    phase_std_degrees = float(np.degrees(np.std(phase_residual)))
    coherence = float(abs(np.mean(np.exp(1j * phase_residual))))
    max_phase_step = float(np.degrees(np.max(np.abs(np.diff(phase_residual)))))
    sound_speed = 343.0
    path_error_mm = sound_speed / target_hz * phase_std_degrees / 360 * 1000
    snr_proxy_db = float(
        np.median(
            20
            * np.log10(
                selected_amplitude
                / np.maximum(noise_amplitude[selected], 1e-12)
            )
        )
    )
    if coherence >= 0.97 and phase_std_degrees <= 15:
        quality = "good"
    elif coherence >= 0.85 and max_phase_step <= 45 and snr_proxy_db >= 20:
        quality = "usable"
    else:
        quality = "poor"

    return {
        "source": str(session.source),
        "target_hz": target_hz,
        "estimated_hz": round(float(estimated_hz), 6),
        "frequency_offset_hz": round(float(estimated_hz - target_hz), 6),
        "active_duration_seconds": round(
            float(time[-1] - time[0] + block_size / rate), 6
        ),
        "amplitude": {
            "median_dbfs": round(float(np.median(amplitude_dbfs)), 4),
            "p05_dbfs": round(float(np.percentile(amplitude_dbfs, 5)), 4),
            "p95_dbfs": round(float(np.percentile(amplitude_dbfs, 95)), 4),
            "standard_deviation_db": round(float(np.std(amplitude_dbfs)), 4),
        },
        "phase": {
            "residual_standard_deviation_degrees": round(phase_std_degrees, 4),
            "residual_p95_absolute_degrees": round(
                float(np.degrees(np.percentile(np.abs(phase_residual), 95))), 4
            ),
            "maximum_step_degrees": round(max_phase_step, 4),
            "coherence": round(coherence, 6),
            "equivalent_path_error_rms_mm": round(path_error_mm, 4),
        },
        "snr_proxy_db": round(snr_proxy_db, 4),
        "harmonics_dbfs": [
            round(float(20 * np.log10(max(value, 1e-12) / 32768)), 4)
            for value in harmonic_amplitudes
        ],
        "approximate_thd_percent": round(float(thd * 100), 4),
        "quality": quality,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session")
    parser.add_argument("frequency", type=float)
    args = parser.parse_args()
    print(
        json.dumps(
            analyze_tone(args.session, args.frequency),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
