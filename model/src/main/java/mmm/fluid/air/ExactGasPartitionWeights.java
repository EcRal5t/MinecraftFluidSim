package mmm.fluid.air;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Positive geometric sums retained as bounded rational leaves, with certified final decisions. */
public final class ExactGasPartitionWeights {
	private static final int INITIAL_PRECISION = 2 * Long.SIZE;
	private record Term(BigInteger numerator, BigInteger denominator) { }
	private record Bounds(BigInteger lower, BigInteger upper) { }
	private record Cache(int precision, Bounds[] weights, Bounds sum) { }
	private final List<List<Term>> weights;
	private final List<Term> sum;
	private final int denominatorBits;
	private final int pivot;
	private volatile Cache cache;

	private ExactGasPartitionWeights(List<List<ExactRational>> groups) {
		if (groups == null || groups.isEmpty()) throw new IllegalArgumentException("Gas partition needs children");
		var children = new ArrayList<List<Term>>(groups.size());
		var all = new TreeMap<BigInteger, BigInteger>();
		for (var group : groups) {
			if (group == null) throw new IllegalArgumentException("Gas partition leaves are missing");
			var joined = new TreeMap<BigInteger, BigInteger>();
			for (var value : group) {
				if (value == null || value.signum() < 0) throw new IllegalArgumentException("Negative gas partition leaf");
				if (!value.isZero()) joined.merge(value.denominator(), value.numerator(), BigInteger::add);
			}
			var terms = terms(joined); children.add(terms);
			for (var term : terms) all.merge(term.denominator(), term.numerator(), BigInteger::add);
		}
		weights = List.copyOf(children); sum = terms(all);
		if (sum.isEmpty()) throw new IllegalArgumentException("Gas partition weights sum to zero");
		int bits = 0;
		for (var term : sum) bits = Math.addExact(bits, term.denominator().bitLength());
		denominatorBits = bits;
		int largest = 0;
		for (int i = 1; i < weights.size(); i++) if (sign(i, BigInteger.ONE, largest, BigInteger.ONE.negate(), BigInteger.ZERO, BigInteger.ZERO) > 0) largest = i;
		pivot = largest;
	}

	public static ExactGasPartitionWeights ofTerms(List<List<ExactRational>> groups) {
		return new ExactGasPartitionWeights(groups);
	}
	public static ExactGasPartitionWeights ofRationals(List<ExactRational> weights) {
		if (weights == null) throw new IllegalArgumentException("Gas partition weights are missing");
		var groups = new ArrayList<List<ExactRational>>(weights.size());
		for (var weight : weights) {
			if (weight == null) throw new IllegalArgumentException("Gas partition weight is missing");
			groups.add(List.of(weight));
		}
		return ofTerms(groups);
	}
	public int childCount() { return weights.size(); }

	/** Largest remainder uses exact quotas; equal remainders retain the child ordinal. */
	public long[] allocateNanos(long total) {
		if (total < 0) throw new IllegalArgumentException("Negative gas partition amount");
		long[] shares = new long[childCount()];
		if (total == 0) return shares;
		BigInteger amount = BigInteger.valueOf(total); var enclosure = positiveSumCache();
		long assigned = 0;
		for (int i = 0; i < shares.length; i++) {
			var weight = enclosure.weights()[i];
			long low = amount.multiply(weight.lower()).divide(enclosure.sum().upper()).min(amount).longValueExact();
			long high = amount.multiply(weight.upper()).divide(enclosure.sum().lower()).min(amount).longValueExact();
			while (low < high) {
				long middle = low + (high - low) / 2 + (high - low) % 2;
				if (sign(i, amount, -1, BigInteger.ZERO, BigInteger.valueOf(middle).negate(), BigInteger.ZERO) >= 0) low = middle;
				else high = middle - 1;
			}
			shares[i] = low; assigned = Math.addExact(assigned, low);
		}
		Integer[] order = new Integer[shares.length]; Arrays.setAll(order, i -> i);
		Arrays.sort(order, (first, second) -> {
			int comparison = sign(first, amount, second, amount.negate(),
					BigInteger.valueOf(shares[second]).subtract(BigInteger.valueOf(shares[first])), BigInteger.ZERO);
			return comparison == 0 ? Integer.compare(first, second) : -comparison;
		});
		long remaining = total - assigned;
		if (remaining < 0 || remaining >= shares.length) throw new IllegalStateException("Certified gas quotas do not close");
		for (int i = 0; i < remaining; i++) shares[order[i]]++;
		return shares;
	}

