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
package org.gradle.api.internal.artifacts.ivyservice;

import com.google.common.collect.Sets;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.UnresolvedDependency;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.DependencyGraphNodeResult;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedFileDependencyResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedArtifactsResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedFileDependencyResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.oldresult.TransientConfigurationResults;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.oldresult.TransientConfigurationResultsLoader;
import org.gradle.api.internal.artifacts.transform.ArtifactTransforms;
import org.gradle.api.internal.artifacts.transform.VariantSelector;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraphWithEdgeValues;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultLenientConfiguration implements LenientConfiguration, VisitedArtifactSet {
    private final ConfigurationInternal configuration;
    private final Set<UnresolvedDependency> unresolvedDependencies;
    private final VisitedArtifactsResults artifactResults;
    private final VisitedFileDependencyResults fileDependencyResults;
    private final TransientConfigurationResultsLoader transientConfigurationResultsFactory;
    private final ArtifactTransforms artifactTransforms;
    private final AttributeContainerInternal implicitAttributes;

    // Selected for the configuration
    private SelectedArtifactResults artifactsForThisConfiguration;
    private SelectedFileDependencyResults filesForThisConfiguration;

    public DefaultLenientConfiguration(ConfigurationInternal configuration, Set<UnresolvedDependency> unresolvedDependencies, VisitedArtifactsResults artifactResults, VisitedFileDependencyResults fileDependencyResults, TransientConfigurationResultsLoader transientConfigurationResultsLoader, ArtifactTransforms artifactTransforms) {
        this.configuration = configuration;
        implicitAttributes = configuration.getAttributes().asImmutable();
        this.unresolvedDependencies = unresolvedDependencies;
        this.artifactResults = artifactResults;
        this.fileDependencyResults = fileDependencyResults;
        this.transientConfigurationResultsFactory = transientConfigurationResultsLoader;
        this.artifactTransforms = artifactTransforms;
    }

    private SelectedArtifactResults getSelectedArtifacts() {
        if (artifactsForThisConfiguration == null) {
            artifactsForThisConfiguration = artifactResults.select(Specs.<ComponentIdentifier>satisfyAll(), artifactTransforms.variantSelector(implicitAttributes));
        }
        return artifactsForThisConfiguration;
    }

    private SelectedFileDependencyResults getSelectedFiles() {
        if (filesForThisConfiguration == null) {
            filesForThisConfiguration = fileDependencyResults.select(Specs.<ComponentIdentifier>satisfyAll(), artifactTransforms.variantSelector(implicitAttributes));
        }
        return filesForThisConfiguration;
    }

    public SelectedArtifactSet select() {
        return select(Specs.<Dependency>satisfyAll(), implicitAttributes, Specs.<ComponentIdentifier>satisfyAll());
    }

    public SelectedArtifactSet select(final Spec<? super Dependency> dependencySpec) {
        return select(dependencySpec, implicitAttributes, Specs.<ComponentIdentifier>satisfyAll());
    }

    @Override
    public SelectedArtifactSet select(final Spec<? super Dependency> dependencySpec, final AttributeContainerInternal requestedAttributes, final Spec<? super ComponentIdentifier> componentSpec) {
        final SelectedArtifactResults artifactResults;
        final SelectedFileDependencyResults fileDependencyResults;
        VariantSelector selector = artifactTransforms.variantSelector(requestedAttributes);
        artifactResults = this.artifactResults.select(componentSpec, selector);
        fileDependencyResults = this.fileDependencyResults.select(componentSpec, selector);

        return new SelectedArtifactSet() {
            @Override
            public <T extends Collection<Object>> T collectBuildDependencies(T dest) {
                artifactResults.getArtifacts().collectBuildDependencies(dest);
                fileDependencyResults.getArtifacts().collectBuildDependencies(dest);
                return dest;
            }

            @Override
            public void visitArtifacts(ArtifactVisitor visitor) {
                if (hasError()) {
                    List<Throwable> failures = new ArrayList<Throwable>();
                    for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                        failures.add(unresolvedDependency.getProblem());
                    }
                    ResolveException resolveException = new ResolveException(configuration.toString(), failures);
                    visitor.visitFailure(resolveException);
                }

                DefaultLenientConfiguration.this.visitArtifacts(dependencySpec, artifactResults, fileDependencyResults, visitor);
            }

            /**
             * Collects files reachable from first level dependencies that satisfy the given spec. Fails when any file cannot be resolved
             */
            @Override
            public <T extends Collection<? super File>> T collectFiles(T dest) throws ResolveException {
                rethrowFailure();
                ResolvedFilesCollectingVisitor visitor = new ResolvedFilesCollectingVisitor(dest);
                try {
                    DefaultLenientConfiguration.this.visitArtifacts(dependencySpec, artifactResults, fileDependencyResults, visitor);
                    // The visitor adds file dependencies directly to the destination collection however defers adding the artifacts.
                    // This is to ensure a fixed order regardless of whether the first level dependencies are filtered or not
                    // File dependencies and artifacts are currently treated separately as a migration step
                    visitor.addArtifacts();
                } catch (Throwable t) {
                    visitor.failures.add(t);
                }
                if (!visitor.failures.isEmpty()) {
                    throw new ArtifactResolveException("files", configuration.getPath(), configuration.getDisplayName(), visitor.failures);
                }
                return dest;
            }

            /**
             * Collects all resolved artifacts. Fails when any artifact cannot be resolved.
             */
            @Override
            public <T extends Collection<? super ResolvedArtifactResult>> T collectArtifacts(T dest) throws ResolveException {
                rethrowFailure();
                ResolvedArtifactCollectingVisitor visitor = new ResolvedArtifactCollectingVisitor(dest);
                try {
                    DefaultLenientConfiguration.this.visitArtifacts(dependencySpec, artifactResults, fileDependencyResults, visitor);
                } catch (Throwable t) {
                    visitor.failures.add(t);
                }
                if (!visitor.failures.isEmpty()) {
                    throw new ArtifactResolveException("artifacts", configuration.getPath(), configuration.getDisplayName(), visitor.failures);
                }
                return dest;
            }
        };
    }

    public boolean hasError() {
        return unresolvedDependencies.size() > 0;
    }

    public Set<UnresolvedDependency> getUnresolvedModuleDependencies() {
        return unresolvedDependencies;
    }

    public void rethrowFailure() throws ResolveException {
        if (hasError()) {
            List<Throwable> failures = new ArrayList<Throwable>();
            for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                failures.add(unresolvedDependency.getProblem());
            }
            throw new ResolveException(configuration.toString(), failures);
        }
    }

    private TransientConfigurationResults loadTransientGraphResults(SelectedArtifactResults artifactResults) {
        return transientConfigurationResultsFactory.create(artifactResults);
    }

    public Set<ResolvedDependency> getFirstLevelModuleDependencies(Spec<? super Dependency> dependencySpec) {
        Set<ResolvedDependency> matches = new LinkedHashSet<ResolvedDependency>();
        for (DependencyGraphNodeResult node : getFirstLevelNodes(dependencySpec)) {
            matches.add(node.getPublicView());
        }
        return matches;
    }

    private Set<DependencyGraphNodeResult> getFirstLevelNodes(Spec<? super Dependency> dependencySpec) {
        Set<DependencyGraphNodeResult> matches = new LinkedHashSet<DependencyGraphNodeResult>();
        TransientConfigurationResults graphResults = loadTransientGraphResults(getSelectedArtifacts());
        for (Map.Entry<ModuleDependency, DependencyGraphNodeResult> entry : graphResults.getFirstLevelDependencies().entrySet()) {
            if (dependencySpec.isSatisfiedBy(entry.getKey())) {
                matches.add(entry.getValue());
            }
        }
        return matches;
    }

    public Set<ResolvedDependency> getAllModuleDependencies() {
        Set<ResolvedDependency> resolvedElements = new LinkedHashSet<ResolvedDependency>();
        Deque<ResolvedDependency> workQueue = new LinkedList<ResolvedDependency>();
        workQueue.addAll(loadTransientGraphResults(getSelectedArtifacts()).getRootNode().getPublicView().getChildren());
        while (!workQueue.isEmpty()) {
            ResolvedDependency item = workQueue.removeFirst();
            if (resolvedElements.add(item)) {
                final Set<ResolvedDependency> children = item.getChildren();
                if (children != null) {
                    workQueue.addAll(children);
                }
            }
        }
        return resolvedElements;
    }

    @Override
    public Set<File> getFiles() {
        return getFiles(Specs.<Dependency>satisfyAll());
    }

    /**
     * Recursive but excludes unsuccessfully resolved artifacts.
     */
    public Set<File> getFiles(Spec<? super Dependency> dependencySpec) {
        Set<File> files = Sets.newLinkedHashSet();
        FilesAndArtifactCollectingVisitor visitor = new FilesAndArtifactCollectingVisitor(files);
        visitArtifacts(dependencySpec, getSelectedArtifacts(), getSelectedFiles(), visitor);
        files.addAll(getFiles(filterUnresolved(visitor.artifacts)));
        return files;
    }

    @Override
    public Set<ResolvedArtifact> getArtifacts() {
        return getArtifacts(Specs.<Dependency>satisfyAll());
    }

    /**
     * Recursive but excludes unsuccessfully resolved artifacts.
     */
    public Set<ResolvedArtifact> getArtifacts(Spec<? super Dependency> dependencySpec) {
        ArtifactCollectingVisitor visitor = new ArtifactCollectingVisitor();
        visitArtifacts(dependencySpec, getSelectedArtifacts(), getSelectedFiles(), visitor);
        return filterUnresolved(visitor.artifacts);
    }

    private Set<ResolvedArtifact> filterUnresolved(final Set<ResolvedArtifact> artifacts) {
        return CollectionUtils.filter(artifacts, IgnoreMissingExternalArtifacts.INSTANCE);
    }

    private Set<File> getFiles(final Set<ResolvedArtifact> artifacts) {
        final Set<File> files = new LinkedHashSet<File>();
        for (ResolvedArtifact artifact : artifacts) {
            File depFile = artifact.getFile();
            if (depFile != null) {
                files.add(depFile);
            }
        }
        return files;
    }

    /**
     * Recursive, includes unsuccessfully resolved artifacts
     *
     * @param dependencySpec dependency spec
     */
    private void visitArtifacts(Spec<? super Dependency> dependencySpec, SelectedArtifactResults artifactResults, SelectedFileDependencyResults fileDependencyResults, ArtifactVisitor visitor) {
        //this is not very nice might be good enough until we get rid of ResolvedConfiguration and friends
        //avoid traversing the graph causing the full ResolvedDependency graph to be loaded for the most typical scenario
        if (dependencySpec == Specs.SATISFIES_ALL) {
            if (visitor.includeFiles()) {
                fileDependencyResults.getArtifacts().visit(visitor);
            }
            artifactResults.getArtifacts().visit(visitor);
            return;
        }

        if (visitor.includeFiles()) {
            for (Map.Entry<FileCollectionDependency, ResolvedArtifactSet> entry : fileDependencyResults.getFirstLevelFiles().entrySet()) {
                if (dependencySpec.isSatisfiedBy(entry.getKey())) {
                    entry.getValue().visit(visitor);
                }
            }
        }

        CachingDirectedGraphWalker<DependencyGraphNodeResult, ResolvedArtifact> walker = new CachingDirectedGraphWalker<DependencyGraphNodeResult, ResolvedArtifact>(new ResolvedDependencyArtifactsGraph(visitor, fileDependencyResults));
        DependencyGraphNodeResult rootNode = loadTransientGraphResults(artifactResults).getRootNode();
        for (DependencyGraphNodeResult node : getFirstLevelNodes(dependencySpec)) {
            node.getArtifactsForIncomingEdge(rootNode).visit(visitor);
            walker.add(node);
        }
        walker.findValues();
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public Set<ResolvedDependency> getFirstLevelModuleDependencies() {
        return loadTransientGraphResults(getSelectedArtifacts()).getRootNode().getPublicView().getChildren();
    }

    private static class FilesAndArtifactCollectingVisitor extends ArtifactCollectingVisitor {
        final Collection<File> files;

        FilesAndArtifactCollectingVisitor(Collection<File> files) {
            this.files = files;
        }

        @Override
        public boolean includeFiles() {
            return true;
        }

        @Override
        public void visitFile(ComponentArtifactIdentifier artifactIdentifier, AttributeContainer variant, File file) {
            this.files.add(file);
        }
    }

    public static class ArtifactResolveException extends ResolveException {
        private final String type;
        private final String displayName;

        public ArtifactResolveException(String type, String path, String displayName, List<Throwable> failures) {
            super(path, failures);
            this.type = type;
            this.displayName = displayName;
        }

        // Need to override as error message is hardcoded in constructor of public type ResolveException
        @Override
        public String getMessage() {
            return String.format("Could not resolve all %s for %s.", type, displayName);
        }
    }

    private static class ResolvedDependencyArtifactsGraph implements DirectedGraphWithEdgeValues<DependencyGraphNodeResult, ResolvedArtifact> {
        private final ArtifactVisitor artifactsVisitor;
        private final SelectedFileDependencyResults fileDependencyResults;

        ResolvedDependencyArtifactsGraph(ArtifactVisitor artifactsVisitor, SelectedFileDependencyResults fileDependencyResults) {
            this.artifactsVisitor = artifactsVisitor;
            this.fileDependencyResults = fileDependencyResults;
        }

        @Override
        public void getNodeValues(DependencyGraphNodeResult node, Collection<? super ResolvedArtifact> values, Collection<? super DependencyGraphNodeResult> connectedNodes) {
            connectedNodes.addAll(node.getOutgoingEdges());
            if (artifactsVisitor.includeFiles()) {
                fileDependencyResults.getArtifacts(node.getNodeId()).visit(artifactsVisitor);
            }
        }

        @Override
        public void getEdgeValues(DependencyGraphNodeResult from, DependencyGraphNodeResult to,
                                  Collection<ResolvedArtifact> values) {
            to.getArtifactsForIncomingEdge(from).visit(artifactsVisitor);
        }
    }

    private static class IgnoreMissingExternalArtifacts implements Spec<ResolvedArtifact> {
        private static final IgnoreMissingExternalArtifacts INSTANCE = new IgnoreMissingExternalArtifacts();

        public boolean isSatisfiedBy(ResolvedArtifact element) {
            if (isExternalModuleArtifact(element)) {
                try {
                    element.getFile();
                } catch (org.gradle.internal.resolve.ArtifactResolveException e) {
                    return false;
                }
            }
            return true;
        }

        boolean isExternalModuleArtifact(ResolvedArtifact element) {
            return element.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier;
        }
    }
}