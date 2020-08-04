/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2007 - 2020 ImageJ developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package net.imagej.launcher;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A classloader whose classpath can be augmented after instantiation.
 * 
 * @author Johannes Schindelin
 */
public class ClassLoaderPlus extends URLClassLoader {

	// A frozen ClassLoaderPlus will add only to the urls array
	protected static Set<ClassLoader> frozen = new HashSet<ClassLoader>();
	protected static Map<URLClassLoader, List<URL>> urlsMap = new HashMap<URLClassLoader, List<URL>>();
	protected static Method addURL;

	public static URLClassLoader getInImageJDirectory(final URLClassLoader classLoader,
		final String... relativePaths)
	{
		try {
			final File directory = new File(getImageJDir());
			final Set<URL> urls = new LinkedHashSet<URL>(relativePaths.length);
			for (final String path : relativePaths) {
				discoverDependencies(new File(directory, path), urls);
			}
			return get(classLoader, urls.toArray(new URL[urls.size()]));
		}
		catch (final Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Uh oh: " + e.getMessage());
		}
	}

	private static void discoverDependencies(final File jarFile, final Set<URL> result) {
		final File versioned = getPossiblyVersionedFile(jarFile);
		if (!versioned.exists()) return;
		try {
			final URL url = versioned.toURI().toURL();
			if (result.contains(url)) return;
			result.add(url);
			final Manifest manifest = new JarFile(versioned).getManifest();
			if (manifest == null) return;
			final String classPath = (String)manifest.getMainAttributes().get(Attributes.Name.CLASS_PATH);
			if (classPath == null) return;
			File directory = jarFile.getParentFile();
			if (directory == null) directory = jarFile.getAbsoluteFile().getParentFile();
			for (final String path : classPath.split(" +")) {
				discoverDependencies(new File(directory, path), result);
			}
		} catch (MalformedURLException e) {
			// ignore this class path element
		} catch (IOException e) {
			// ignore this class path element
		}
	}

	public static URLClassLoader get(final URLClassLoader classLoader, final File... files) {
		try {
			final URL[] urls = new URL[files.length];
			for (int i = 0; i < urls.length; i++) {
				urls[i] = files[i].toURI().toURL();
			}
			return get(classLoader, urls);
		}
		catch (final Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Uh oh: " + e.getMessage());
		}
	}

	public static URLClassLoader get(URLClassLoader classLoader, final URL... urls) {
		if (classLoader == null) {
			final ClassLoader currentClassLoader = ClassLoaderPlus.class.getClassLoader();
			if (currentClassLoader instanceof URLClassLoader) {
				classLoader = (URLClassLoader)currentClassLoader;
			} else {
				final ClassLoader contextClassLoader =
					Thread.currentThread().getContextClassLoader();
				if (contextClassLoader instanceof URLClassLoader) {
					classLoader = (URLClassLoader)contextClassLoader;
				}
			}
		}
		if (classLoader == null) return new ClassLoaderPlus(urls);
		for (final URL url : urls) {
			add(classLoader, url);
		}
		return classLoader;
	}

	public static URLClassLoader getRecursivelyInImageJDirectory(final URLClassLoader classLoader,
		final String... relativePaths)
	{
		return getRecursivelyInImageJDirectory(classLoader, false, relativePaths);
	}

	public static URLClassLoader getRecursivelyInImageJDirectory(URLClassLoader classLoader,
		final boolean onlyJars, final String... relativePaths)
	{
		try {
			final File directory = new File(getImageJDir());
			final File[] files = new File[relativePaths.length];
			for (int i = 0; i < files.length; i++)
				classLoader =
					getRecursively(classLoader, onlyJars, new File(directory, relativePaths[i]));
			return classLoader;
		}
		catch (final Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Uh oh: " + e.getMessage());
		}
	}

	public static URLClassLoader getRecursively(final URLClassLoader classLoader, final File directory) {
		return getRecursively(classLoader, false, directory);
	}

	public static URLClassLoader getRecursively(URLClassLoader classLoader, final boolean onlyJars,
		final File directory)
	{
		try {
			if (!onlyJars)
				classLoader = get(classLoader, directory);
			final File[] list = directory.listFiles();
			if (list != null) {
				/*
				Note that this code is replicated in ij1-patcher's LegacyHooks class.
				Improvements to this Pattern string should also be mirrored there.
				*/
				final Pattern pattern =
						Pattern.compile("(batik|jython|jython-standalone|jruby)(-[0-9].*)?\\.jar");
				Arrays.sort(list, new FatJarFileComparator(pattern));
				for (final File file : list) {
					if (file.isDirectory()) classLoader = getRecursively(classLoader, onlyJars, file);
					else if (file.getName().endsWith(".jar")) classLoader = get(classLoader, file);
				}
			}
			return classLoader;
		}
		catch (final Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Uh oh: " + e.getMessage());
		}
	}

	public ClassLoaderPlus() {
		this(new URL[0]);
	}

