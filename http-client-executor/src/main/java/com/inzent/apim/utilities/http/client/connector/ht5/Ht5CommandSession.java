package com.inzent.apim.utilities.http.client.connector.ht5;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.concurrent.locks.Lock;
import org.apache.hc.core5.http.nio.command.RequestExecutionCommand;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Timeout;

/**
 * Filters cancelled queued exchanges before HttpCore allocates their HTTP/2 stream and emits headers.
 */
final class Ht5CommandSession
		implements IOSession {
	private final IOSession delegate;

	Ht5CommandSession(IOSession delegate) {
		this.delegate = delegate;
	}

	@Override
	public Command poll() {
		while (true) {
			Command command = this.delegate.poll();
			if (command instanceof RequestExecutionCommand request && request.getCancellableDependency() != null && request.getCancellableDependency()
			                                                                                                               .isCancelled()) {
				request.cancel();
				continue;
			}
			return command;
		}
	}

	@Override
	public String getId() {
		return this.delegate.getId();
	}

	@Override
	public IOEventHandler getHandler() {
		return this.delegate.getHandler();
	}

	@Override
	public void upgrade(IOEventHandler handler) {
		this.delegate.upgrade(handler);
	}

	@Override
	public Lock getLock() {
		return this.delegate.getLock();
	}

	@Override
	public void enqueue(Command command,
	                    Command.Priority priority) {
		this.delegate.enqueue(command,
		                      priority);
	}

	@Override
	public boolean hasCommands() {
		return this.delegate.hasCommands();
	}

	@Override
	public ByteChannel channel() {
		return this.delegate.channel();
	}

	@Override
	public int read(ByteBuffer bytes) throws IOException {
		return this.delegate.read(bytes);
	}

	@Override
	public int write(ByteBuffer bytes) throws IOException {
		return this.delegate.write(bytes);
	}

	@Override
	public boolean isOpen() {
		return this.delegate.isOpen();
	}

	@Override
	public SocketAddress getRemoteAddress() {
		return this.delegate.getRemoteAddress();
	}

	@Override
	public SocketAddress getLocalAddress() {
		return this.delegate.getLocalAddress();
	}

	@Override
	public int getEventMask() {
		return this.delegate.getEventMask();
	}

	@Override
	public void setEventMask(int mask) {
		this.delegate.setEventMask(mask);
	}

	@Override
	public void setEvent(int operation) {
		this.delegate.setEvent(operation);
	}

	@Override
	public void clearEvent(int operation) {
		this.delegate.clearEvent(operation);
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public void close(CloseMode mode) {
		this.delegate.close(mode);
	}

	@Override
	public Status getStatus() {
		return this.delegate.getStatus();
	}

	@Override
	public Timeout getSocketTimeout() {
		return this.delegate.getSocketTimeout();
	}

	@Override
	public void setSocketTimeout(Timeout timeout) {
		this.delegate.setSocketTimeout(timeout);
	}

	@Override
	public long getInitiationTime() {
		return this.delegate.getInitiationTime();
	}

	@Override
	public long getLastReadTime() {
		return this.delegate.getLastReadTime();
	}

	@Override
	public long getLastWriteTime() {
		return this.delegate.getLastWriteTime();
	}

	@Override
	public long getLastEventTime() {
		return this.delegate.getLastEventTime();
	}

	@Override
	public void updateReadTime() {
		this.delegate.updateReadTime();
	}

	@Override
	public void updateWriteTime() {
		this.delegate.updateWriteTime();
	}

	@Override
	public int getPendingCommandCount() {
		return this.delegate.getPendingCommandCount();
	}
}
