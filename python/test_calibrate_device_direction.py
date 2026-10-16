from __future__ import annotations

import unittest

import numpy as np

from calibrate_device_direction import circular_distance_degrees, fit_band, physical_cluster


class CalibrationFitTest(unittest.TestCase):
    def test_recovers_known_baseline_and_direction(self) -> None:
        heading = np.deg2rad(np.linspace(-70.0, 70.0, 281))
        frequency = 1_000.0
        baseline_m = 0.055
        direction_deg = -25.0
        phase_offset = 0.4
        phase = np.angle(
            np.exp(
                1j
                * (
                    phase_offset
                    + 2.0
                    * np.pi
                    * frequency
                    * baseline_m
                    / 343.0
                    * np.cos(heading - np.deg2rad(direction_deg))
                )
            )
        )

        result = fit_band(heading, phase, frequency)

        self.assertGreater(result["score"], 0.999)
        self.assertLess(result["residual_rms_deg"], 1.0)
        self.assertAlmostEqual(result["effective_baseline_cm"], 5.5, delta=0.2)
        self.assertLess(
            circular_distance_degrees(result["fit_direction_deg"], direction_deg),
            2.0,
        )

    def test_physical_cluster_rejects_same_direction_with_inconsistent_baseline(self) -> None:
        results = [
            {"score": 0.98, "fit_direction_deg": -138.0, "effective_baseline_cm": 1.6},
            {"score": 0.95, "fit_direction_deg": -128.5, "effective_baseline_cm": 11.05},
            {"score": 0.94, "fit_direction_deg": 20.0, "effective_baseline_cm": 9.0},
        ]

        cluster = physical_cluster(results)

        self.assertEqual(len(cluster), 1)


if __name__ == "__main__":
    unittest.main()
