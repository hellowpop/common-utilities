package mains;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;

import com.inzent.apim.utilities.http.curl.CurlHttpMockExecutor;
import com.inzent.apim.utilities.http.curl.ExecuteConfigure;
import com.inzent.apim.utilities.http.curl.ExecuteContext;
import com.inzent.apim.utilities.http.curl.ExecutionRecord;
import com.inzent.apim.utilities.http.curl.StageRecord;

public interface CallPatterns {
	class DirectCall {
		static final URI base = URI.create("http://127.0.0.1:9991/");
		static final Path temp;

		static {
			try {
				temp = Files.createTempDirectory("curl-http-mock-executor");
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		public static void main(String[] args) throws Throwable {
			final AtomicReference<Path> outHolder = new AtomicReference<>();
			final AtomicReference<Path> errHolder = new AtomicReference<>();
			final AtomicReference<Path> directoryHolder = new AtomicReference<>();
			InputStream retained;
			try (var c = context("/hello").requestHeader("X-Test",
			                                             "builder")
			                              .build()) {
				c.setPreExecute(() -> c.requestHeaders()
				                       .set("X-Test",
				                            "pre"));
				AtomicBoolean post = new AtomicBoolean();
				c.setPostExecute(() -> {
					post.set(true);
				});
				new CurlHttpMockExecutor().handle(c);
				assertTrue(post.get());
				assertEquals(4096,
				             c.record()
				              .request()
				              .stdinBytesWritten());
				assertEquals(ExecutionRecord.State.COMPLETED,
				             c.record()
				              .state());
				assertEquals(0,
				             c.record()
				              .exitCode()
				              .orElseThrow());
				assertEquals(200,
				             c.record()
				              .response()
				              .status()
				              .orElseThrow()
				              .code());
				assertEquals(StageRecord.Status.SUCCEEDED,
				             c.record()
				              .stage(ExecutionRecord.Stage.PRE)
				              .status());
				assertEquals(StageRecord.Status.SUCCEEDED,
				             c.record()
				              .stage(ExecutionRecord.Stage.POST)
				              .status());
				outHolder.set(c.record()
				               .stdout()
				               .orElseThrow()
				               .path());
				final Path out = outHolder.get();
				errHolder.set(c.record()
				               .stderr()
				               .orElseThrow()
				               .path());
				final Path err = errHolder.get();
				directoryHolder.set(out.getParent());

				final String outStr = Files.readString(out);

				System.out.println(outStr);

				assertTrue(Files.readString(err)
				                .contains("> POST / HTTP/1.1"));
				Assertions.assertNotEquals(out,
				                           err);
				retained = c.record()
				            .stdout()
				            .orElseThrow()
				            .openStream();
				assertThrows(IllegalStateException.class,
				             () -> new CurlHttpMockExecutor().handle(c));
			}
			final Path out = outHolder.get();
			final Path err = errHolder.get();
			final Path directory = directoryHolder.get();
			assertFalse(Files.exists(out));
			assertFalse(Files.exists(err));
			assertFalse(Files.exists(directory));
			assertThrows(IOException.class,
			             retained::read);
		}

		static ExecuteContext.Builder context(String path) {
			return ExecuteContext.builder()
			                     .url(base.resolve(path))
			                     .configure(ExecuteConfigure.builder()
			                                                .temporaryDirectory(temp)
			                                                .build());
		}
	}
}
