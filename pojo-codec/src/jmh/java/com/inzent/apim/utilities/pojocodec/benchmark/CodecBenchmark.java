package com.inzent.apim.utilities.pojocodec.benchmark;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.inzent.apim.utilities.pojocodec.PojoCodecController;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5,
        time = 1)
@Measurement(iterations = 5,
             time = 1)
@Fork(value = 2,
      jvmArgsAppend = {"-Xms512m",
                       "-Xmx512m"})
public class CodecBenchmark {
	@Param({"flat",
	        "nested",
	        "numeric"})
	public String workload;
	private final PojoCodecController codec = new PojoCodecController();
	private final ByteArrayOutputStream output = new ByteArrayOutputStream(16384);
	private Object bean;
	private Class<?> type;
	private ObjectWriter writer;
	private ObjectReader reader;
	private byte[] binary;
	private byte[] json;

	public static class Flat {
		public long id = 123456789L;
		public int count = 42;
		public boolean active = true;
		public double amount = 12345.67;
		public String name = "홍길동 / benchmark";
		public String description = "Reusable metadata and bounded binary frames";
	}

	public static class Nested {
		public long id = 17;
		public String name = "nested";
		public List<Flat> items = new ArrayList<>();
	}

	public static class Numeric {
		public long id = 23;
		public int[] samples = new int[1024];
	}

	@Setup
	public void setup() throws Exception {
		switch (workload) {
			case "flat" -> bean = new Flat();
			case "nested" -> {
				Nested value = new Nested();
				for (int i = 0; i < 16; i++) {
					Flat item = new Flat();
					item.id += i;
					value.items.add(item);
				}
				bean = value;
			}
			case "numeric" -> {
				Numeric value = new Numeric();
				for (int i = 0; i < value.samples.length; i++) {
					value.samples[i] = i * 12347 - 5000000;
				}
				bean = value;
			}
			default -> throw new IllegalArgumentException(workload);
		}
		type = bean.getClass();
		ObjectMapper mapper = new ObjectMapper();
		mapper.setVisibility(PropertyAccessor.ALL,
		                     JsonAutoDetect.Visibility.NONE);
		mapper.setVisibility(PropertyAccessor.FIELD,
		                     JsonAutoDetect.Visibility.ANY);
		mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
		mapper.getFactory()
		      .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
		writer = mapper.writerFor(type);
		reader = mapper.readerFor(type);
		codec.serialize(output,
		                bean);
		binary = output.toByteArray();
		output.reset();
		writer.writeValue(output,
		                  bean);
		json = output.toByteArray();
		output.reset();
		Object binaryCopy = codec.deserialize(new ByteArrayInputStream(binary),
		                                      type);
		Object jsonCopy = reader.readValue(json);
		// Compare content, not implementation metadata/wire size.
		if (!mapper.valueToTree(binaryCopy)
		           .equals(mapper.valueToTree(jsonCopy))) {
			throw new IllegalStateException("Different restored values");
		}
	}

	@Benchmark
	public int pojoSerialize() throws Exception {
		output.reset();
		codec.serialize(output,
		                bean);
		return output.size();
	}

	@Benchmark
	public int jacksonSerialize() throws Exception {
		output.reset();
		writer.writeValue(output,
		                  bean);
		return output.size();
	}

	@Benchmark
	public Object pojoDeserialize() throws Exception {
		return codec.deserialize(new ByteArrayInputStream(binary),
		                         type);
	}

	@Benchmark
	public Object jacksonDeserialize() throws Exception {
		return reader.readValue(new ByteArrayInputStream(json));
	}
}
