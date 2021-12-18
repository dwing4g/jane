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

package org.tinylog.configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.tinylog.Level;
import org.tinylog.provider.InternalLogger;
import org.tinylog.runtime.RuntimeProvider;

/**
 * Alternative service loader that supports constructors with arguments in opposite to {@link java.util.ServiceLoader}.
 *
 * @param <T>
 *            Service interface
 */
public final class ServiceLoader<T> {

	private static final String SERVICE_PREFIX = "META-INF/services/";
	private static final Pattern SPLIT_PATTERN = Pattern.compile(" ");

	private final Class<? extends T> service;
	private final Class<?>[] argumentTypes;

	private final ClassLoader classLoader;
	private final Collection<String> classes;

	public static Function<Class<?>, Collection<String>> customLoader;

	/**
	 * @param service
	 *            Service interface
	 * @param argumentTypes
	 *            Expected argument types for constructors
	 */
	public ServiceLoader(final Class<? extends T> service, final Class<?>... argumentTypes) {
		this.service = service;
		this.argumentTypes = argumentTypes;

		Collection<String> names;
		if (customLoader != null && (names = customLoader.apply(service)) != null) {
			classes = names;
			classLoader = customLoader.getClass().getClassLoader();
			return;
		}

		String fileName = SERVICE_PREFIX + service.getName();
		for (ClassLoader loader : RuntimeProvider.getClassLoaders()) {
			Enumeration<URL> serviceFiles = fetchServiceFiles(loader, fileName);
			if (serviceFiles.hasMoreElements()) {
				classLoader = loader;
				classes = loadClasses(loader, service);
				return;
			}
		}

		this.classLoader = null;
		this.classes = Collections.emptyList();
	}

	/**
	 * Creates a defined service implementation. The name can be either the fully-qualified class name or the simplified
	 * acronym. The acronym is the class name without package and service suffix.
	 *
	 * <p>
	 * The acronym for {@code org.tinylog.writers.RollingFileWriter} is for example {@code rolling file}.
	 * </p>
	 *
	 * @param name
	 *            Acronym or class name of service implementation
	 * @param arguments
	 *            Arguments for constructor of service implementation
	 * @return A new instance of service or {@code null} if failed to create service
	 */
	public T create(final String name, final Object... arguments) {
		if (name.indexOf('.') == -1) {
			String expectingClassName = toSimpleClassName(name);
			for (String className : classes) {
				int split = className.lastIndexOf('.');
				String simpleClassName = split == -1 ? className : className.substring(split + 1);
				if (expectingClassName.equals(simpleClassName)) {
					return createInstance(className, arguments);
				}
			}

			InternalLogger.log(Level.ERROR, "Service implementation '" + name + "' not found");
			return null;
		} else {
			return createInstance(name, arguments);
		}
	}

	/**
	 * Creates service implementations from a comma separated list of names. Names can be either fully-qualified class names or their
	 * simplified acronyms. An acronym is the class name without package and service suffix. Optionally, each name can be enriched by a
	 * string argument. There must be a colon between the name and argument. All service implementations need a constructor that accepts
	 * a string as argument.
	 *
	 * @param list Comma separated list of names with optional arguments
	 * @return All created service implementations (one for each name)
	 */
	public List<T> createList(final String list) {
		List<T> instances = new ArrayList<T>();
		for (String entry : list.split(",")) {
			entry = entry.trim();
			if (!entry.isEmpty()) {
				T instance;

				int separator = entry.indexOf(':');
				if (separator == -1) {
					instance = create(entry, (Object) null);
				} else {
					String name = entry.substring(0, separator).trim();
					String argument = entry.substring(separator + 1).trim();
					instance = create(name, argument);
				}

				if (instance != null) {
					instances.add(instance);
				}
			}
		}
		return instances;
	}

	/**
	 * Creates all registered service implementations.
	 *
	 * @param arguments
	 *            Arguments for constructors of service implementations
	 * @return Instances of all service implementations
	 */
	public Collection<T> createAll(final Object... arguments) {
		Collection<T> instances = new ArrayList<T>(classes.size());

		for (String className : classes) {
			T instance = createInstance(className, arguments);
			if (instance != null) {
				instances.add(instance);
			}
		}

		return instances;
	}