	public ClassLoaderPlus(final URL... urls) {
		super(urls, Thread.currentThread().getContextClassLoader());
		Thread.currentThread().setContextClassLoader(this);
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder();
		builder.append(getClass().getName()).append("(");
		for (final URL url : getURLs()) {
			builder.append(" ").append(url.toString());
		}
		builder.append(" )");
		return builder.toString();
	}

	public static void addInImageJDirectory(final URLClassLoader classLoader, final String relativePath) {
		try {
			add(classLoader, new File(getImageJDir(), relativePath));
		}
		catch (final Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Uh oh: " + e.getMessage());
		}
	}

	public static void add(final URLClassLoader classLoader, final String path) throws MalformedURLException {
		add(classLoader, new File(path));
	}

	public static void add(final URLClassLoader classLoader, final File file) throws MalformedURLException {
		add(classLoader, file.toURI().toURL());
	}

	public static void add(final URLClassLoader classLoader, final URL url) {
		List<URL> urls = urlsMap.get(classLoader);
		if (urls == null) {
			urls = new ArrayList<URL>();
			urlsMap.put(classLoader, urls);
		}
		urls.add(url);
		if (!frozen.contains(classLoader)) {
			if (classLoader instanceof ClassLoaderPlus) {
				((ClassLoaderPlus)classLoader).addURL(url);
			}
			else try {
				if (addURL == null) {
					addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
					addURL.setAccessible(true);
				}
				addURL.invoke(classLoader, url);
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}
	}

	public static void freeze(final ClassLoader classLoader) {
		frozen.add(classLoader);
	}

	public static String getClassPath(final ClassLoader classLoader) {
		List<URL> urls = urlsMap.get(classLoader);
		if (urls == null) return "";

		final StringBuilder builder = new StringBuilder();
		String sep = "";
		for (final URL url : urls)
			if (url.getProtocol().equals("file")) {
				builder.append(sep).append(url.getPath());
				sep = File.pathSeparator;
			}
		return builder.toString();
	}

	public static String getImageJDir() throws ClassNotFoundException {
		String path = System.getProperty("ij.dir");
		if (path != null) return path;
		final String prefix = "file:";
		final String suffix = "/jars/imagej-launcher.jar!/imagej/ClassLoaderPlus.class";
		path =
			Class.forName("net.imagej.launcher.ClassLoaderPlus")
				.getResource("ClassLoaderPlus.class").getPath();
		if (path.startsWith(prefix)) path = path.substring(prefix.length());
		if (path.endsWith(suffix)) {
			path = path.substring(0, path.length() - suffix.length());
		}
		return path;
	}

	public static String getJarPath(final ClassLoader classLoader, final String className) {
		try {
			final Class<?> clazz = classLoader.loadClass(className);
			String path =
				clazz.getResource("/" + className.replace('.', '/') + ".class")
					.getPath();
			if (path.startsWith("file:")) path = path.substring(5);
			final int bang = path.indexOf("!/");
			if (bang > 0) path = path.substring(0, bang);
			return path;
		}
		catch (final Throwable t) {
			t.printStackTrace();
			return null;
		}
	}

	// keep this synchronized with net.imagej.updater.FileObject
	private static Pattern versionPattern = Pattern.compile("(.+?)(-\\d+(\\.\\d+)+[a-z]?(-[A-Za-z0-9.]+|\\.GA)*)(\\.jar)");

	public static File getPossiblyVersionedFile(final File file) {
		if (file.exists()) return file;

		final String baseName;
		final Matcher matcher = versionPattern.matcher(file.getName());
		if (matcher.matches())
			baseName = matcher.group(1);
		else if (file.getName().endsWith(".jar"))
			baseName = file.getName().substring(0, file.getName().length() - 4);
		else
			return file;

		File[] list = file.getParentFile().listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				if (!name.startsWith(baseName)) return false; // quicker than regex matching
				final Matcher matcher = versionPattern.matcher(name);
				return matcher.matches() && matcher.group(1).equals(baseName);
			}
		});
		if (list == null || list.length < 1) return file;
		if (list.length == 1) return list[0];

		int newest = 0;
		System.err.println("Warning: " + file.getName() + " matched multiple versions:");
		for (int i = 0; i < list.length; i++) {
			System.err.println("\t" + list[i].getName());
			if (i > 0 && list[newest].lastModified() < list[i].lastModified()) newest = i;
		}
		System.err.println("Picking " + list[newest]);
		return list[newest];
	}

	/**
	 * Comparator to ensure that problematic fat JARs are sorted <em>last</em>.
	 * It is intended to be used with a {@link Pattern} that filters things this
	 * way.
	 */
	public final static class FatJarFileComparator implements Comparator<File> {

		private final Pattern pattern;

		private FatJarFileComparator(Pattern pattern) {
			this.pattern = pattern;
		}

		@Override
		public int compare(final File a, final File b) {
			return (pattern.matcher(a.getName()).matches() ? 1 : 0) -
				(pattern.matcher(b.getName()).matches() ? 1 : 0);
		}
	}

}
