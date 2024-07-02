package com.synnex.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;

public class JavaSourceAnalyzer {
	private static final String RESULT_FILE_NAME = "result.txt";
	static final Logger logger = LoggerFactory.getLogger(JavaSourceAnalyzer.class);
	static final String WAR = "CG2";
	static final String BS = "DBS";
	static final String FILE_EXT = ".java";
	private final int targetTables;
	private Set<String> skipSet;
	private List<SourceDir> srcDirs;
	private List<Path> libPaths;
	private int fileIndex = 0;
	private int exceptionCount = 0;
	private boolean insertAll = false;

	public JavaSourceAnalyzer(Initer initer) {
		if(initer.ignoreFile != null) {
			try(var stream = Files.lines(initer.ignoreFile)) {
				//skip first 2 line because those are SET statements.
				skipSet = stream.skip(2).collect(Collectors.toSet());
			} catch (IOException e) {
				logger.warn("exception on read skip file " + initer.ignoreFile, e);
				increaseExceptonCount();
				skipSet = Set.of();
			}
		}else {
			skipSet = Set.of();
		}
		srcDirs = initer.srcDirs;
		libPaths = initer.libPaths();
		targetTables = initer.getTargetTables();
	}

	public static void main(String[] args) {
		com.github.javaparser.utils.Log.setAdapter(new LogAdapter(logger));
		Initer initer = new Initer();
		JCommander.newBuilder().addObject(initer).build().parse(args);
		JavaSourceAnalyzer runner= new JavaSourceAnalyzer(initer);
		try {
			runner.run();
		} catch (Throwable e) {
			logger.error("error at ", e);
			logger.error("total running file: {}", runner.fileIndex);
			logger.error("total exception occor: {}", runner.exceptionCount);
		}
	}

	private static Iterator<String> convertToInsertAllWithBatching(Set<String> insertStatements, int batchSize) {
		// Calculate the number of batches
		long numberOfBatches = (long) Math.ceil((double) insertStatements.size() / batchSize);

		return LongStream.range(0, numberOfBatches)
				.mapToObj(i -> insertStatements.stream().skip(i * batchSize).limit(batchSize)
						.map(sql -> sql.substring("INSERT ".length(), sql.length() - 1))
						.collect(Collectors.joining("\r\n", "INSERT ALL\r\n", "\r\nSELECT 1 FROM DUAL;")))
				.iterator();
	}

	public void run() throws IOException {
		Instant start = Clock.systemDefaultZone().instant();
		Set<String> totalSqlStatements = new HashSet<>();
		ParserConfiguration fileParserConfig = new ParserConfiguration();
		fileParserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.RAW);
		ParserConfiguration fileSolverConfig = new ParserConfiguration();
		fileSolverConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_6);

		// First pass: Collect fully qualified names of all classes
		Set<Path> srcPaths = new HashSet<>();
		for(SourceDir srcDir : srcDirs) {
			Instant fileStart = Clock.systemDefaultZone().instant();
			if(logger.isTraceEnabled()) logger.trace("start collecting {}", srcDir.getSrcDir());
			try (var files = Files.walk(Paths.get(srcDir.getSrcDir()))) {
				files.filter(Files::isRegularFile)
						.filter(path -> srcDir.getExclude().stream()
								.map(s -> srcDir.getSrcDir() + "\\" + s)
								.noneMatch(path::startsWith))
						.filter(path -> path.toString().endsWith(FILE_EXT))
						.forEach(srcPaths::add);
			}
			if (logger.isTraceEnabled())
				logger.trace("end collecting {}\t running time: {}", srcDir.getSrcDir(),
						Duration.between(fileStart, Clock.systemDefaultZone().instant()));
		}
		logger.info("collected file list.");

		logger.info("total java file: {}", srcPaths.size());

		// Set up the TypeSolver with JAR dependencies
		CombinedTypeSolver typeSolver = new CombinedTypeSolver();
		for (Path jarPath : libPaths) {
			try (var files = Files.walk(jarPath)) {
				files.filter(Files::isRegularFile)
						.filter(path -> path.toString().endsWith("jar"))
						.forEach(path -> {
					try {
						typeSolver.add(new JarTypeSolver(path));
					} catch (IOException e) {
						logger.warn("exception on add jar to solver " + path, e);
						increaseExceptonCount();
					}
				});
			}
		}
		logger.info("added jar file to type solver.");
		for (SourceDir srcDir : srcDirs) {
			typeSolver.add(new JavaParserTypeSolver(Paths.get(srcDir.getSrcDir()), fileSolverConfig));
		}
		logger.info("added src file to type solver.");
		srcPaths.stream().map(path -> parse(fileParserConfig, typeSolver, path)).forEach(totalSqlStatements::addAll);
		logger.info("parsed file list.");

		logger.info("total sql statements: {}", totalSqlStatements.size());

		// Print SQL statements
		Files.writeString(Path.of(RESULT_FILE_NAME), "SET AUTOCOMMIT ON;\r\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		Files.writeString(Path.of(RESULT_FILE_NAME), "SET NLS_LANG=AMERICAN_AMERICA.UTF8;\r\n", StandardOpenOption.APPEND, StandardOpenOption.WRITE);
		
		if (insertAll) {
			Files.write(Path.of(RESULT_FILE_NAME),
					(Iterable<String>) () -> convertToInsertAllWithBatching(totalSqlStatements, 999),
					StandardOpenOption.APPEND, StandardOpenOption.WRITE);
		} else {
			Files.write(Path.of(RESULT_FILE_NAME), (Iterable<String>) totalSqlStatements.stream().sorted()::iterator,
					StandardOpenOption.APPEND, StandardOpenOption.WRITE);
		}
		Files.writeString(Path.of(RESULT_FILE_NAME), "EXIT", StandardOpenOption.APPEND, StandardOpenOption.WRITE);

		Instant end = Clock.systemDefaultZone().instant();
		logger.info("end parse.");
		logger.info("start: {}", start);
		logger.info("end: {}", end);
		logger.info("total: {}", Duration.between(start, end));
		logger.info("total exception occor: {}", exceptionCount);
	}

	private Set<String> parse(ParserConfiguration fileParserConfig, CombinedTypeSolver typeSolver,
			Path path) {
		try {
			Instant fileStart = Clock.systemDefaultZone().instant();
			if(logger.isTraceEnabled()) logger.trace("start parsing {}", path);
			var result = new JavaParser(fileParserConfig).parse(path);
			if(result.isSuccessful()) {
				// Generate SQL statements
				CompilationUnit cu = result.getResult().orElseThrow();
				Set<String> sqlStatements = new HashSet<>();
				SQLGenerator generator = new SQLGenerator(typeSolver, targetTables, srcDirs, skipSet);
				exceptionCount += generator.getExceptionCount();
				cu.accept(generator, sqlStatements);
				if (logger.isTraceEnabled())
					logger.trace("end parsing {}\t running time: {}", path,
							Duration.between(fileStart, Clock.systemDefaultZone().instant()));
				fileIndex++;
				return sqlStatements;
			}else {
				logger.warn("{} parse fail: {}", path, result.getProblems());
				return Set.of();
			}
		} catch (IOException e) {
			logger.warn("exception on parseing " + path, e);
			increaseExceptonCount();
			return Set.of();
		}
	}

	private void increaseExceptonCount() {
		exceptionCount++;
	}
}
