"""Ideal water–ethanol equilibrium using the production phase and caloric data.

GasLiquidPhaseModel.java derives B = latent enthalpy / specific gas constant;
gas-liquid-mass-transfer.comp uses P* = P0 exp(B (1/Tb - 1/T)).
"""

import argparse
import json
import math
from fractions import Fraction
from pathlib import Path


DATA = Path(__file__).resolve().parents[1] / "fabric/src/main/resources/data/mmm/mmm"
# GasRegistry.java and SolidVolume.java define these model units.
STANDARD_PRESSURE = 101325.0
SVQ_PER_CUBIC_METRE = 1 << 27


def load(group, name):
    return json.loads((DATA / group / f"{name}.json").read_text(encoding="utf-8"))


def number(value):
    return float(Fraction(str(value)))


def specific_energy(material, temperature):
    reference = number(material["reference_temperature_k"])
    low, high = sorted((reference, temperature))
    energy = sum(
        max(0.0, min(high, number(segment["maximum_temperature_k"]))
            - max(low, number(segment["minimum_temperature_k"])))
        * number(segment["specific_heat_j_per_kg_k"])
        for segment in material["caloric_segments"]
    )
    return number(material["reference_specific_energy_j_per_kg"]) + math.copysign(energy, temperature - reference)


def phase(name):
    transition = load("phase_transitions", name + "_vapor")
    liquid = load("thermal_materials", transition["lower_energy_material"].split(":")[-1])
    gas = load("thermal_materials", transition["upper_energy_material"].split(":")[-1])
    curve = transition["equilibrium_curve"]
    # ThermalPhaseTransition.equilibriumTemperatureK interpolates pressure.
    for left, right in zip(curve, curve[1:]):
        p0, p1 = number(left["pressure_pa"]), number(right["pressure_pa"])
        if p0 <= STANDARD_PRESSURE <= p1:
            t0, t1 = number(left["temperature_k"]), number(right["temperature_k"])
            boiling = t0 + (t1 - t0) * (STANDARD_PRESSURE - p0) / (p1 - p0)
            break
    else:
        raise ValueError("Standard pressure outside transition curve")
    mass = number(transition["mass_kg_per_batch"])
    liquid_volume = number(transition["lower_volume_svq_per_batch"]) / SVQ_PER_CUBIC_METRE
    gas_volume = number(transition["upper_volume_svq_per_batch"]) / SVQ_PER_CUBIC_METRE
    latent = (specific_energy(gas, boiling) - specific_energy(liquid, boiling)
              + STANDARD_PRESSURE * (gas_volume - liquid_volume) / mass)
    gas_constant = STANDARD_PRESSURE * gas_volume / (mass * boiling)
    return boiling, latent / gas_constant


def saturation(phase_data, temperature):
    boiling, slope = phase_data
    return STANDARD_PRESSURE * math.exp(slope * (1 / boiling - 1 / temperature))


def vapor_fraction(x, ethanol, water, temperature):
    e = x * saturation(ethanol, temperature)
    w = (1 - x) * saturation(water, temperature)
    return e / (e + w)


def main():
    parser = argparse.ArgumentParser(description="Ideal water–ethanol phase-equilibrium reference")
    parser.add_argument("--pressure-pa", type=float, default=STANDARD_PRESSURE)
    parser.add_argument("--contacts", type=int, default=4)
    parser.add_argument("--ethanol-volume-fraction", type=float, default=0.5)
    args = parser.parse_args()
    if not math.isfinite(args.pressure_pa) or args.pressure_pa <= 0 or args.contacts < 1 or not 0 <= args.ethanol_volume_fraction <= 1:
        parser.error("Pressure and contacts must be positive; volume fraction must be between 0 and 1")
    ethanol, water = phase("ethanol"), phase("water")
    e_volume = number(load("species", "ethanol")["effective_volume_m3_per_mol"])
    w_volume = number(load("species", "h2o")["effective_volume_m3_per_mol"])
    e_moles = args.ethanol_volume_fraction / e_volume
    w_moles = (1 - args.ethanol_volume_fraction) / w_volume
    x = e_moles / (e_moles + w_moles)
    print(f"Ideal reference: ethanol volume fraction={args.ethanol_volume_fraction:.6f}, feed mole fraction={x:.6f}")
    print(f"380 K single contact: water Psat={saturation(water, 380):.2f} Pa, ethanol Psat={saturation(ethanol, 380):.2f} Pa, vapor ethanol={vapor_fraction(x, ethanol, water, 380):.6f}")
    print(f"Repeated complete condensation and bubble-point equilibrium: pressure={args.pressure_pa:.2f} Pa")
    for contact in range(1, args.contacts + 1):
        low, high = 1.0, 1000.0
        for _ in range(100):
            temperature = (low + high) / 2
            pressure = x * saturation(ethanol, temperature) + (1 - x) * saturation(water, temperature)
            if pressure < args.pressure_pa:
                low = temperature
            else:
                high = temperature
        temperature = (low + high) / 2
        y = vapor_fraction(x, ethanol, water, temperature)
        print(f"  contact {contact}: T={temperature:.3f} K, liquid ethanol={x:.6f}, vapor ethanol={y:.6f}")
        x = y


if __name__ == "__main__":
    main()
