/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.collect.ImmutableSet.of;

/**
 * Provides access to frequently used system properties.
 */
public class SystemProperties {
    private static final Set<String> STANDARD_PROPERTIES = of(
            "java.version",
            "java.vendor",
            "java.vendor.url",
            "java.home",
            "java.vm.specification.version",
            "java.vm.specification.vendor",
            "java.vm.specification.name",
            "java.vm.version",
            "java.vm.vendor",
            "java.vm.name",
            "java.specification.version",
            "java.specification.vendor",
            "java.specification.name",
            "java.class.version",
            "java.class.path",
            "java.library.path",
            "java.io.tmpdir",
            "java.compiler",
            "java.ext.dirs",
            "os.name",
            "os.arch",
            "os.version",
            "file.separator",
            "path.separator",
            "line.separator",
            "user.name",
            "user.home",
            "user.dir"
    );

    private static final Set<String> IMPORTANT_NON_STANDARD_PROPERTIES = of(
            "java.runtime.version"
    );

    private static final SystemProperties INSTANCE = new SystemProperties();
    private final Lock lock = new ReentrantLock();

    public static SystemProperties getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> asMap() {
        return (Map) System.getProperties();
    }

    public String getLineSeparator() {
        return System.getProperty("line.separator");
    }

    public String getJavaIoTmpDir() {
        return System.getProperty("java.io.tmpdir");
    }

    public String getUserHome() {
        return System.getProperty("user.home");
    }

    public String getUserName() {
        return System.getProperty("user.name");
    }

    public String getJavaVersion() {
        return System.getProperty("java.version");
    }

    public File getCurrentDir() {
        return new File(System.getProperty("user.dir"));
    }

    public File getJavaHomeDir() {
        lock.lock();
        File javaHomeDir;
        try {
            javaHomeDir = new File(System.getProperty("java.home"));
        } finally {
            lock.unlock();
        }
        return javaHomeDir;
    }

    /**
     * Creates instance for Factory implementation with the provided Java home directory. Setting the "java.home" system property is thread-safe
     * and is set back to the original value of "java.home" after the operation.
     *
     * @param javaHomeDir Java home directory
     * @param factory Factory
     * @return Instance created by Factory implementation
     */
    public <T> T withJavaHome(File javaHomeDir, Factory<T> factory) {
        return withSystemProperty("java.home", javaHomeDir.getAbsolutePath(), factory);
    }

    /**
     * Creates an instance for a Factory implementation with a system property set to a given value.  Sets the system property back to the original value (or
     * clears it if it was never set) after the operation.
     *
     * @param propertyName The name of the property to set
     * @param value The value to temporarily set the property to
     * @param factory Instance created by the Factory implementation
     */
    public <T> T withSystemProperty(String propertyName, String value, Factory<T> factory) {
        lock.lock();
        String originalValue = System.getProperty(propertyName);
        System.setProperty(propertyName, value);

        try {
            return factory.create();
        } finally {
            if (originalValue != null) {
                System.setProperty(propertyName, originalValue);
            } else {
                System.clearProperty(propertyName);
            }
            lock.unlock();
        }
    }

    /**
     * Returns the keys that are guaranteed to be contained in System.getProperties() by default,
     * as specified in the Javadoc for that method.
     */
    public Set<String> getStandardProperties() {
        return STANDARD_PROPERTIES;
    }

    /**
     * Returns the names of properties that are not guaranteed to be contained in System.getProperties()
     * but are usually there and if there should not be adjusted.
     *
     * @return the set of keys of {@code System.getProperties()} which should not be adjusted
     *   by client code. This method never returns {@code null}.
     */
    public Set<String> getNonStandardImportantProperties() {
        return IMPORTANT_NON_STANDARD_PROPERTIES;
    }
}
