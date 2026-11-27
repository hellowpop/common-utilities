package com.inzent.apim.utilities.filesplitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootConfiguration(proxyBeanMethods = false)
public class FileSplitterApplication {
	private static final String USAGE = """
		Usage:
		  java -jar file-spliter.jar split <regex> <output-directory> <file-or-glob> [<file-or-glob> ...]
		  java -jar file-spliter.jar analyze <regex> <split-directory> <result-directory>
		  java -jar file-spliter.jar --help""";

	public static void main(String[] args) {
		if (args.length == 1 && "--help".equals(args[0])) {
			System.out.println(USAGE);
			return;
		}
		boolean split = args.length >= 4 && "split".equals(args[0]);
		boolean analyze = args.length == 4 && "analyze".equals(args[0]);
		if (!split && !analyze) {
			System.err.println(USAGE);
			System.exit(2);
			return;
		}

		try {
			Pattern pattern = Pattern.compile(args[1]);
			Path directory = Path.of(args[2]);
			SpringApplicationBuilder application = new SpringApplicationBuilder(FileSplitterApplication.class);
			application.web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false);
			try (ConfigurableApplicationContext context = application.run()) {
				if (split) {
					List<Path> inputs = InputFiles.resolve(List.of(args).subList(3, args.length));
					context.getBean(FileSplitter.class).split(pattern, directory, inputs);
				}
				else {
					context.getBean(FileAnalyzer.class).analyze(pattern, directory, Path.of(args[3]));
				}
			}
		}
		catch (IllegalArgumentException exception) {
			System.err.println(exception.getMessage());
			System.exit(2);
		}
		catch (IOException exception) {
			System.err.println("File processing failed: " + exception);
			System.exit(1);
		}
	}

	@Bean
	FileSplitter fileSplitter() {
		return new FileSplitter();
	}

	@Bean
	FileAnalyzer fileAnalyzer() {
		return new FileAnalyzer();
	}
}
