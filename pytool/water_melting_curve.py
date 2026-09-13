"""Extend the packaged ice/liquid curve from IAPWS R14-08(2011), equations 1–5.

Source: https://iapws.org/public/documents/MdUFK/MeltSub2011.pdf
The solver interpolates this table and continues its final secant above the data.
Run from the repository root: uv run pytool/water_melting_curve.py
"""

import json
import math
from pathlib import Path


def pressure(phase: str, temperature: float) -> float:
    if phase == "Ih":
        theta = temperature / 273.16
        return 611.657 * (1 + sum(a * (1 - theta**b) for a, b in (
            (0.119539337e7, 3), (0.808183159e5, 25.75), (0.333826860e4, 103.75))))
    if phase == "VII":
        theta = temperature / 355
        return 2216e6 * math.exp(1.73683 * (1 - theta**-1)
                                - 0.0544606 * (1 - theta**5)
                                + 0.806106e-7 * (1 - theta**22))
    tref, pref, a, b = {"III": (251.165, 208.566e6, 0.299948, 60),
                       "V": (256.164, 350.1e6, 1.18721, 8),
                       "VI": (273.31, 632.4e6, 1.07476, 4.6)}[phase]
    return pref * (1 - a * (1 - (temperature / tref)**b))


def main() -> None:
    for phase, temperature, mpa in (("Ih", 260, 138.268), ("III", 254, 268.685),
                                   ("V", 265, 479.640), ("VI", 320, 1356.76),
                                   ("VII", 550, 6308.71)):
        assert abs(pressure(phase, temperature) / 1e6 - mpa) < 0.01
    path = Path(__file__).resolve().parents[1] / "fabric/src/main/resources/data/mmm/mmm/phase_transitions/ice_water.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    curve = [point for point in data["equilibrium_curve"] if float(point["pressure_pa"]) <= 2e6]
    samples = {"Ih": (272, 270, 265, 260, 255),
               "III": (251.165, 254), "V": (256.164, 260, 265, 270),
               "VI": (273.31, 290, 310, 330, 350),
               "VII": (355, 380, 410, 450, 500, 550, 600, 650, 700, 715)}
    for phase, temperatures in samples.items():
        curve.extend({"pressure_pa": repr(pressure(phase, t)), "temperature_k": repr(float(t))}
                     for t in temperatures)
    data["equilibrium_curve"] = sorted(curve, key=lambda point: float(point["pressure_pa"]))
    path.write_text(json.dumps(data, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {len(curve)} equilibrium points to {path}")


if __name__ == "__main__":
    main()
