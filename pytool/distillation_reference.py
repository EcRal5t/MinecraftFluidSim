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


def bubble_point(x, ethanol, water, pressure):
    low, high = 1.0, 1000.0
    for _ in range(100):
        temperature = (low + high) / 2
        equilibrium_pressure = x * saturation(ethanol, temperature) + (1 - x) * saturation(water, temperature)
        if equilibrium_pressure < pressure:
            low = temperature
        else:
            high = temperature
    return (low + high) / 2


def rayleigh_cut(e_moles, w_moles, ethanol, water, pressure, e_volume, w_volume, fraction):
    """Condense a fixed volume cut while the well-mixed still composition changes."""
    initial_e, initial_w = e_moles, w_moles
    cut_volume = (e_moles * e_volume + w_moles * w_volume) * fraction
    increment = cut_volume / 512
    for _ in range(512):
        remaining = increment
        while remaining > 0:
            x = e_moles / (e_moles + w_moles)
            temperature = bubble_point(x, ethanol, water, pressure)
            y = vapor_fraction(x, ethanol, water, temperature)
            step = remaining
            moles = step / (y * e_volume + (1 - y) * w_volume)
            while y * moles > e_moles or (1 - y) * moles > w_moles:
                step *= 0.5
                moles = step / (y * e_volume + (1 - y) * w_volume)
            e_moles -= y * moles
            w_moles -= (1 - y) * moles
            remaining -= step
    product_e, product_w = initial_e - e_moles, initial_w - w_moles
    return product_e, product_w, e_moles, w_moles


def main():
    parser = argparse.ArgumentParser(description="Ideal water–ethanol phase-equilibrium reference")
    parser.add_argument("--pressure-pa", type=float, default=STANDARD_PRESSURE)
    parser.add_argument("--contacts", type=int, default=4)
    parser.add_argument("--ethanol-volume-fraction", type=float, default=0.5)
    parser.add_argument("--cut-volume-fraction", type=float, default=0.2)
    parser.add_argument("--feed-volume-litres", type=float, default=100.0)
    args = parser.parse_args()
    if (not math.isfinite(args.pressure_pa) or args.pressure_pa <= 0 or args.contacts < 1
            or not 0 <= args.ethanol_volume_fraction <= 1 or not 0 < args.cut_volume_fraction < 1
            or not math.isfinite(args.feed_volume_litres) or args.feed_volume_litres <= 0):
        parser.error("Pressure, contacts and feed volume must be positive; feed fraction must be in [0, 1] and cut fraction in (0, 1)")
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
        temperature = bubble_point(x, ethanol, water, args.pressure_pa)
        y = vapor_fraction(x, ethanol, water, temperature)
        print(f"  contact {contact}: T={temperature:.3f} K, liquid ethanol={x:.6f}, vapor ethanol={y:.6f}")
        x = y

    feed_volume = args.feed_volume_litres * 0.001
    e_moles = feed_volume * args.ethanol_volume_fraction / e_volume
    w_moles = feed_volume * (1 - args.ethanol_volume_fraction) / w_volume
    print(f"Finite Rayleigh batches: feed={args.feed_volume_litres:.6g} L, cut={args.cut_volume_fraction:.2%} of each batch by liquid volume, pressure={args.pressure_pa:.2f} Pa")
    for batch in range(1, args.contacts + 1):
        volume = e_moles * e_volume + w_moles * w_volume
        product_e, product_w, remaining_e, remaining_w = rayleigh_cut(
            e_moles, w_moles, ethanol, water, args.pressure_pa, e_volume, w_volume, args.cut_volume_fraction)
        product_volume = product_e * e_volume + product_w * w_volume
        residual_volume = remaining_e * e_volume + remaining_w * w_volume
        print(f"  batch {batch}: feed={1000 * volume:.6g} L, distillate={1000 * product_volume:.6g} L "
              f"x={product_e / (product_e + product_w):.6f}, residual={1000 * residual_volume:.6g} L "
              f"x={remaining_e / (remaining_e + remaining_w):.6f}")
        e_moles, w_moles = product_e, product_w


if __name__ == "__main__":
    main()
