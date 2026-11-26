package com.inzent.apim.utilities.filesplitter;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAnalyzerTest {
	@TempDir
	Path directory;

	@Test
	void copiesMatchingFilesIntactAndSkipsNestedFiles() throws IOException {
		Path input = Files.createDirectory(this.directory.resolve("split files"));
		Path matched = Files.writeString(input.resolve("A.txt"), "\uFEFFERROR 처음\r\nERROR 다음\r\n마지막");
		Path laterMatch = Files.writeString(input.resolve("B.txt"), "key=B\nINFO 정상\nprefix ERROR 처리 실패\n");
		Files.writeString(input.resolve("C.txt"), "INFO 정상\n");
		Path nested = Files.createDirectory(input.resolve("nested"));
		Files.writeString(nested.resolve("D.txt"), "ERROR 하위 파일\n");
		Path output = this.directory.resolve("result files");

		new FileAnalyzer().analyze(Pattern.compile("^(?:prefix )?ERROR"), input, output);

		Assertions.assertArrayEquals(Files.readAllBytes(matched), Files.readAllBytes(output.resolve("A.txt")));
		Assertions.assertArrayEquals(Files.readAllBytes(laterMatch), Files.readAllBytes(output.resolve("B.txt")));
		Assertions.assertFalse(Files.exists(output.resolve("C.txt")));
		Assertions.assertFalse(Files.exists(output.resolve("D.txt")));
		Assertions.assertFalse(Files.exists(output.resolve("nested")));
	}

	@Test
	void preservesAnExistingResultFile() throws IOException {
		Path input = Files.createDirectory(this.directory.resolve("split"));
		Files.writeString(input.resolve("A.txt"), "ERROR");
		Path output = Files.createDirectory(this.directory.resolve("result"));
		Path existing = Files.writeString(output.resolve("A.txt"), "existing");

		Assertions.assertThrows(FileAlreadyExistsException.class, () -> new FileAnalyzer().analyze(Pattern.compile("ERROR"), input, output));

		Assertions.assertEquals("existing", Files.readString(existing));
	}

	@Test
	void rejectsTheSourceDirectoryAsTheResultDirectory() throws IOException {
		Assertions.assertThrows(IllegalArgumentException.class,
			() -> new FileAnalyzer().analyze(Pattern.compile("ERROR"), this.directory, this.directory.resolve(".")));
	}
}
