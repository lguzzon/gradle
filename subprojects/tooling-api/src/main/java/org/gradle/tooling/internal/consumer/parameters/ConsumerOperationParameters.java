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

package org.gradle.tooling.internal.consumer.parameters;

import org.gradle.tooling.ProgressListener;
import org.gradle.tooling.internal.consumer.ConnectionParameters;
import org.gradle.tooling.internal.protocol.BuildOperationParametersVersion1;
import org.gradle.tooling.internal.protocol.ProgressListenerVersion1;
import org.gradle.util.Jvm;
import org.gradle.util.internal.StrictJavaLocaliser;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * by Szczepan Faber, created at: 1/9/12
 */
public class ConsumerOperationParameters implements BuildOperationParametersVersion1 {

    private final ProgressListenerAdapter progressListener = new ProgressListenerAdapter();
    private final ConnectionParameters parameters;
    private final long startTime = System.currentTimeMillis();

    private OutputStream stdout;
    private OutputStream stderr;
    private InputStream stdin;

    private File javaHome;
    private List<String> jvmArguments;

    public ConsumerOperationParameters(ConnectionParameters parameters) {
        this.parameters = parameters;
    }

    public void setStandardOutput(OutputStream outputStream) {
        stdout = outputStream;
    }

    public void setStandardError(OutputStream outputStream) {
        stderr = outputStream;
    }

    public void setStandardInput(InputStream inputStream) {
        stdin = inputStream;
    }

    public void addProgressListener(ProgressListener listener) {
        progressListener.add(listener);
    }

    public void setJavaHome(File javaHome) {
        validateJavaHome(javaHome);
        this.javaHome = javaHome;
    }

    private void validateJavaHome(File javaHome) {
        if (javaHome == null) {
            return;
        }
        if (!javaHome.isDirectory()) {
            throw new IllegalArgumentException("Supplied javaHome is not a valid folder. You supplied: " + javaHome);
        }
        try {
            Jvm.forHome(javaHome).getJavaExecutable();
        } catch (StrictJavaLocaliser.JavaExecutableNotFoundException e) {
            throw new IllegalArgumentException(
                "Supplied javaHome does not seem to be a valid java location. You supplied: " + javaHome + "."
                        + "I could not find the java executable in that location.", e);
        }
    }

    public void setJvmArguments(String... jvmArguments) {
        this.jvmArguments = jvmArguments != null ? Arrays.asList(jvmArguments) : null;
    }

    public long getStartTime() {
        return startTime;
    }

    public boolean getVerboseLogging() {
        return parameters.getVerboseLogging();
    }

    public File getGradleUserHomeDir() {
        return parameters.getGradleUserHomeDir();
    }

    public File getProjectDir() {
        return parameters.getProjectDir();
    }

    public Boolean isSearchUpwards() {
        return parameters.isSearchUpwards();
    }

    public Boolean isEmbedded() {
        return parameters.isEmbedded();
    }

    public TimeUnit getDaemonMaxIdleTimeUnits() {
        return parameters.getDaemonMaxIdleTimeUnits();
    }

    public Integer getDaemonMaxIdleTimeValue() {
        return parameters.getDaemonMaxIdleTimeValue();
    }

    public OutputStream getStandardOutput() {
        return stdout;
    }

    public OutputStream getStandardError() {
        return stderr;
    }

    public ProgressListenerVersion1 getProgressListener() {
        return progressListener;
    }

    public InputStream getStandardInput() {
        return stdin;
    }

    public File getJavaHome() {
        return javaHome;
    }

    public List<String> getJvmArguments() {
        return jvmArguments;
    }
}
