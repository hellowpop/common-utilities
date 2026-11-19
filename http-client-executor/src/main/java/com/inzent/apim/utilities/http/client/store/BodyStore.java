package com.inzent.apim.utilities.http.client.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;

/**
 * Execution-owned bounded store. Streams must be closed before their context.
 */
public final class BodyStore
		extends OutputStream {
	private final ConnectionConfigure configure;
	private ByteArrayOutputStream memory = new ByteArrayOutputStream();
	private OutputStream output = this.memory;
	private Path file;
	private long size;
	private boolean sealed;
	private boolean closed;

	public BodyStore(ConnectionConfigure configure) {
		this.configure = configure;
	}

	@Override
	public synchronized void write(int value) throws IOException {
		this.write(new byte[]{(byte) value},
		           0,
		           1);
	}

	@Override
	public synchronized void write(byte[] bytes,
	                               int offset,
	                               int length) throws IOException {
		java.util.Objects.checkFromIndexSize(offset,
		                                     length,
		                                     bytes.length);
		if (this.closed || this.sealed) {
			throw new IOException("Body store is no longer writable");
		}
		if (length > this.configure.maxStoredBytes() - this.size) {
			throw new IOException("Body storage limit exceeded");
		}
		if (this.file == null && this.size + length > this.configure.memoryThreshold()) {
			this.file = this.configure.temporaryDirectory() == null
			            ? Files.createTempFile("http-execution-",
			                                   ".bin")
			            : Files.createTempFile(this.configure.temporaryDirectory(),
			                                   "http-execution-",
			                                   ".bin");
			this.output = Files.newOutputStream(this.file);
			this.memory.writeTo(this.output);
			this.memory = null;
		}
		this.output.write(bytes,
		                  offset,
		                  length);
		this.size += length;
	}

	public synchronized void seal() throws IOException {
		if (!this.sealed) {
			this.output.close();
			this.sealed = true;
		}
	}

	public synchronized InputStream openStream() throws IOException {
		if (this.closed) {
			throw new IOException("Body store is closed");
		}
		this.seal();
		return this.file == null
		       ? new ByteArrayInputStream(this.memory.toByteArray())
		       : Files.newInputStream(this.file);
	}

	public byte[] bytes(long maximum) throws IOException {
		if (this.size() > maximum || this.size() > Integer.MAX_VALUE - 8L) {
			throw new IOException("Array body limit exceeded");
		}
		try (InputStream input = this.openStream()) {
			return input.readAllBytes();
		}
	}

	public synchronized long size() {
		return this.size;
	}

	public synchronized boolean fileBacked() {
		return this.file != null;
	}

	@Override
	public synchronized void close() throws IOException {
		if (this.closed && this.file == null) {
			return;
		}
		IOException failure = null;
		try {
			this.seal();
		}
		catch (IOException exception) {
			failure = exception;
		}
		finally {
			this.sealed = true;
			this.closed = true;
			this.memory = null;
			this.output = OutputStream.nullOutputStream();
		}
		if (this.file != null) {
			try {
				Files.deleteIfExists(this.file);
				this.file = null;
			}
			catch (IOException exception) {
				if (failure == null) {
					failure = exception;
				} else {
					failure.addSuppressed(exception);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
