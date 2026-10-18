package mmm.granular;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;
import mmm.inventory.LiquidCellInventory;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

/** Pure saturation and integer composition transfer over connected cell liquid stocks. */
public final class GranularPhaseTransferEngine {
  private final ChemicalRegistry chemistry;
  private final SolverUnitScale units;

  public GranularPhaseTransferEngine(ChemicalRegistry chemistry, SolverUnitScale units) {
    this.chemistry = java.util.Objects.requireNonNull(chemistry);
    this.units = java.util.Objects.requireNonNull(units);
  }

  public record LiquidPortion(
      LiquidCellInventory.Key key,
      SignedDyadic volume,
      long massUnits,
      ChemicalMixtureRecord mixture) {
    public LiquidPortion {
      if (key == null
          || volume == null
          || volume.signum() <= 0
          || massUnits <= 0
          || mixture == null) throw new IllegalArgumentException("Invalid reactive liquid stock");
    }
  }

  public record StockUpdate(
      LiquidCellInventory.Key key,
      ChemicalMixtureRecord mixture,
      SignedDyadic volume,
      long massUnits) {}

  public record Plan(
      long signedExtent,
      long signedSolidSvq,
      long signedSolidMassUnits,
      SolidCompositionRecord transferredComposition,
      List<StockUpdate> stockUpdates,
      boolean retryRequired) {
    public Plan {
      stockUpdates = List.copyOf(stockUpdates);
    }

    static Plan none() {
      return new Plan(0, 0, 0, null, List.of(), false);
    }

    static Plan deferred() {
      return new Plan(0, 0, 0, null, List.of(), true);
    }

    public boolean changed() {
      return signedExtent != 0;
    }

    public boolean precipitation() {
      return signedExtent > 0;
    }

    public double signedSolidMass() {
      return Mass.kilograms(signedSolidMassUnits);
    }
  }

  public Plan plan(
      SolidLiquidReaction rule,
      List<LiquidPortion> liquid,
      long existingExtent,
      long maximumExtent) {
    if (rule == null
        || liquid == null
        || liquid.isEmpty()
        || existingExtent < 0
        || maximumExtent <= 0)
      throw new IllegalArgumentException("Invalid solid/liquid saturation input");
    long[] species = new long[chemistry.species().size()];
    long totalMass = 0;
    SignedDyadic totalVolume = SignedDyadic.ZERO;
    LiquidCellInventory.Key previous = null;
    for (var source : liquid) {
      if (previous != null && previous.compareTo(source.key()) >= 0)
        throw new IllegalArgumentException("Reactive stock identities must be sorted and unique");
      previous = source.key();
      totalVolume = totalVolume.add(source.volume());
      totalMass = Math.addExact(totalMass, source.massUnits());
      for (int entry = 0; entry < source.mixture().entryCount(); entry++)
        species[source.mixture().speciesHandle(entry)] =
            Math.addExact(
                species[source.mixture().speciesHandle(entry)], source.mixture().amountAt(entry));
    }
    long aqueous = Long.MAX_VALUE;
    for (var term : rule.aqueousStoichiometry().entrySet())
      aqueous =
          Math.min(aqueous, species[chemistry.speciesHandle(term.getKey())] / term.getValue());
    if (aqueous == Long.MAX_VALUE) aqueous = 0;
    long all = Math.addExact(aqueous, existingExtent),
        desired =
            Math.max(
                    0,
                    all
                        - capacity(
                            rule.saturationConcentrationMolPerCubicMeter(), totalVolume, all))
                - existingExtent;
    if (desired == 0 || desired < 0 && !rule.reversible()) return Plan.none();
    boolean precipitation = desired > 0;
    long batchExtent = rule.solidExtentPerBatch();
    long batches = Math.min(Math.abs(desired), maximumExtent) / batchExtent;
    var batchSolid = solid(rule, batchExtent);
    long batchMass = batchSolid.massUnits(chemistry);
    if (precipitation) {
      batches = Math.min(batches, (totalMass - 1) / batchMass);
      var scaled = totalVolume.multiply(SignedDyadic.of(SolidVolume.SVQ_PER_CELL, 0));
      BigInteger floor =
          scaled.exponent() >= 0
              ? scaled.significand().shiftLeft(scaled.exponent())
              : scaled.significand().shiftRight(-scaled.exponent());
      if (scaled.fitsLongUnits(0)) floor = floor.subtract(BigInteger.ONE);
      batches =
          Math.min(
              batches,
              floor
                  .max(BigInteger.ZERO)
                  .divide(BigInteger.valueOf(rule.solidVolumeSvqPerBatch()))
                  .min(BigInteger.valueOf(Long.MAX_VALUE))
                  .longValueExact());
    }
    if (batches == 0) return Plan.deferred();
    long extent = Math.multiplyExact(batches, batchExtent),
        solidVolume = Math.multiplyExact(batches, rule.solidVolumeSvqPerBatch());
    var solid = solid(rule, extent);
    long solidMass = solid.massUnits(chemistry);
    long[] massWeights = new long[liquid.size()];
    double[] geometryWeights = new double[liquid.size()];
    var volumeBounds = new SignedDyadic[liquid.size()];
    for (int i = 0; i < liquid.size(); i++) {
      var source = liquid.get(i);
      massWeights[i] = source.massUnits();
      geometryWeights[i] = source.volume().toDouble();
      volumeBounds[i] =
          precipitation
              ? source.volume().subtract(SignedDyadic.of(Double.MIN_VALUE))
              : SignedDyadic.of(solidVolume, -24);
    }
    var masses = allocate(solidMass, massWeights);
    var volumes =
        LiquidCellInventory.allocateVolumes(
            SignedDyadic.of(solidVolume, -24), volumeBounds, geometryWeights);
    var amounts = new ArrayList<TreeMap<Integer, Long>>();
    for (var source : liquid) {
      var map = new TreeMap<Integer, Long>();
      for (int e = 0; e < source.mixture().entryCount(); e++)
        map.put(source.mixture().speciesHandle(e), source.mixture().amountAt(e));
      amounts.add(map);
    }
    for (var term : rule.aqueousStoichiometry().entrySet()) {
      int speciesHandle = chemistry.speciesHandle(term.getKey());
      long moved = Math.multiplyExact(extent, term.getValue());
      long[] weights = massWeights;
      if (precipitation) {
        weights = new long[liquid.size()];
        for (int i = 0; i < weights.length; i++)
          weights[i] = liquid.get(i).mixture().amount(speciesHandle);
      }
      var shares = allocate(moved, weights);
      for (int i = 0; i < shares.length; i++) {
        long value =
            Math.addExact(
                amounts.get(i).getOrDefault(speciesHandle, 0L),
                precipitation ? -shares[i] : shares[i]);
        if (value == 0) amounts.get(i).remove(speciesHandle);
        else amounts.get(i).put(speciesHandle, value);
      }
    }
    var updates = new ArrayList<StockUpdate>();
    long afterMass = 0, residualBefore = 0, residualAfter = 0;
    for (int i = 0; i < liquid.size(); i++) {
      var source = liquid.get(i);
      long mass =
          precipitation
              ? source.massUnits() - masses[i]
              : Math.addExact(source.massUnits(), masses[i]);
      var composition = mixture(amounts.get(i));
      composition =
          composition.withMassResidualUnits(
              Math.subtractExact(mass, composition.speciesMassUnits(chemistry)));
      var volume =
          precipitation ? source.volume().subtract(volumes[i]) : source.volume().add(volumes[i]);
      updates.add(new StockUpdate(source.key(), composition, volume, mass));
      afterMass = Math.addExact(afterMass, mass);
      residualBefore = Math.addExact(residualBefore, source.mixture().massResidualUnits());
      residualAfter = Math.addExact(residualAfter, composition.massResidualUnits());
    }
    long signedMass = precipitation ? solidMass : -solidMass;
    if (Math.addExact(afterMass, signedMass) != totalMass || residualAfter != residualBefore)
      throw new IllegalStateException("Reaction integer mass/residual failed to close");
    return new Plan(
        precipitation ? extent : -extent,
        precipitation ? solidVolume : -solidVolume,
        signedMass,
        solid,
        updates,
        false);
  }

