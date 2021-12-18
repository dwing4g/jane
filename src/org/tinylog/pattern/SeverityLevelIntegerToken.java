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
import java.util.Collection;
import java.util.Collections;

import org.tinylog.Level;
import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;

/**
 * Token for outputting the severity level of a log entry as integer.
 */
final class SeverityLevelIntegerToken implements Token {

	private static final int LEVEL_COUNT = Level.OFF.ordinal();

	/** */
	SeverityLevelIntegerToken() {
	}

	@Override
	public Collection<LogEntryValue> getRequiredLogEntryValues() {
		return Collections.singleton(LogEntryValue.LEVEL);
	}

	@Override
	public void render(final LogEntry logEntry, final StringBuilder builder) {
		builder.append(getReverseOfOrdinalAsLevelValue(logEntry));
	}

	@Override
	public void apply(final LogEntry logEntry, final PreparedStatement statement, final int index) throws SQLException {
		statement.setInt(index, getReverseOfOrdinalAsLevelValue(logEntry));
	}

	private int getReverseOfOrdinalAsLevelValue(final LogEntry logEntry) {
		return LEVEL_COUNT - logEntry.getLevel().ordinal();
	}

}
