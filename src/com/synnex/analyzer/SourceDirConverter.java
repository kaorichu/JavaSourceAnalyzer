package com.synnex.analyzer;

import com.beust.jcommander.IStringConverter;

class SourceDirConverter implements IStringConverter<SourceDir> {
	@Override
	public SourceDir convert(String value) {
		return new SourceDir(value);
	}
}