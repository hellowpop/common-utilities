package com.inzent.apim.utilities.pojocodec;

/**
 * Optional instance lifecycle hooks. Implementations may override only the events they need.
 */
public interface PojoInjectable {
	default void beforeSerialize() {
	}

	default void afterSerialize() {
	}

	default void onSerializeFail(Throwable cause) {
	}

	default void beforeDeserialize() {
	}

	default void afterDeserialize() {
	}

	default void onDeserializeFail(Throwable cause) {
	}
}
