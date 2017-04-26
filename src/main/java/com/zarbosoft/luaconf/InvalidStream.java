package com.zarbosoft.luaconf;

public class InvalidStream extends RuntimeException {

	public InvalidStream(final Object path, final String string) {
		super(String.format("%s\n%s", string, path));
	}
}
