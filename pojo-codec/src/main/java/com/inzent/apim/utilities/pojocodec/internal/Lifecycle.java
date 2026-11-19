package com.inzent.apim.utilities.pojocodec.internal;

import com.inzent.apim.utilities.pojocodec.PojoCodecException;
import com.inzent.apim.utilities.pojocodec.PojoInjectable;

import java.io.IOException;

/**
 * Failure reporting must never replace the primary failure or notify a root twice.
 */
public final class Lifecycle {
	private Lifecycle() {
	}

	public static void beforeSerialize(Object bean) {
		if (bean instanceof PojoInjectable injectable) {
			injectable.beforeSerialize();
		}
	}

	public static void afterSerialize(Object bean) {
		if (bean instanceof PojoInjectable injectable) {
			injectable.afterSerialize();
		}
	}

	public static void beforeDeserialize(Object bean) {
		if (bean instanceof PojoInjectable injectable) {
			injectable.beforeDeserialize();
		}
	}

	public static void afterDeserialize(Object bean) {
		if (bean instanceof PojoInjectable injectable) {
			injectable.afterDeserialize();
		}
	}

	public static void serializeFailed(Object bean,
	                                   Throwable cause) {
		if (bean instanceof PojoInjectable injectable) {
			try {
				injectable.onSerializeFail(cause);
			}
			catch (Throwable secondary) {
				suppress(cause,
				         secondary);
			}
		}
	}

	public static void deserializeFailed(Object bean,
	                                     Throwable cause) {
		if (bean instanceof PojoInjectable injectable) {
			try {
				injectable.onDeserializeFail(cause);
			}
			catch (Throwable secondary) {
				suppress(cause,
				         secondary);
			}
		}
	}

	public static void rootDeserializeFailed(Session session,
	                                         Throwable cause) {
		if (!session.rootDeserializeFailed) {
			session.rootDeserializeFailed = true;
			deserializeFailed(session.rootDeserializedBean,
			                  cause);
		}
	}

	private static void suppress(Throwable primary,
	                             Throwable secondary) {
		if (primary != secondary) {
			primary.addSuppressed(secondary);
		}
	}

	public static IOException propagate(Throwable cause) {
		if (cause instanceof IOException exception) {
			return exception;
		}
		if (cause instanceof RuntimeException exception) {
			throw exception;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new PojoCodecException("Lifecycle operation failed",
		                              cause);
	}
}
