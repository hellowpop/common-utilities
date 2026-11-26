package com.inzent.apim.utilities.filesplitter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class FileSplitter {
	private static final Pattern INVALID_FILE_NAME_CHARACTER = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
	private static final Pattern DEVICE_NAME = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]) *(\\..*)?");

	void split(Pattern pattern, Path outputDirectory, List<Path> inputs) throws IOException {
		Files.createDirectories(outputDirectory);
		try (OutputFiles outputs = new OutputFiles(outputDirectory)) {
			BufferedWriter current = null;
			for (Path input : inputs) {
				boolean gzip = input.toString().toLowerCase(Locale.ROOT).endsWith(".gz");
				try (InputStream source = Files.newInputStream(input);
					 BufferedReader reader = new BufferedReader(new InputStreamReader(gzip ? new GZIPInputStream(source) : source, StandardCharsets.UTF_8.newDecoder()))) {
					long lineNumber = 0;
					String line;
					Matcher matcher = pattern.matcher("");
					while ((line = reader.readLine()) != null) {
						lineNumber++;
						if (lineNumber == 1 && line.startsWith("\uFEFF")) {
							line = line.substring(1);
						}
						if (matcher.reset(line).find()) {
							String key = matcher.groupCount() == 0 ? matcher.group() : matcher.group(1);
							if (key == null || key.isBlank() || key.endsWith(".") || key.endsWith(" ")
								|| INVALID_FILE_NAME_CHARACTER.matcher(key).find() || DEVICE_NAME.matcher(key).matches()) {
								throw new IllegalArgumentException("Invalid output key at " + input + ":" + lineNumber);
							}
							current = outputs.writerFor(key);
						}
						if (current != null) {
							current.write(line);
							current.write('\n');
						}
					}
				}
			}
		}
	}

	private static final class OutputFiles implements AutoCloseable {
		private final Path directory;
		private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();

		private OutputFiles(Path directory) {
			this.directory = directory;
		}

		private BufferedWriter writerFor(String key) throws IOException {
			BufferedWriter writer = this.writers.get(key);
			if (writer == null) {
				Path path = this.directory.resolve(key + ".txt");
				writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				this.writers.put(key, writer);
			}
			return writer;
		}

		@Override
		public void close() throws IOException {
			IOException failure = null;
			for (BufferedWriter writer : this.writers.values()) {
				try {
					writer.close();
				}
				catch (IOException exception) {
					if (failure == null) {
						failure = exception;
					}
					else {
						failure.addSuppressed(exception);
					}
				}
			}
			if (failure != null) {
				throw failure;
			}
		}
	}
}
