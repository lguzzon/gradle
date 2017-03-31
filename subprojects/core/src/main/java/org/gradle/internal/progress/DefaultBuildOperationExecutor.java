/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.internal.progress;

import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.internal.Transformers;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.GradleThread;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultBuildOperationExecutor implements BuildOperationExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildOperationExecutor.class);

    private final BuildOperationListener listener;
    private final TimeProvider timeProvider;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final AtomicLong nextId = new AtomicLong();
    private final ThreadLocal<OperationDetails> currentOperation = new ThreadLocal<OperationDetails>();

    public DefaultBuildOperationExecutor(BuildOperationListener listener, TimeProvider timeProvider, ProgressLoggerFactory progressLoggerFactory) {
        this.listener = listener;
        this.timeProvider = timeProvider;
        this.progressLoggerFactory = progressLoggerFactory;
    }

    @Override
    public Operation getCurrentOperation() {
        Operation current = currentOperation.get();
        if (current == null) {
            if (GradleThread.isManaged()) {
                throw new IllegalStateException("No operation is currently running.");
            } else {
                LOGGER.warn("No operation is currently running in unmanaged thread: {}", Thread.currentThread().getName());
                current = new UnmanagedThreadOperation();
            }
        }
        return current;
    }

    @Override
    public void run(String displayName, Action<? super BuildOperationContext> action) {
        run(BuildOperationDetails.displayName(displayName).build(), Transformers.toTransformer(action));
    }

    @Override
    public void run(BuildOperationDetails operationDetails, Action<? super BuildOperationContext> action) {
        run(operationDetails, Transformers.toTransformer(action));
    }

    @Override
    public <T> T run(String displayName, Transformer<T, ? super BuildOperationContext> factory) {
        return run(BuildOperationDetails.displayName(displayName).build(), factory);
    }

    @Override
    public <T> T run(BuildOperationDetails operationDetails, Transformer<T, ? super BuildOperationContext> factory) {
        OperationDetails operationBefore = currentOperation.get();
        OperationDetails parent = operationDetails.getParent() != null ? (OperationDetails) operationDetails.getParent() : operationBefore;
        OperationIdentifier parentId;
        if (parent == null) {
            parentId = null;
        } else {
            if (!parent.isRunning()) {
                throw new IllegalStateException(String.format("Cannot start operation (%s) as parent operation (%s) has already completed.", operationDetails.getDisplayName(), parent.operationDetails.getDisplayName()));
            }
            parentId = parent.id;
        }
        OperationIdentifier id = new OperationIdentifier(nextId.getAndIncrement());
        OperationDetails currentOperation = new OperationDetails(parent, id, operationDetails);
        currentOperation.setRunning(true);
        this.currentOperation.set(currentOperation);
        try {
            long startTime = timeProvider.getCurrentTime();
            BuildOperationInternal operation = new BuildOperationInternal(id, parentId, operationDetails.getName(), operationDetails.getDisplayName(), operationDetails.getOperationDescriptor());
            listener.started(operation, new OperationStartEvent(startTime));

            T result = null;
            Throwable failure = null;
            BuildOperationContextImpl context = new BuildOperationContextImpl();
            try {
                ProgressLogger progressLogger;
                if (operationDetails.getProgressDisplayName() != null) {
                    progressLogger = progressLoggerFactory.newOperation(DefaultBuildOperationExecutor.class);
                    progressLogger.setDescription(operationDetails.getDisplayName());
                    progressLogger.setShortDescription(operationDetails.getProgressDisplayName());
                    progressLogger.started();
                } else {
                    progressLogger = null;
                }

                LOGGER.debug("Build operation '{}' started", operation.getDisplayName());
                try {
                    result = factory.transform(context);
                } finally {
                    if (progressLogger != null) {
                        progressLogger.completed();
                    }
                }
                if (parent != null && !parent.isRunning()) {
                    throw new IllegalStateException(String.format("Parent operation (%s) completed before this operation (%s).", parent.operationDetails.getDisplayName(), operationDetails.getDisplayName()));
                }
            } catch (Throwable t) {
                context.failed(t);
                failure = t;
            }

            long endTime = timeProvider.getCurrentTime();
            listener.finished(operation, new OperationResult(startTime, endTime, context.failure));

            if (failure != null) {
                throw UncheckedException.throwAsUncheckedException(failure);
            }

            LOGGER.debug("Build operation '{}' completed", operation.getDisplayName());
            return result;
        } finally {
            this.currentOperation.set(operationBefore);
            currentOperation.setRunning(false);
        }
    }

    protected void createRunningRootOperation(String displayName) {
        assert currentOperation.get() == null;
        OperationDetails operation = new OperationDetails(null, new OperationIdentifier(0), BuildOperationDetails.displayName(displayName).build());
        operation.setRunning(true);
        currentOperation.set(operation);
    }

    protected static class OperationDetails implements Operation {
        final AtomicBoolean running = new AtomicBoolean();
        final OperationDetails parent;
        final OperationIdentifier id;
        final BuildOperationDetails operationDetails;

        public OperationDetails(OperationDetails parent, OperationIdentifier id, BuildOperationDetails operationDetails) {
            this.parent = parent;
            this.id = id;
            this.operationDetails = operationDetails;
        }

        @Override
        public Object getId() {
            return id;
        }

        @Override
        public Object getParentId() {
            return parent.id;
        }

        public boolean isRunning() {
            return running.get();
        }

        public void setRunning(boolean running) {
            this.running.set(running);
        }
    }

    private static class BuildOperationContextImpl implements BuildOperationContext {
        Throwable failure;

        @Override
        public void failed(Throwable t) {
            failure = t;
        }
    }

    private static class UnmanagedThreadOperation implements Operation {

        private static final AtomicLong COUNTER = new AtomicLong();
        private final String id;
        private final String toString;

        private UnmanagedThreadOperation() {
            long count = COUNTER.getAndIncrement();
            id = "unmanaged_" + count;
            toString = "Unmanaged thread operation #" + count + " (" + Thread.currentThread().getName() + ')';
        }

        @Override
        public Object getId() {
            return id;
        }

        @Nullable
        @Override
        public Object getParentId() {
            return null;
        }

        @Override
        public String toString() {
            return toString;
        }
    }
}
