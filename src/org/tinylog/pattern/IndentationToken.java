/*
 * Copyright 2016 Martin Winandy
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.tinylog.pattern;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;

import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;

/**
 * Decorator token for indenting new lines. Each new line, which is produced by the underlying token, will be indented
 * by a defined number of spaces.
 */
class IndentationToken implements Token {

	private static final int INITIAL_CAPACITY = 1024;
	private static final String NEW_LINE = System.getProperty("line.separator");

	private final Token token;
	private final char[] spaces;

	/**
	 * @param token
	 *            Base token
	 * @param indentation
	 *            Number of spaces to indent new lines
	 */
	IndentationToken(final Token token, final int indentation) {
		this.token = token;
		this.spaces = new char[indentation];
		Arrays.fill(spaces, ' ');
	}

	@Override
	public Collection<LogEntryValue> getRequiredLogEntryValues() {
		return token.getRequiredLogEntryValues();
	}

	@Override
	public void render(final LogEntry logEntry, final StringBuilder builder) {
		StringBuilder source = new StringBuilder(INITIAL_CAPACITY);
		token.render(logEntry, source);

		int head = 0;
		for (int i = source.indexOf(NEW_LINE, head); i != -1; i = source.indexOf(NEW_LINE, head)) {
			builder.append(source, head, i + NEW_LINE.length());
			head = i + NEW_LINE.length();
			if (head < source.length()) {
				builder.append(spaces);
				while (head < source.length() && source.charAt(head) == '\t') {
					builder.append(spaces);
					++head;
				}
			}
		}

		builder.append(source, head, source.length());
	}
	
	@Override
	public void apply(final LogEntry logEntry, final PreparedStatement statement, final int index) throws SQLException {
		StringBuilder builder = new StringBuilder();
		render(logEntry, builder);
		statement.setString(index, builder.toString());
	}

}
