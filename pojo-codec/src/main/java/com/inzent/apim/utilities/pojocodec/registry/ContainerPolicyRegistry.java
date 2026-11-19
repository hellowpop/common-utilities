package com.inzent.apim.utilities.pojocodec.registry;

import java.util.Map;

/**
 * Policies for constructor properties not exposed by JDK public getters.
 */
public final class ContainerPolicyRegistry {
	public record Policy(Class<?> enumType,
	                     Class<?> checkedKeyType,
	                     Class<?> checkedValueType,
	                     boolean fair,
	                     boolean accessOrder) {
		public static Policy enumType(Class<? extends Enum<?>> type) {
			return new Policy(type,
			                  null,
			                  null,
			                  false,
			                  false);
		}

		public static Policy checked(Class<?> elementType) {
			return new Policy(null,
			                  elementType,
			                  null,
			                  false,
			                  false);
		}

		public static Policy checkedMap(Class<?> key,
		                                Class<?> value) {
			return new Policy(null,
			                  key,
			                  value,
			                  false,
			                  false);
		}
	}

	private final Map<Class<?>, Policy> policies;

	public ContainerPolicyRegistry(Map<Class<?>, Policy> policies) {
		this.policies = Map.copyOf(policies);
	}

	public Policy get(Class<?> type) {
		return policies.getOrDefault(type,
		                             new Policy(null,
		                                        null,
		                                        null,
		                                        false,
		                                        false));
	}
}
