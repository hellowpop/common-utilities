package com.inzent.apim.utilities.http.client.spi;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;

/**
 * Reads concurrently so a final rejection can terminate a blocked upload without losing the response.
 */
public final class DuplexExchange {
	@FunctionalInterface
	public interface Action {
		void run() throws Exception;
	}

	private DuplexExchange() {
	}

	public static HttpResponsePayload run(ExecutionContext context,
	                                      ExecutorService readers,
	                                      Action send,
	                                      Callable<HttpResponsePayload> read,
	                                      Runnable abort) throws Exception {
		CompletableFuture<HttpResponsePayload> response = new CompletableFuture<>();
		CountDownLatch sendDone = new CountDownLatch(1);
		Future<?> reader = readers.submit(() -> {
			try {
				HttpResponsePayload result = read.call();
				response.complete(result);
				// Let the caller publish a concurrent successful flush before treating this as an early response.
				if (!sendDone.await(20,
				                    TimeUnit.MILLISECONDS)) {
					abort.run();
				}
			}
			catch (Throwable failure) {
				response.completeExceptionally(failure);
			}
		});
		Throwable sendFailure = null;
		try {
			try {
				send.run();
				context.sent(context.request()
				                    .body()
				                    .size());
			}
			catch (Throwable failure) {
				sendFailure = failure;
			}
			finally {
				sendDone.countDown();
			}
			try {
				HttpResponsePayload result = response.isDone()
				                             ? response.get()
				                             : response.get(context.remainingMillis(),
				                                            TimeUnit.MILLISECONDS);
				if (sendFailure instanceof Error error) {
					throw error;
				}
				if (sendFailure != null) {
					context.transition(ExecutionContext.State.RECEIVING,
					                   "SEND",
					                   "EARLY_FINAL_RESPONSE");
				}
				return result;
			}
			catch (java.util.concurrent.ExecutionException failure) {
				Throwable cause = failure.getCause();
				if (sendFailure != null) {
					sendFailure.addSuppressed(cause);
					throwFailure(sendFailure);
				}
				throwFailure(cause);
				throw new AssertionError("unreachable");
			}
		}
		catch (InterruptedException failure) {
			Thread.currentThread()
			      .interrupt();
			context.cancel();
			throw failure;
		}
		finally {
			sendDone.countDown();
			if (!reader.isDone()) {
				if (!response.isDone()) {
					abort.run();
				}
				try {
					reader.get(1000,
					           TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException interrupted) {
					reader.cancel(true);
					Thread.currentThread()
					      .interrupt();
				}
				catch (Exception ignored) {
					reader.cancel(true);
				}
			}
		}
	}

	private static void throwFailure(Throwable failure) throws Exception {
		if (failure instanceof Error error) {
			throw error;
		}
		if (failure instanceof Exception exception) {
			throw exception;
		}
		throw new IOException("HTTP exchange failed",
		                      failure);
	}
}
