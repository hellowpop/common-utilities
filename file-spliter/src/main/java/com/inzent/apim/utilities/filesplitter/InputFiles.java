package com.inzent.apim.utilities.filesplitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class InputFiles {
	private InputFiles() {
	}

	static List<Path> resolve(List<String> selectors) throws IOException {
		List<Path> inputs = new ArrayList<>();
		for (String selector : selectors) {
			if (hasWildcard(selector)) {
				inputs.addAll(match(selector));
			}
			else {
				inputs.add(Path.of(selector));
			}
		}
		return inputs;
	}

	private static List<Path> match(String selector) throws IOException {
		int fileNameStart = selector.lastIndexOf('/') + 1;
		if (File.separatorChar == '\\') {
			fileNameStart = Math.max(fileNameStart, selector.lastIndexOf('\\') + 1);
			if (fileNameStart == 0 && selector.length() >= 2 && selector.charAt(1) == ':') {
				fileNameStart = 2;
			}
		}
		String directoryName = selector.substring(0, fileNameStart);
		if (hasWildcard(directoryName)) {
			throw new IllegalArgumentException("Wildcards are only supported in the file name: " + selector);
		}

		Path directory = Path.of(directoryName.isEmpty() ? "." : directoryName);
		String pattern = selector.substring(fileNameStart);
		List<Path> matches = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, pattern)) {
			for (Path entry : entries) {
				if (Files.isRegularFile(entry)) {
					matches.add(entry);
				}
			}
		}
		catch (DirectoryIteratorException exception) {
			throw new IOException("Could not expand input pattern: " + selector, exception);
		}
		if (matches.isEmpty()) {
			throw new IllegalArgumentException("No files match input pattern: " + selector);
		}
		matches.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return matches;
	}

	private static boolean hasWildcard(String selector) {
		return selector.indexOf('*') >= 0 || selector.indexOf('?') >= 0;
	}
}