  private long capacity(ExactRational concentration, SignedDyadic volume, long maximum) {
    var amount =
        volume.exponent() >= 0
            ? new ExactRational(volume.significand().shiftLeft(volume.exponent()), BigInteger.ONE)
            : new ExactRational(volume.significand(), BigInteger.ONE.shiftLeft(-volume.exponent()));
    amount =
        amount
            .multiply(ExactRational.ofDouble(units.cubicMetersPerNormalizedVolume()))
            .multiply(concentration)
            .multiply(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL);
    return amount
        .numerator()
        .divide(amount.denominator())
        .min(BigInteger.valueOf(maximum))
        .longValueExact();
  }

  private SolidCompositionRecord solid(SolidLiquidReaction rule, long extent) {
    var species = new int[rule.solidComposition().size()];
    var amounts = new long[species.length];
    var map = new TreeMap<Integer, Long>();
    for (var term : rule.solidComposition().entrySet())
      map.put(chemistry.speciesHandle(term.getKey()), Math.multiplyExact(extent, term.getValue()));
    int i = 0;
    for (var term : map.entrySet()) {
      species[i] = term.getKey();
      amounts[i++] = term.getValue();
    }
    return new SolidCompositionRecord(species, amounts, 0);
  }

  private ChemicalMixtureRecord mixture(TreeMap<Integer, Long> map) {
    var species = new int[map.size()];
    var amounts = new long[map.size()];
    int i = 0;
    for (var term : map.entrySet()) {
      species[i] = term.getKey();
      amounts[i++] = term.getValue();
    }
    return new ChemicalMixtureRecord(species, amounts, 0);
  }

  /** Bounded integer quotas; every leftover is one mass/species quantum. */
  private static long[] allocate(long amount, long[] weights) {
    long total = 0;
    for (long value : weights) total = Math.addExact(total, value);
    if (total <= 0 || amount < 0)
      throw new IllegalArgumentException("Reactive allocation has no actual stock");
    var result = new long[weights.length];
    long remainder = amount;
    for (int i = 0; i < weights.length; i++) {
      result[i] = Mass.portion(amount, weights[i], total);
      remainder -= result[i];
    }
    for (int i = 0; remainder > 0 && i < result.length; i++) {
      if (amount > total || result[i] < weights[i]) {
        result[i]++;
        remainder--;
      }
    }
    if (remainder != 0) throw new IllegalStateException("Reactive integer quotas failed to close");
    return result;
  }

  public static final class BlockedException extends IllegalStateException {
    public BlockedException(String message) {
      super(message);
    }
  }
}
