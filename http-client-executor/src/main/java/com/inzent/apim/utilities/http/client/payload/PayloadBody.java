package com.inzent.apim.utilities.http.client.payload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

import com.inzent.apim.utilities.http.client.model.HttpHeaders;
import com.inzent.apim.utilities.http.client.model.RequestBody;
import com.inzent.apim.utilities.http.client.store.BodyStore;
import com.inzent.apim.utilities.http.payload.ContentEncoding;
import com.inzent.apim.utilities.http.payload.ContentType;
import com.inzent.apim.utilities.http.payload.HttpPayloadGenerator;

/**
 * Copies generated entity bytes before the generator's callback closes its stream.
 */
public final class PayloadBody
		implements RequestBody {
	private final HttpPayloadGenerator generator;
	private final boolean chunked;
	private final ContentType type;
	private final ContentEncoding encoding;
	private final long length;
	private final long blockSize;

	public PayloadBody(HttpPayloadGenerator generator,
	                   boolean chunked,
	                   ContentType type,
	                   ContentEncoding encoding,
	                   long length,
	                   long blockSize) {
		this.generator = Objects.requireNonNull(generator);
		this.chunked = chunked;
		this.type = Objects.requireNonNull(type);
		this.encoding = Objects.requireNonNull(encoding);
		this.length = length;
		this.blockSize = blockSize;
	}

	@Override
	public HttpHeaders prepare(BodyStore target) throws IOException {
		HttpHeaders.Builder headers = HttpHeaders.builder();
		try {
			this.generator.generate(this.chunked,
			                        this.type,
			                        this.encoding,
			                        this.length,
			                        this.blockSize,
			                        (generated, input) -> {
				                        generated.forEach(entry -> headers.add(entry.getKey(),
				                                                               entry.getValue()));
				                        try {
					                        input.transferTo(target);
				                        }
				                        catch (IOException exception) {
					                        throw new UncheckedIOException(exception);
				                        }
			                        });
		}
		catch (UncheckedIOException exception) {
			throw exception.getCause();
		}
		return headers.build();
	}
}
