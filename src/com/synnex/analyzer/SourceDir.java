package com.synnex.analyzer;

import java.util.List;

class SourceDir {
	private String srcDir;
	private List<String> exclude;
	
	public SourceDir(String srcDir) {
		this(srcDir, List.of());
	}
	
	public SourceDir(String srcDir, List<String> exclude) {
		super();
		this.srcDir = srcDir;
		this.exclude = exclude;
	}
	public String getSrcDir() {
		return srcDir;
	}
	public void setSrcDir(String srcDir) {
		this.srcDir = srcDir;
	}
	public List<String> getExclude() {
		return exclude;
	}
	public void setExclude(List<String> exclude) {
		this.exclude = exclude;
	}

}