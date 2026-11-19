package com.inzent.apim.utilities.pojocodec.metadata;

import java.lang.reflect.Constructor;
import java.util.List;

public record ClassMetadata(Class<?> type,
                            Constructor<?> constructor,
                            List<FieldMetadata> fields) {
	public ClassMetadata {
		fields = List.copyOf(fields);
	}
}
