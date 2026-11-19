package com.inzent.apim.utilities.pojocodec;

/**
 * All limits apply to both reading and writing.
 */
public record CodecLimits(int documentBytes,
                          int frameBytes,
                          int stringBytes,
                          int identifierBytes,
                          int containerItems,
                          int fields,
                          int depth,
                          long bufferBytes,
                          long totalItems) {
	public CodecLimits {
		if (documentBytes < 1 || frameBytes < 1 || stringBytes < 1 || identifierBytes < 1 || containerItems < 1 || fields < 1 || depth < 1 || bufferBytes < 1 || totalItems < 1) {
			throw new IllegalArgumentException("Limits must be positive");
		}
	}

	public static CodecLimits defaults() {
		return new CodecLimits(64 * 1024 * 1024,
		                       16 * 1024 * 1024,
		                       1024 * 1024,
		                       4096,
		                       100000,
		                       10000,
		                       64,
		                       128L * 1024 * 1024,
		                       1000000);
	}
}
