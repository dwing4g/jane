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

import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;

/**
 * Token for outputting the simple name of the class in which a log entry has been issued.
 *
 * <p>
 * A simple class name is the class name without package prefix.
 * </p>
 */
final class SimpleClassNameToken implements Token {

	/** */
	SimpleClassNameToken() {
	}

	@Override
	public Collection<LogEntryValue> getRequiredLogEntryValues() {
		return Collections.singleton(LogEntryValue.CLASS);
	}

	@Override
	public void render(final LogEntry logEntry, final StringBuilder builder) {
		builder.append(getSimpleClassName(logEntry.getClassName()));
	}
	
	@Override
	public void apply(final LogEntry logEntry, final PreparedStatement statement, final int index) throws SQLException {
		statement.setString(index, getSimpleClassName(logEntry.getClassName()));
	}

	/**
	 * Gets the simple class name from a fully qualified class name.
	 * 
	 * @param fullyQualifiedClassName
	 *            Fully qualified class name
	 * @return Class name without package prefix
	 */
	private static String getSimpleClassName(final String fullyQualifiedClassName) {
		if (fullyQualifiedClassName == null) {
			return null;
		}		
		int dotIndex = fullyQualifiedClassName.lastIndexOf('.');
		if (dotIndex < 0) {
			return fullyQualifiedClassName;
		} else {
			return fullyQualifiedClassName.substring(dotIndex + 1);
		}
	}

}