	/** Physical volume shares round once; the largest exact weight carries the source remainder. */
	public double[] partitionControlVolume(double total) {
		if (!Double.isFinite(total) || total < 0) throw new IllegalArgumentException("Invalid gas control volume");
		double[] result = new double[childCount()];
		if (total == 0) return result;
		SignedDyadic source = SignedDyadic.of(total), assigned = SignedDyadic.ZERO;
		for (int i = 0; i < result.length; i++) if (i != pivot && !weights.get(i).isEmpty()) {
			result[i] = observe(i, source, true); requirePositive(result[i]);
			assigned = assigned.add(SignedDyadic.of(result[i]));
		}
		var remainder = source.subtract(assigned);
		if (remainder.signum() <= 0) throw new IllegalArgumentException("Gas volume rounding exhausted its largest share");
		result[pivot] = remainder.toDouble(); requirePositive(result[pivot]);
		return result;
	}

	/** Absolute geometric measure used by ambient capture. */
	public double weightVolume(int child) {
		double result = observe(child, SignedDyadic.of(1), false);
		if (!weights.get(child).isEmpty()) requirePositive(result);
		return result;
	}

	private double observe(int child, SignedDyadic source, boolean normalized) {
		if (weights.get(child).isEmpty()) return 0;
		var enclosure = normalized ? positiveSumCache() : enclosure(INITIAL_PRECISION);
		var weight = enclosure.weights()[child];
		BigInteger lowerDenominator = normalized ? enclosure.sum().upper() : BigInteger.ONE.shiftLeft(enclosure.precision());
		BigInteger upperDenominator = normalized ? enclosure.sum().lower() : lowerDenominator;
		long low = Double.doubleToRawLongBits(observeQuotient(source, weight.lower(), lowerDenominator));
		long high = Double.doubleToRawLongBits(observeQuotient(source, weight.upper(), upperDenominator));
		while (low < high) {
			long middle = low + (high - low) / 2;
			double first = Double.longBitsToDouble(middle), second = Double.longBitsToDouble(middle + 1);
			SignedDyadic midpoint = Double.isFinite(second)
					? SignedDyadic.of(first).add(SignedDyadic.of(second)).multiply(SignedDyadic.of(1, -1))
					: SignedDyadic.of(first).add(SignedDyadic.of(Math.ulp(first)).multiply(SignedDyadic.of(1, -1)));
			int exponent = Math.min(source.exponent(), midpoint.exponent());
			BigInteger scale = source.significand().shiftLeft(source.exponent() - exponent);
			BigInteger threshold = midpoint.significand().shiftLeft(midpoint.exponent() - exponent).negate();
			int comparison = sign(child, scale, -1, BigInteger.ZERO,
					normalized ? threshold : BigInteger.ZERO, normalized ? BigInteger.ZERO : threshold);
			if (comparison == 0) return Double.longBitsToDouble((middle & 1) == 0 ? middle : middle + 1);
			if (comparison < 0) high = middle; else low = middle + 1;
		}
		return Double.longBitsToDouble(low);
	}
	private static double observeQuotient(SignedDyadic source, BigInteger weight, BigInteger denominator) {
		BigInteger numerator = source.significand().multiply(weight);
		if (source.exponent() >= 0) numerator = numerator.shiftLeft(source.exponent());
		else denominator = denominator.shiftLeft(-source.exponent());
		return ExactRational.roundQuotientToDouble(numerator, denominator);
	}

