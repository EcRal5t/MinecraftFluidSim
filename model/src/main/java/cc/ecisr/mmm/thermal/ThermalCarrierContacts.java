package cc.ecisr.mmm.thermal;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

/** Ordered carrier contacts in primitive columns; contact objects are created only by list readers. */
public final class ThermalCarrierContacts extends AbstractList<ThermalCarrierTransport.Contact> {
	int[] first, second;
	double[] conductance, reservoir;
	private final double[] degree;
	private int count;

	public ThermalCarrierContacts(int capacity) {
		this(capacity, null);
	}
	/** Strict zero-flux preflight retains the ordered owner degrees instead of contact columns. */
	ThermalCarrierContacts(int capacity, double[] degree) {
		if (capacity < 0) throw new IllegalArgumentException("Negative thermal contact capacity");
		this.degree = degree;
		if (degree != null) capacity = 0;
		first = new int[capacity]; second = new int[capacity];
		conductance = new double[capacity]; reservoir = new double[capacity];
	}
	ThermalCarrierContacts(List<ThermalCarrierTransport.Contact> contacts) {
		this(contacts.size());
		for (var contact : contacts) add(contact.first(), contact.second(), contact.conductance(), contact.reservoirTemperatureK());
	}
	public void add(int a, int b, double g, double temperature) {
		ThermalCarrierTransport.Contact.require(a, b, g, temperature);
		if (count == first.length) {
			int capacity = Math.max(Math.addExact(count, 1), Math.addExact(count, Math.max(16, count / 2)));
			first = Arrays.copyOf(first, capacity); second = Arrays.copyOf(second, capacity);
			conductance = Arrays.copyOf(conductance, capacity); reservoir = Arrays.copyOf(reservoir, capacity);
		}
		write(count++, a, b, g, temperature);
		modCount++;
	}
	/** Writes a preassigned slot; workers own disjoint intervals of the allocated columns. */
	void put(int index, int a, int b, double g, double temperature) {
		ThermalCarrierTransport.Contact.require(a, b, g, temperature);
		write(index, a, b, g, temperature);
	}
	private void write(int index, int a, int b, double g, double temperature) {
		if (degree != null) {
			if (a >= degree.length || b >= degree.length) throw new IllegalArgumentException("Unknown conduction owner");
			degree[a] += g;
			if (b >= 0) degree[b] += g;
			return;
		}
		first[index] = a; second[index] = b; conductance[index] = g; reservoir[index] = temperature;
	}
	void compact(int[] starts, int[] ends) {
		int target = 0;
		for (int cell = 0; cell < ends.length; cell++) {
			int length = ends[cell] - starts[cell];
			if (degree == null) {
				System.arraycopy(first, starts[cell], first, target, length);
				System.arraycopy(second, starts[cell], second, target, length);
				System.arraycopy(conductance, starts[cell], conductance, target, length);
				System.arraycopy(reservoir, starts[cell], reservoir, target, length);
			}
			target += length;
		}
		count = target; modCount++;
	}
	@Override public boolean add(ThermalCarrierTransport.Contact contact) {
		add(contact.first(), contact.second(), contact.conductance(), contact.reservoirTemperatureK()); return true;
	}
	@Override public int size() { return count; }
	@Override public ThermalCarrierTransport.Contact get(int index) {
		java.util.Objects.checkIndex(index, count);
		return new ThermalCarrierTransport.Contact(first[index], second[index], conductance[index], reservoir[index]);
	}
}
