package com.inzent.apim.utilities.filesplitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputFilesTest {
	@TempDir
	Path directory;

	@Test
	void expandsEachPatternInFileNameOrderAmongExplicitInputs() throws IOException {
		Path logs = Files.createDirectory(this.directory.resolve("log files"));
		Path second = Files.createFile(logs.resolve("acb-log2.txt"));
		Path first = Files.createFile(logs.resolve("acb-log1.txt"));
		Path other = Files.createFile(logs.resolve("other.txt"));
		Path explicit = this.directory.resolve("literal[1].log");
		Files.createDirectory(logs.resolve("acb-log3.txt"));

		List<Path> inputs = InputFiles.resolve(List.of(explicit.toString(), logs + File.separator + "acb-log*.txt", logs + "/other.tx?", first.toString()));

		Assertions.assertEquals(List.of(explicit, first, second, other, first), inputs);
	}

	@Test
	void rejectsPatternsWithoutMatchingFiles() throws IOException {
		Files.createDirectory(this.directory.resolve("directory.txt"));
		String selector = this.directory + File.separator + "*.txt";

		IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class, () -> InputFiles.resolve(List.of(selector)));

		Assertions.assertTrue(exception.getMessage().contains(selector));
	}

	@Test
	void rejectsWildcardsInDirectoryComponents() {
		String selector = this.directory + File.separator + "log*" + File.separator + "*.txt";

		Assertions.assertThrows(IllegalArgumentException.class, () -> InputFiles.resolve(List.of(selector)));
	}
}
