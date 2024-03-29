/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.mina.core.session;

import java.util.Set;

/**
 * Stores the user-defined attributes which is provided per {@link IoSession}.
 * All user-defined attribute accesses in {@link IoSession} are forwarded to the instance of {@link IoSessionAttributeMap}.
 */
public interface IoSessionAttributeMap {
	/**
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key))
	 * 	   return getAttribute(key);
	 * else
	 *     return defaultValue;
	 * </pre>
	 *
	 * @param key          The key we are looking for
	 * @param defaultValue The default returned value if the attribute is not found
	 * @return the value of user defined attribute associated with the specified key.
	 * 		If there's no such attribute, the default value is returned.
	 */
	Object getAttribute(Object key, Object defaultValue);

	/**
	 * Sets a user-defined attribute.
	 *
	 * @param key   the key of the attribute
	 * @param value the value of the attribute
	 * @return The old value of the attribute. <tt>null</tt> if it is new.
	 */
	Object setAttribute(Object key, Object value);

	/**
	 * Sets a user defined attribute if the attribute with the specified key is not set yet.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key))
	 *     return getAttribute(key);
	 * else
	 *     return setAttribute(key, value);
	 * </pre>
	 *
	 * @param key   The key we are looking for
	 * @param value The value to inject
	 * @return The previous attribute
	 */
	Object setAttributeIfAbsent(Object key, Object value);

	/**
	 * Removes a user-defined attribute with the specified key.
	 *
	 * @param key The key we are looking for
	 * @return The old value of the attribute.  <tt>null</tt> if not found.
	 */
	Object removeAttribute(Object key);

	/**
	 * Removes a user defined attribute with the specified key if the current
	 * attribute value is equal to the specified value.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key) &amp;&amp; getAttribute(key).equals(value)) {
	 *     removeAttribute(key);
	 *     return true;
	 * } else
	 *     return false;
	 * </pre>
	 *
	 * @param key   The key we are looking for
	 * @param value The value to remove
	 * @return <tt>true</tt> if the value has been removed,
	 * 		<tt>false</tt> if the key was not found of the value not removed
	 */
	boolean removeAttribute(Object key, Object value);

	/**
	 * Replaces a user defined attribute with the specified key if the
	 * value of the attribute is equals to the specified old value.
	 * This method is same with the following code except that the operation is performed atomically.
	 * <pre>
	 * if (containsAttribute(key) &amp;&amp; getAttribute(key).equals(oldValue)) {
	 *     setAttribute(key, newValue);
	 *     return true;
	 * } else
	 *     return false;
	 * </pre>
	 *
	 * @param key      The key we are looking for
	 * @param oldValue The old value to replace
	 * @param newValue The new value to set
	 * @return <tt>true</tt> if the value has been replaced,
	 * 		<tt>false</tt> if the key was not found of the value not replaced
	 */
	boolean replaceAttribute(Object key, Object oldValue, Object newValue);

	/**
	 * @param key The key we are looking for
	 * @return <tt>true</tt> if this session contains the attribute with the specified <tt>key</tt>.
	 */
	boolean containsAttribute(Object key);

	/** @return the set of keys of all user-defined attributes. */
	Set<Object> getAttributeKeys();
}
