package com.inzent.apim.utilities.pojocodec.registry;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;

import java.util.Comparator;
import java.util.Map;

public final class ComparatorRegistry {
	private final Map<String, Comparator<?>> comparators;

	public ComparatorRegistry(Map<String, Comparator<?>> registrations) {
		if (registrations.containsKey("natural") || registrations.containsKey("reverse-natural")) {
			throw new IllegalArgumentException("Reserved comparator ID");
		}
		comparators = Map.copyOf(registrations);
	}

	public String id(Comparator<?> comparator) throws PojoCodecException {
		if (comparator == null || comparator == Comparator.naturalOrder()) {
			return "natural";
		}
		if (comparator == Comparator.reverseOrder()) {
			return "reverse-natural";
		}
		for (Map.Entry<String, Comparator<?>> entry : comparators.entrySet()) {
			if (entry.getValue() == comparator) {
				return entry.getKey();
			}
		}
		throw new PojoCodecException("Comparator must be registered");
	}

	public Comparator<?> resolve(String id) throws PojoCodecException {
		if (id.equals("natural")) {
			return null;
		}
		if (id.equals("reverse-natural")) {
			return Comparator.reverseOrder();
		}
		Comparator<?> result = comparators.get(id);
		if (result == null) {
			throw new PojoCodecException("Unregistered comparator: " + id);
		}
		return result;
	}
}
