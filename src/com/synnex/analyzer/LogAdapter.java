package com.synnex.analyzer;

import java.util.function.Supplier;

import org.slf4j.Logger;

import com.github.javaparser.utils.Log.Adapter;

final class LogAdapter implements Adapter {
	final Logger logger;
	public LogAdapter(Logger logger) {
		this.logger = logger;
	}

	@Override
	public void info(Supplier<String> message) {
		logger.info(message.get());
	}

	@Override
	public void trace(Supplier<String> message) {
		logger.trace(message.get());
	}

	@Override
	public void error(Supplier<Throwable> throwableSupplier, Supplier<String> messageSupplier) {
		logger.error(messageSupplier.get(), throwableSupplier.get());
	}
}