	private int sign(int first, BigInteger a, int second, BigInteger b, BigInteger total, BigInteger constant) {
		var current = enclosure(INITIAL_PRECISION);
		var initial = linearBounds(current, first, a, second, b, total, constant);
		if (initial.lower().signum() > 0) return 1;
		if (initial.upper().signum() < 0) return -1;
		if (initial.lower().signum() == 0 && initial.upper().signum() == 0) return 0;
		var expression = new TreeMap<BigInteger, BigInteger>();
		append(expression, sum, total);
		if (first >= 0) append(expression, weights.get(first), a);
		if (second >= 0) append(expression, weights.get(second), b);
		if (constant.signum() != 0) expression.merge(BigInteger.ONE, constant, BigInteger::add);
		expression.values().removeIf(value -> value.signum() == 0);
		var terms = terms(expression);
		if (terms.isEmpty()) return 0;
		int bits = 0;
		for (var term : terms) bits = Math.addExact(bits, term.denominator().bitLength());
		int maximumPrecision = Math.addExact(bits, Integer.SIZE - Integer.numberOfLeadingZeros(terms.size()) + 2);
		for (;;) {
			int precision = current.precision();
			var bound = bounds(terms, precision, null);
			if (bound.lower().signum() > 0) return 1;
			if (bound.upper().signum() < 0) return -1;
			// A nonzero rational sum has denominator dividing the product of these denominators.
			if (precision >= bits) {
				BigInteger minimum = BigInteger.ONE.shiftLeft(precision - bits);
				if (bound.lower().compareTo(minimum.negate()) > 0 && bound.upper().compareTo(minimum) < 0) return 0;
			}
			if (precision >= maximumPrecision) throw new IllegalStateException("Gas weight sign certificate did not terminate");
			current = enclosure((int) Math.min(2L * precision, maximumPrecision));
		}
	}
	private static void append(Map<BigInteger, BigInteger> result, List<Term> terms, BigInteger coefficient) {
		if (coefficient.signum() != 0) for (var term : terms)
			result.merge(term.denominator(), term.numerator().multiply(coefficient), BigInteger::add);
	}
	private static Bounds linearBounds(Cache cache, int first, BigInteger a, int second, BigInteger b, BigInteger total, BigInteger constant) {
		Bounds result = new Bounds(constant.shiftLeft(cache.precision()), constant.shiftLeft(cache.precision()));
		result = add(result, cache.sum(), total);
		if (first >= 0) result = add(result, cache.weights()[first], a);
		if (second >= 0) result = add(result, cache.weights()[second], b);
		return result;
	}
	private static Bounds add(Bounds result, Bounds value, BigInteger coefficient) {
		return coefficient.signum() >= 0
				? new Bounds(result.lower().add(value.lower().multiply(coefficient)), result.upper().add(value.upper().multiply(coefficient)))
				: new Bounds(result.lower().add(value.upper().multiply(coefficient)), result.upper().add(value.lower().multiply(coefficient)));
	}
	private Cache positiveSumCache() {
		var current = enclosure(INITIAL_PRECISION);
		while (current.sum().lower().signum() == 0) {
			if (current.precision() > denominatorBits) throw new IllegalStateException("Positive gas weight sum has no positive enclosure");
			current = enclosure((int) Math.min(2L * current.precision(), (long) denominatorBits + 1));
		}
		return current;
	}
	private synchronized Cache enclosure(int precision) {
		if (cache != null && cache.precision() >= precision) return cache;
		var reused = new HashMap<Term, Bounds>(); var children = new Bounds[childCount()];
		for (int i = 0; i < children.length; i++) children[i] = bounds(weights.get(i), precision, reused);
		cache = new Cache(precision, children, bounds(sum, precision, reused)); return cache;
	}
	private static Bounds bounds(List<Term> terms, int precision, Map<Term, Bounds> reused) {
		BigInteger lower = BigInteger.ZERO, upper = BigInteger.ZERO;
		for (var term : terms) {
			Bounds interval = reused == null ? null : reused.get(term);
			if (interval == null) {
				BigInteger[] quotient = term.numerator().shiftLeft(precision).divideAndRemainder(term.denominator());
				BigInteger floor = quotient[0];
				if (quotient[1].signum() < 0) floor = floor.subtract(BigInteger.ONE);
				interval = new Bounds(floor, quotient[1].signum() == 0 ? floor : floor.add(BigInteger.ONE));
				if (reused != null) reused.put(term, interval);
			}
			lower = lower.add(interval.lower()); upper = upper.add(interval.upper());
		}
		return new Bounds(lower, upper);
	}
	private static List<Term> terms(Map<BigInteger, BigInteger> values) {
		var joined = new TreeMap<BigInteger, BigInteger>();
		for (var entry : values.entrySet()) {
			BigInteger numerator = entry.getValue(), denominator = entry.getKey();
			while (numerator.signum() != 0) {
				var divisor = numerator.gcd(denominator);
				numerator = numerator.divide(divisor); denominator = denominator.divide(divisor);
				var previous = joined.remove(denominator);
				if (previous == null) { joined.put(denominator, numerator); break; }
				numerator = numerator.add(previous);
			}
		}
		return joined.entrySet().stream().map(entry -> new Term(entry.getValue(), entry.getKey())).toList();
	}
	private static void requirePositive(double value) {
		if (!(Double.isFinite(value) && value > 0)) throw new IllegalArgumentException("Positive gas volume has no finite positive representation");
	}
}
