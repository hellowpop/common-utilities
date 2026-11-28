package com.inzent.apim.utilities.http.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.cfg.MapperBuilder;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

final class ConnectionConfigureFile {

	private static final ObjectReader JSON = reader(JsonMapper.builder(JsonFactory.builder()
	                                                                              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
	                                                                              .build()));
	private static final ObjectReader YAML = reader(YAMLMapper.builder(YAMLFactory.builder()
	                                                                              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
	                                                                              .build()));

	private ConnectionConfigureFile() {
	}

	static ConnectionConfigure load(Path file) throws IOException {
		Path name = file.getFileName();
		String filename = name == null
		                  ? ""
		                  : name.toString()
		                        .toLowerCase(Locale.ROOT);
		ObjectReader reader;
		if (filename.endsWith(".json")) {
			reader = JSON;
		} else if (filename.endsWith(".yml") || filename.endsWith(".yaml")) {
			reader = YAML;
		} else {
			throw new IOException("Connection configuration file must use a .json, .yml or .yaml extension: " + file);
		}
		try (InputStream input = Files.newInputStream(file)) {
			ConnectionConfigure settings = reader.readValue(input);
			if (settings == null) {
				throw new IOException("Connection configuration must be an object: " + file);
			}
			return settings;
		}
	}

	private static ObjectReader reader(MapperBuilder<?, ?> mapper) {
		return mapper.addMixIn(ConnectionConfigure.class,
		                       ConfigureBinding.class)
		             .addMixIn(ConnectionConfigure.Builder.class,
		                       BuilderBinding.class)
		             .addMixIn(ProxyConfigure.class,
		                       ProxyBinding.class)
		             .addModule(new SimpleModule().addDeserializer(Duration.class,
		                                                           new DurationDeserializer()))
		             .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		             .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
		             .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
		                     DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES,
		                     DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
		                     DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
		                     DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		             .build()
		             .readerFor(ConnectionConfigure.class);
	}

	@JsonDeserialize(builder = ConnectionConfigure.Builder.class)
	private abstract static class ConfigureBinding {
	}

	@JsonPOJOBuilder(withPrefix = "")
	@JsonIgnoreProperties("sslContext")
	private abstract static class BuilderBinding {
	}

	private abstract static class ProxyBinding {
		@JsonCreator
		ProxyBinding(
				@JsonProperty(value = "host",
				              required = true) String host,
				@JsonProperty(value = "port",
				              required = true) int port,
				@JsonProperty("user") String user,
				@JsonProperty("password") String password) {
		}
	}

	private static final class DurationDeserializer
			extends JsonDeserializer<Duration> {
		@Override
		public Duration deserialize(JsonParser parser,
		                            DeserializationContext context) throws IOException {
			if (parser.hasToken(JsonToken.VALUE_STRING)) {
				try {
					return Duration.parse(parser.getText());
				}
				catch (DateTimeParseException invalid) {
					return context.reportInputMismatch(Duration.class,
					                                   "Expected an ISO-8601 duration");
				}
			}
			return context.reportInputMismatch(Duration.class,
			                                   "Expected an ISO-8601 duration string");
		}
	}
}
