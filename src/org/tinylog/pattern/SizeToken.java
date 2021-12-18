/*
 * Copyright 2021 Victor Kropp
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
import java.util.Collection;

import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;

/**
 * Decorator token for ensuring both minimum and maximum size. If the output of the underlying token is shorter
 * or longer than the defined size, start of the token will be trimmed or padded with spaces at the end.
 */
class SizeToken implements Token {

	private final Token token;
	private final int size;

	/**
	 * @param token
	 *            Base token
	 * @param size
	 *            Maximum size for output
	 */
	SizeToken(final Token token, final int size) {
		this.token = token;
		this.size = size;
	}

	@Override
	public Collection<LogEntryValue> getRequiredLogEntryValues() {
		return token.getRequiredLogEntryValues();
	}

	@Override
	public void render(final LogEntry logEntry, final StringBuilder builder) {
		int offset = builder.length();
		token.render(logEntry, builder);
		int size = builder.length() - offset;

		if (size > this.size) {
			builder.delete(offset, offset + size - this.size);
		}
		if (size < this.size) {
			for (int i = 0; i < this.size - size; ++i) {
				builder.append(' ');
			}
		}
	}
	
	@Override
	public void apply(final LogEntry logEntry, final PreparedStatement statement, final int index) throws SQLException {
		StringBuilder builder = new StringBuilder();
		render(logEntry, builder);
		statement.setString(index, builder.toString());
	}

}
