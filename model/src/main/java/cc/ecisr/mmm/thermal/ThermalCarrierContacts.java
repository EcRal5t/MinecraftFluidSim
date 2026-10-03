package cc.ecisr.mmm.thermal;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

/** Ordered carrier contacts in primitive columns; contact objects are created only by list readers. */
public final class ThermalCarrierContacts extends AbstractList<ThermalCarrierTransport.Contact> {
	int[] first, second;
	double[] conductance, reservoir;
	private int count;

	public ThermalCarrierContacts(int capacity) {
		if (capacity < 0) throw new IllegalArgumentException("Negative thermal contact capacity");
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
		first[count] = a; second[count] = b; conductance[count] = g; reservoir[count++] = temperature;
		modCount++;
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
