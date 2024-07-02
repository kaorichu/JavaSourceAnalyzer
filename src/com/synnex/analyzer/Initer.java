package com.synnex.analyzer;

import java.nio.file.Path;
import java.util.List;

import com.beust.jcommander.Parameter;

class Initer {
	public static final int BS_SOURCE_LIST = 1 << 3;
	public static final int BS_SOURCE_RELATION = 1 << 2;
	public static final int BS_SOURCE_METHODS = 1 << 1;
	public static final int BS_SOURCE_CALL_METHODS = 1 << 0;
	public static final int ALL = BS_SOURCE_LIST | BS_SOURCE_RELATION | BS_SOURCE_METHODS | BS_SOURCE_CALL_METHODS;
	public static final String ROOT_SRC_DIR = "C:\\BS source\\cg\\UAT - backup2\\src";
	public static final String LIB_DIR = "\\..\\WebContent\\WEB-INF\\lib";
	private int targetTables;
	@Parameter(names="-l")
	boolean list;

	@Parameter(names="-r")
	boolean relation;

	@Parameter(names="-m")
	boolean method;

	@Parameter(names="-c")
	boolean callMethod;
	
	@Parameter(names="-im")
	boolean insertAll;

	@Parameter(names="-e")
	Path ignoreFile;

	@Parameter(names="-jre")
	Path jrePath = Path.of("C:\\Program Files\\Java\\jdk1.6.0_45\\jre\\lib");

	@Parameter(names="-src", converter = SourceDirConverter.class)
	List<SourceDir> srcDirs = List.of(new SourceDir(ROOT_SRC_DIR),
			new SourceDir(ROOT_SRC_DIR + "2\\core"),
			new SourceDir(ROOT_SRC_DIR + "2\\dao"), new SourceDir(ROOT_SRC_DIR + "2\\report"),
			new SourceDir(ROOT_SRC_DIR + "2\\service"), new SourceDir(ROOT_SRC_DIR + "2\\web"),
			new SourceDir(ROOT_SRC_DIR + "..\\test\\dao"), new SourceDir(ROOT_SRC_DIR + "..\\test\\web"),
			new SourceDir(ROOT_SRC_DIR + "..\\test\\service"));
	
	@Parameter(names="-lib")
	Path libPath = Path.of(LIB_DIR);

	public List<Path> libPaths() {
		return  List.of(
				Path.of(srcDirs.get(0).getSrcDir()).resolve(libPath),
				jrePath.resolve("\\resources.jar"),
				jrePath.resolve("\\rt.jar"),
				jrePath.resolve("\\jsse.jar"),
				jrePath.resolve("\\jce.jar"),
				jrePath.resolve("\\charsets.jar"),
				jrePath.resolve("\\ext\\dnsns.jar"),
				jrePath.resolve("\\ext\\localedata.jar"),
				jrePath.resolve("\\ext\\sunjce_provider.jar"),
				jrePath.resolve("\\ext\\sunmscapi.jar"));
	}

	public int getTargetTables() {
		if(list) {
			targetTables |= Initer.BS_SOURCE_LIST;
		}
		if(relation) {
			targetTables |= Initer.BS_SOURCE_RELATION;
		}
		if(method) {
			targetTables |= Initer.BS_SOURCE_METHODS;
		}
		if(callMethod) {
			targetTables |= Initer.BS_SOURCE_CALL_METHODS;
		}
		if(targetTables == 0) {
			targetTables = Initer.ALL;
		}
		return targetTables;
	}
}