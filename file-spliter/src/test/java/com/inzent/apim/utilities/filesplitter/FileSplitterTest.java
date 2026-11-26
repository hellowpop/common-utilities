package com.inzent.apim.utilities.filesplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSplitterTest {
	@TempDir
	Path directory;

	@Test
	void reusesOutputAndCarriesTheLastKeyAcrossFiles() throws IOException {
		Path first = Files.writeString(this.directory.resolve("one.log"), "머리말\r\nkey=A\r\n본문\r\nkey=B\r\n");
		Path second = Files.writeString(this.directory.resolve("two.log"), "이어지는 줄\nkey=A\n\n마지막 줄");
		Path output = this.directory.resolve("output");

		new FileSplitter().split(Pattern.compile("^key=(\\w+)"), output, List.of(first, second));

		Assertions.assertEquals("key=A\n본문\nkey=A\n\n마지막 줄\n", Files.readString(output.resolve("A.txt")));
		Assertions.assertEquals("key=B\n이어지는 줄\n", Files.readString(output.resolve("B.txt")));
	}

	@Test
	void splitsGzipAndPlainInputsInOrder() throws IOException {
		Path first = Files.writeString(this.directory.resolve("one.log"), "key=A\n일반 입력\n");
		Path compressed = this.directory.resolve("two.log.GZ");
		try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(compressed))) {
			gzip.write("\uFEFF압축 파일 첫 줄\r\nkey=B\r\n압축 본문".getBytes(StandardCharsets.UTF_8));
		}
		Path last = Files.writeString(this.directory.resolve("three.log"), "일반 파일 마지막 줄");
		Path output = this.directory.resolve("output");

		new FileSplitter().split(Pattern.compile("^key=(\\w+)"), output, List.of(first, compressed, last));

		Assertions.assertEquals("key=A\n일반 입력\n압축 파일 첫 줄\n", Files.readString(output.resolve("A.txt")));
		Assertions.assertEquals("key=B\n압축 본문\n일반 파일 마지막 줄\n", Files.readString(output.resolve("B.txt")));
	}

	@Test
	void failsWhenGzipInputHasAnInvalidHeader() throws IOException {
		Path first = Files.writeString(this.directory.resolve("one.log"), "key=A\n보존할 내용\n");
		Path invalid = Files.writeString(this.directory.resolve("broken.gz"), "key=B\nnot gzip\n");
		Path output = this.directory.resolve("output");

		Assertions.assertThrows(ZipException.class, () -> new FileSplitter().split(Pattern.compile("^key=(\\w+)"), output, List.of(first, invalid)));

		Assertions.assertEquals("key=A\n보존할 내용\n", Files.readString(output.resolve("A.txt")));
		Assertions.assertFalse(Files.exists(output.resolve("B.txt")));
	}

	@Test
	void ignoresUtf8BomAtTheStartOfEveryInput() throws IOException {
		Path first = Files.writeString(this.directory.resolve("one.log"), "\uFEFFkey=A\n");
		Path second = Files.writeString(this.directory.resolve("two.log"), "\uFEFF본문\nkey=B\n");
		Path output = this.directory.resolve("output");

		new FileSplitter().split(Pattern.compile("^key=(.+)"), output, List.of(first, second));

		Assertions.assertEquals("key=A\n본문\n", Files.readString(output.resolve("A.txt")));
		Assertions.assertEquals("key=B\n", Files.readString(output.resolve("B.txt")));
	}

	@Test
	void usesTheFirstWholeMatchWhenThereAreNoCaptureGroups() throws IOException {
		Path input = Files.writeString(this.directory.resolve("input.log"), "prefix ID1 ID2\nnext\n");
		Path output = this.directory.resolve("output");

		new FileSplitter().split(Pattern.compile("ID[0-9]+"), output, List.of(input));

		Assertions.assertEquals("prefix ID1 ID2\nnext\n", Files.readString(output.resolve("ID1.txt")));
		Assertions.assertFalse(Files.exists(output.resolve("ID2.txt")));
	}

	@Test
	void createsNoFilesWithoutAMatch() throws IOException {
		Path input = Files.writeString(this.directory.resolve("input.log"), "only text");
		Path output = this.directory.resolve("output");

		new FileSplitter().split(Pattern.compile("^key=(.+)"), output, List.of(input));

		try (Stream<Path> files = Files.list(output)) {
			Assertions.assertEquals(0, files.count());
		}
	}

	@Test
	void preservesExistingFilesAndFlushesOpenOutputsOnFailure() throws IOException {
		Path input = Files.writeString(this.directory.resolve("input.log"), "key=A\nbody\nkey=B\n");
		Path output = Files.createDirectory(this.directory.resolve("output"));
		Path existing = Files.writeString(output.resolve("B.txt"), "existing\n");

		Assertions.assertThrows(FileAlreadyExistsException.class, () -> new FileSplitter().split(Pattern.compile("^key=(.+)"), output, List.of(input)));

		Assertions.assertEquals("existing\n", Files.readString(existing));
		Assertions.assertEquals("key=A\nbody\n", Files.readString(output.resolve("A.txt")));
	}

	@Test
	void rejectsUnsafeOrEmptyKeys() throws IOException {
		Path input = this.directory.resolve("input.log");
		Path output = this.directory.resolve("output");
		Pattern pattern = Pattern.compile("^key=(.*)");

		for (String key : List.of("../escape", "sub\\file", "NUL", "COM1.log", "name.", "")) {
			Files.writeString(input, "key=" + key);
			Assertions.assertThrows(IllegalArgumentException.class, () -> new FileSplitter().split(pattern, output, List.of(input)), key);
		}
		Assertions.assertFalse(Files.exists(this.directory.resolve("escape.txt")));
	}
}
