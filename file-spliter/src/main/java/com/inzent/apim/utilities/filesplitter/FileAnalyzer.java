package com.inzent.apim.utilities.filesplitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FileAnalyzer {
	void analyze(Pattern pattern, Path inputDirectory, Path resultDirectory) throws IOException {
		try (DirectoryStream<Path> inputs = Files.newDirectoryStream(inputDirectory)) {
			Files.createDirectories(resultDirectory);
			if (Files.isSameFile(inputDirectory, resultDirectory)) {
				throw new IllegalArgumentException("Split and result directories must be different.");
			}

			for (Path input : inputs) {
				if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) && this.containsMatch(pattern, input)) {
					Files.copy(input, resultDirectory.resolve(input.getFileName()));
				}
			}
		}
		catch (DirectoryIteratorException exception) {
			throw exception.getCause();
		}
	}

	private boolean containsMatch(Pattern pattern, Path input) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
			reader.mark(1);
			if (reader.read() != '\uFEFF') {
				reader.reset();
			}

			Matcher matcher = pattern.matcher("");
			String line;
			while ((line = reader.readLine()) != null) {
				if (matcher.reset(line).find()) {
					return true;
				}
			}
			return false;
		}
	}
}