	private static Enumeration<URL> fetchServiceFiles(final ClassLoader classLoader, final String fileName) {
		try {
			return classLoader.getResources(fileName);
		} catch (IOException ex) {
			InternalLogger.log(Level.ERROR, "Failed loading services from '" + fileName + "'");
			return Collections.enumeration(Collections.<URL>emptyList());
		}
	}

	/**
	 * Loads all registered service class names.
	 *
	 * @param <T>
	 *            Service interface
	 * @param classLoader
	 *             Class loader to use for finding service files
	 * @param service
	 *            Service interface
	 * @return Class names
	 */
	private static <T> Collection<String> loadClasses(final ClassLoader classLoader, final Class<? extends T> service) {
		String name = SERVICE_PREFIX + service.getName();
		Enumeration<URL> urls;
		try {
			urls = classLoader.getResources(name);
		} catch (IOException ex) {
			InternalLogger.log(Level.ERROR, "Failed loading services from '" + name + "'");
			return Collections.emptyList();
		}

		Collection<String> classes = new ArrayList<String>();

		while (urls.hasMoreElements()) {
			URL url = urls.nextElement();
			BufferedReader reader = null;

			try {
				reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					line = line.trim();
					if (line.length() > 0 && line.charAt(0) != '#' && !classes.contains(line)) {
						classes.add(line);
					}
				}
			} catch (IOException ex) {
				InternalLogger.log(Level.ERROR, "Failed reading service resource '" + url + "'");
			} finally {
				if (reader != null) {
					try {
						reader.close();
					} catch (IOException ex) {
						// Ignore
					}
				}
			}
		}

		return classes;
	}

	/**
	 * Generates the simple class name from an acronym. A simple class name is the class name without package.
	 *
	 * <p>
	 * The acronym {@code rolling file}, for example, will be transformed into {@code RollingFileWriter} for the service
	 * interface {@code Writer}.
	 * </p>
	 *
	 * @param name
	 *            Simplified acronym
	 * @return Simple class without package
	 */
	public String toSimpleClassName(final String name) {
		StringBuilder builder = new StringBuilder(name.length());
		for (String token : SPLIT_PATTERN.split(name)) {
			if (!token.isEmpty()) {
				builder.append(Character.toUpperCase(token.charAt(0)));
				builder.append(token.substring(1).toLowerCase(Locale.ROOT));
			}
		}
		builder.append(service.getSimpleName());
		return builder.toString();
	}

	/**
	 * Creates a new instance of a class.
	 *
	 * @param className
	 *            Fully-qualified class name
	 * @param arguments
	 *            Arguments for constructor
	 * @return A new instance of given class or {@code null} if creation failed
	 */
	@SuppressWarnings("unchecked")
	private T createInstance(final String className, final Object... arguments) {
		try {
			Class<?> implementation = Class.forName(className, false, classLoader);
			if (service.isAssignableFrom(implementation)) {
				return (T) implementation.getDeclaredConstructor(argumentTypes).newInstance(arguments);
			} else {
				InternalLogger.log(Level.ERROR, "Class '" + className + "' does not implement service interface '" + service + "'");
			}
		} catch (ClassNotFoundException ex) {
			InternalLogger.log(Level.ERROR, "Service implementation '" + className + "' not found");
		} catch (NoSuchMethodException ex) {
			InternalLogger.log(Level.ERROR, "Service implementation '" + className + "' has no matching constructor");
		} catch (InstantiationException ex) {
			InternalLogger.log(Level.ERROR, "Service implementation '" + className + "' is not instantiable");
		} catch (IllegalAccessException ex) {
			InternalLogger.log(Level.ERROR, "Constructor of service implementation '" + className + "' is not accessible");
		} catch (IllegalArgumentException ex) {
			InternalLogger.log(Level.ERROR, "Illegal arguments for constructor of service implementation '" + className + "'");
		} catch (InvocationTargetException ex) {
			InternalLogger.log(Level.ERROR, ex.getTargetException(), "Failed creating service implementation '" + className + "'");
		}

		return null;
	}

}

