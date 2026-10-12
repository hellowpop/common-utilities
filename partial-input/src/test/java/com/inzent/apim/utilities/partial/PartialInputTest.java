package com.inzent.apim.utilities.partial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartialInputTest {
    @TempDir Path directory;

    @Test void closedStreamsReleaseOwnedReferencesAndKeepClosedContract() throws Exception {
        var info = List.of(block(0, 3));
        var array = new PartialByteArray(bytes("abc"), info);
        var file = new PartialFile(Files.write(directory.resolve("retention"), bytes("abc")), info);
        for (var stream : List.of(array.openStream(), file.openStream())) {
            stream.close();
            // Inspect the ownership edges directly: GC timing is not deterministic.
            for (String name : List.of("source", "selection", "channel")) {
                var field = PartialInputStream.class.getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(stream), "Closed stream retains " + name);
            }
            assertThrows(IOException.class, stream::read);
            assertThrows(IOException.class, () -> stream.read(new byte[0]));
            assertThrows(IOException.class, () -> stream.skip(0));
            assertThrows(IOException.class, stream::available);
            stream.close();
        }
        assertArrayEquals(bytes("abc"), array.shrink());
        file.shrink();
    }

    @Test void nullFileSelectionIsRejectedBeforeFileAccess() {
        var failure = assertThrows(NullPointerException.class,
                () -> new PartialFile(directory.resolve("missing"), null));
        assertEquals("partialInfo", failure.getMessage());
        assertThrows(NullPointerException.class, () -> new PartialFile((String) null, List.of()));
    }

    @Test void invalidFileSelectionDoesNotLeaveFileHandleOpen() throws Exception {
        Path file = Files.write(directory.resolve("invalid"), bytes("abc"));
        assertThrows(NullPointerException.class, () -> new PartialFile(file, Arrays.asList((Long) null)));
        assertThrows(IllegalArgumentException.class, () -> new PartialFile(file, List.of(block(3, 1))));
        Files.delete(file);
        assertFalse(Files.exists(file));
    }

    private static long block(int offset, int length) {
        return ((long) offset << 32) | (length & 0xffffffffL);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test void arrayShrinkPreservesOrderOverlapAndOriginal() throws Exception {
        byte[] source = bytes("0123456789");
        var info = new ArrayList<>(List.of(block(7, 3), block(1, 3), block(2, 2)));
        var partial = new PartialByteArray(source, info);
        info.clear();
        assertArrayEquals(bytes("78912323"), partial.shrink());
        assertArrayEquals(bytes("0123456789"), source);
        try (var stream = partial.openStream()) {
            assertArrayEquals(bytes("78912323"), stream.readAllBytes());
        }
        assertArrayEquals(bytes("78912323"), partial.shrink());
    }

    @Test void fileShrinkReplacesOriginalAndCanBeRepeated() throws Exception {
        Path file = Files.write(directory.resolve("data"), bytes("0123456789"));
        var partial = new PartialFile(file.toString(), List.of(block(7, 3), block(1, 3), block(2, 2)));
        try (var stream = partial.openStream()) {
            assertArrayEquals(bytes("78912323"), stream.readAllBytes());
        }
        assertEquals(file.toRealPath(), partial.shrink());
        assertArrayEquals(bytes("78912323"), Files.readAllBytes(file));
        try (var stream = partial.openStream()) {
            assertArrayEquals(bytes("78912323"), stream.readAllBytes());
        }
        partial.shrink();
        assertArrayEquals(bytes("78912323"), Files.readAllBytes(file));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void rejectsNegativeAndOutOfBoundsBlocksForBothSources() throws Exception {
        Path file = Files.write(directory.resolve("data"), new byte[10]);
        for (long invalid : new long[]{block(-1, 1), block(0, -1), block(9, 2),
                block(11, 0), block(Integer.MAX_VALUE, Integer.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> new PartialByteArray(new byte[10], List.of(invalid)));
            assertThrows(IllegalArgumentException.class, () -> new PartialFile(file, List.of(invalid)));
        }
    }

    @Test void rejectsNullInputsAndMissingOrDirectoryFiles() {
        assertThrows(NullPointerException.class, () -> new PartialByteArray(null, List.of()));
        assertThrows(NullPointerException.class, () -> new PartialByteArray(new byte[0], null));
        assertThrows(NullPointerException.class, () -> new PartialByteArray(new byte[0], Arrays.asList((Long) null)));
        assertThrows(NullPointerException.class, () -> new PartialFile((Path) null, List.of()));
        assertThrows(IOException.class, () -> new PartialFile(directory.resolve("absent"), List.of()));
        assertThrows(IOException.class, () -> new PartialFile(directory, List.of()));
    }

    @Test void emptyAndZeroLengthSelectionsProduceEmptyOutput() throws Exception {
        for (var info : List.of(List.<Long>of(), List.of(block(0, 0), block(10, 0)))) {
            var partial = new PartialByteArray(new byte[10], info);
            assertEquals(0, partial.shrink().length);
            try (var stream = partial.openStream()) {
                assertEquals(-1, stream.read());
                assertEquals(0, stream.read(new byte[0]));
                assertEquals(0, stream.skip(10));
            }
            Path file = Files.write(directory.resolve("empty"), new byte[10]);
            new PartialFile(file, info).shrink();
            assertEquals(0, Files.size(file));
        }
        assertEquals(0, new PartialByteArray(new byte[0], List.of(block(0, 0))).shrink().length);
    }

    @Test void streamsHonorReadSkipCloseAndIndependentPositions() throws Exception {
        byte[] source = new byte[]{0, (byte) 255, 2, 3, 4, 5};
        var info = List.of(block(6, 0), block(1, 2), block(0, 0), block(4, 2));
        var array = new PartialByteArray(source, info);
        var file = new PartialFile(Files.write(directory.resolve("data"), source), info);
        for (InputStream stream : List.of(array.openStream(), file.openStream())) {
            try (stream) {
                assertEquals(4, stream.available());
                assertEquals(255, stream.read());
                assertEquals(0, stream.skip(-1));
                assertEquals(1, stream.skip(1));
                byte[] buffer = new byte[6];
                assertEquals(2, stream.read(buffer, 2, 4));
                assertArrayEquals(new byte[]{0, 0, 4, 5, 0, 0}, buffer);
                assertEquals(-1, stream.read());
                assertEquals(0, stream.read(buffer, 6, 0));
                assertThrows(IndexOutOfBoundsException.class, () -> stream.read(buffer, 5, 2));
                assertThrows(NullPointerException.class, () -> stream.read(null, 0, 0));
                assertEquals(0, stream.available());
                assertFalse(stream.markSupported());
                assertThrows(IOException.class, stream::reset);
            }
            stream.close();
            assertThrows(IOException.class, stream::read);
        }
        try (var first = file.openStream(); var second = file.openStream()) {
            assertEquals(3, first.skip(3));
            assertEquals(255, second.read());
            assertEquals(5, first.read());
        }
        try (var stream = array.openStream()) { assertEquals(4, stream.skip(Long.MAX_VALUE)); }
    }

    @Test void bulkReadCrossesBlockBoundaries() throws Exception {
        var info = List.of(block(4, 2), block(1, 2), block(5, 0), block(0, 1));
        var array = new PartialByteArray(bytes("012345"), info);
        var file = new PartialFile(Files.write(directory.resolve("data"), bytes("012345")), info);
        for (var stream : List.of(array.openStream(), file.openStream())) {
            try (stream) {
                byte[] result = new byte[20];
                assertEquals(5, stream.read(result, 3, 10));
                assertArrayEquals(bytes("45120"), Arrays.copyOfRange(result, 3, 8));
            }
        }
    }

    @Test void truncatedFileRaisesEofAndFailedShrinkLeavesFileIntact() throws Exception {
        Path file = Files.write(directory.resolve("data"), bytes("0123456789"));
        var partial = new PartialFile(file, List.of(block(5, 5)));
        try (var stream = partial.openStream()) {
            Files.write(file, bytes("012345"));
            assertThrows(EOFException.class, stream::readAllBytes);
        }
        assertThrows(EOFException.class, partial::shrink);
        assertArrayEquals(bytes("012345"), Files.readAllBytes(file));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void supportsFileRangesBeyondSignedIntegerSum() throws Exception {
        Path file = directory.resolve("large");
        try (var out = new RandomAccessFile(file.toFile(), "rw")) {
            out.seek(Integer.MAX_VALUE);
            out.write(new byte[]{42, 43});
        }
        var partial = new PartialFile(file, List.of(block(Integer.MAX_VALUE, 2)));
        try (var stream = partial.openStream()) {
            assertArrayEquals(new byte[]{42, 43}, stream.readAllBytes());
        }
        partial.shrink();
        assertArrayEquals(new byte[]{42, 43}, Files.readAllBytes(file));
    }

    @Test void rejectsArrayOutputTooLargeBeforeAllocation() {
        byte[] source = new byte[65536];
        var info = java.util.Collections.nCopies(32768, block(0, source.length));
        var partial = new PartialByteArray(source, info);
        assertThrows(IllegalStateException.class, partial::shrink);
        try (var stream = partial.openStream()) {
            assertEquals(Integer.MAX_VALUE, stream.available());
            assertEquals(2147483648L, stream.skip(Long.MAX_VALUE));
        } catch (IOException e) { fail(e); }
    }
}
