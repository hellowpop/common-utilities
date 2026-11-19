package com.inzent.apim.utilities.http.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered headers. Lookup is case insensitive.
 */
public final class HttpHeaders {
	private final List<Entry> entries;

	private HttpHeaders(List<Entry> entries) {
		this.entries = List.copyOf(entries);
	}

	public static Builder builder() {
		return new Builder();
	}

	public List<Entry> entries() {
		return this.entries;
	}

	public List<String> values(String name) {
		return this.entries.stream()
		                   .filter(entry -> entry.name()
		                                         .equalsIgnoreCase(name))
		                   .map(Entry::value)
		                   .toList();
	}

	public String first(String name) {
		return this.values(name)
		           .stream()
		           .findFirst()
		           .orElse(null);
	}

	public boolean contains(String name) {
		return this.first(name) != null;
	}

	public Builder toBuilder() {
		return new Builder().addAll(this);
	}

	public HttpHeaders redacted() {
		Builder builder = builder();
		for (Entry entry : this.entries) {
			boolean secret = List.of("authorization",
			                         "proxy-authorization",
			                         "cookie",
			                         "set-cookie")
			                     .contains(entry.name()
			                                    .toLowerCase(java.util.Locale.ROOT));
			builder.add(entry.name(),
			            secret
			            ? "***"
			            : entry.value());
		}
		return builder.build();
	}

	public record Entry(String name,
	                    String value) {
	}

	public static final class Builder {
		private final List<Entry> entries = new ArrayList<>();

		public Builder add(String name,
		                   String value) {
			ConnectMethod.of(name);
			Objects.requireNonNull(value,
			                       "value");
			for (int i = 0; i < value.length(); i++) {
				char character = value.charAt(i);
				if ((character < 32 && character != '\t') || character == 127 || character > 255) {
					throw new IllegalArgumentException("Invalid HTTP header value");
				}
			}
			this.entries.add(new Entry(name,
			                           value));
			return this;
		}

		public Builder set(String name,
		                   String value) {
			this.remove(name);
			return this.add(name,
			                value);
		}

		public Builder remove(String name) {
			this.entries.removeIf(entry -> entry.name()
			                                    .equalsIgnoreCase(name));
			return this;
		}

		public Builder addAll(HttpHeaders headers) {
			this.entries.addAll(headers.entries());
			return this;
		}

		public HttpHeaders build() {
			return new HttpHeaders(this.entries);
		}
	}
}
