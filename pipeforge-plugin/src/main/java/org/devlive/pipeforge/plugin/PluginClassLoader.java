/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Child-first class loader of one plugin version. Classes of the plugin and its libraries win
 * over the engine's, except for the parent-first packages: the JDK, the connector API and error
 * codes, SLF4J and JSpecify. Those must be the same classes in the engine and in every plugin.
 */
public final class PluginClassLoader extends URLClassLoader {

    /** Packages always loaded from the parent. */
    public static final List<String> PARENT_FIRST = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.devlive.pipeforge.api.",
            "org.devlive.pipeforge.common.",
            "org.slf4j.",
            "org.jspecify.");

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String plugin;

    public PluginClassLoader(String plugin, URL[] urls, ClassLoader parent) {
        super("plugin:" + plugin, urls, parent);
        this.plugin = plugin;
    }

    /** {@code id@version} of the plugin. */
    public String plugin() {
        return plugin;
    }

    static boolean parentFirst(String name) {
        for (String prefix : PARENT_FIRST) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (parentFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException e) {
                    loaded = getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        if (parentFirst(name.replace('/', '.'))) {
            return super.getResource(name);
        }
        URL url = findResource(name);
        return url != null ? url : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>(Collections.list(findResources(name)));
        urls.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(urls);
    }

    @Override
    public String toString() {
        return "PluginClassLoader[" + plugin + "]";
    }
}
