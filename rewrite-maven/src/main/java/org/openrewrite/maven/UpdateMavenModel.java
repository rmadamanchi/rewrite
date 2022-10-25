/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import org.openrewrite.ExecutionContext;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.Markup;
import org.openrewrite.maven.internal.MavenPomDownloader;
import org.openrewrite.maven.tree.*;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

public class UpdateMavenModel<P> extends MavenVisitor<P> {

    @Override
    public Xml visitDocument(Xml.Document document, P p) {
        if (!(p instanceof ExecutionContext)) {
            throw new IllegalArgumentException("UpdateMavenModel must be provided an ExecutionContext");
        }
        ExecutionContext ctx = (ExecutionContext) p;

        MavenResolutionResult resolutionResult = getResolutionResult();
        Pom requested = resolutionResult.getPom().getRequested();

        Optional<Xml.Tag> parent = document.getRoot().getChild("parent");
        if (parent.isPresent()) {
            Parent updatedParent = new Parent(new GroupArtifactVersion(
                    parent.get().getChildValue("groupId").orElse(null),
                    parent.get().getChildValue("artifactId").orElseThrow(() -> new IllegalStateException("GAV must have artifactId")),
                    parent.get().getChildValue("version").orElse(null)
            ), parent.get().getChildValue("relativePath").orElse(null));
            requested = requested.withParent(updatedParent);
        } else if (requested.getParent() != null) {
            requested = requested.withParent(null);
        }

        Optional<Xml.Tag> dependencies = document.getRoot().getChild("dependencies");
        if (dependencies.isPresent()) {
            List<Xml.Tag> eachDependency = dependencies.get().getChildren("dependency");
            List<Dependency> requestedDependencies = new ArrayList<>(eachDependency.size());
            for (Xml.Tag dependency : eachDependency) {
                requestedDependencies.add(new Dependency(
                        new GroupArtifactVersion(
                                dependency.getChildValue("groupId").orElse(null),
                                dependency.getChildValue("artifactId").orElseThrow(() -> new IllegalStateException("Dependency must have artifactId")),
                                dependency.getChildValue("version").orElse(null)
                        ),
                        dependency.getChildValue("classifier").orElse(null),
                        dependency.getChildValue("type").orElse(null),
                        dependency.getChildValue("scope").orElse("compile"),
                        mapExclusions(dependency),
                        dependency.getChildValue("optional").map(Boolean::parseBoolean).orElse(false)
                ));
            }
            requested = requested.withDependencies(requestedDependencies);
        } else if (!requested.getDependencies().isEmpty()) {
            requested = requested.withDependencies(Collections.emptyList());
        }

        Optional<Xml.Tag> dependencyManagement = document.getRoot().getChild("dependencyManagement");
        if (dependencyManagement.isPresent()) {
            dependencies = dependencyManagement.get().getChild("dependencies");
            if (dependencies.isPresent()) {
                List<Xml.Tag> eachDependency = dependencies.get().getChildren("dependency");
                List<ManagedDependency> requestedManagedDependencies = new ArrayList<>(eachDependency.size());
                for (Xml.Tag dependency : eachDependency) {
                    String scope = dependency.getChildValue("scope").orElse("compile");
                    GroupArtifactVersion gav = new GroupArtifactVersion(
                            dependency.getChildValue("groupId").orElse(null),
                            dependency.getChildValue("artifactId").orElseThrow(() -> new IllegalStateException("Dependency must have artifactId")),
                            dependency.getChildValue("version").orElse(null)
                    );

                    if ("import".equals(scope)) {
                        requestedManagedDependencies.add(new ManagedDependency.Imported(gav));
                    } else {
                        requestedManagedDependencies.add(new ManagedDependency.Defined(gav, scope,
                                dependency.getChildValue("type").orElse(null),
                                dependency.getChildValue("classifier").orElse(null),
                                mapExclusions(dependency)));
                    }
                }
                requested = requested.withDependencyManagement(requestedManagedDependencies);
            }
        } else if (!requested.getDependencyManagement().isEmpty()) {
            requested = requested.withDependencyManagement(Collections.emptyList());
        }

        try {
            MavenResolutionResult updated = updateResult(ctx, resolutionResult.withPom(resolutionResult.getPom().withRequested(requested)),
                    resolutionResult.getProjectPoms());
            return document.withMarkers(document.getMarkers().computeByType(getResolutionResult(),
                    (original, ignored) -> updated));
        } catch (MavenDownloadingExceptions e) {
            return markExceptions(document, e);
        }
    }

    @Nullable
    private List<GroupArtifact> mapExclusions(Xml.Tag tag) {
        return tag.getChild("exclusions")
                .map(exclusions -> {
                    List<Xml.Tag> eachExclusion = exclusions.getChildren("exclusion");
                    List<GroupArtifact> requestedExclusions = new ArrayList<>(eachExclusion.size());
                    for (Xml.Tag exclusion : eachExclusion) {
                        requestedExclusions.add(new GroupArtifact(
                                exclusion.getChildValue("groupId").orElse(null),
                                exclusion.getChildValue("artifactId").orElse(null)
                        ));
                    }
                    return requestedExclusions;
                })
                .orElse(null);
    }

    private MavenResolutionResult updateResult(ExecutionContext ctx, MavenResolutionResult resolutionResult, Map<Path, Pom> projectPoms) throws MavenDownloadingExceptions {
        MavenPomDownloader downloader = new MavenPomDownloader(projectPoms, ctx, getResolutionResult().getMavenSettings(),
                getResolutionResult().getActiveProfiles());

        AtomicReference<MavenDownloadingExceptions> exceptions = new AtomicReference<>();
        try {
            ResolvedPom resolved = resolutionResult.getPom().resolve(ctx, downloader);
            MavenResolutionResult mrr = resolutionResult
                    .withPom(resolved)
                    .withModules(ListUtils.map(resolutionResult.getModules(), module -> {
                        try {
                            return updateResult(ctx, module, projectPoms);
                        } catch (MavenDownloadingExceptions e) {
                            exceptions.set(MavenDownloadingExceptions.append(exceptions.get(), e));
                            return module;
                        }
                    }))
                    .resolveDependencies(downloader, ctx);
            if (exceptions.get() != null) {
                throw exceptions.get();
            }
            return mrr;
        } catch (MavenDownloadingExceptions e) {
            throw MavenDownloadingExceptions.append(exceptions.get(), e);
        } catch (MavenDownloadingException e) {
            throw MavenDownloadingExceptions.append(exceptions.get(), e);
        }
    }

    private Xml.Document markExceptions(Xml.Document document, MavenDownloadingExceptions e) {
        Map<GroupArtifact, List<MavenDownloadingException>> byGav = new HashMap<>();
        for (MavenDownloadingException exception : e.getExceptions()) {
            byGav.computeIfAbsent(new GroupArtifact(exception.getRoot().getGroupId(),
                    exception.getRoot().getArtifactId()), ga -> new ArrayList<>()).add(exception);
        }

        return (Xml.Document) new MavenIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer integer) {
                Xml.Tag t = tag;
                for (GroupArtifact ga : byGav.keySet()) {
                    boolean hasException = isDependencyTag(ga.getGroupId(), ga.getArtifactId()) ||
                                           isManagedDependencyTag(ga.getGroupId(), ga.getArtifactId());
                    if (isParentTag()) {
                        Parent parent = requireNonNull(getResolutionResult().getPom().getRequested().getParent());
                        if (parent.getGroupId().equals(ga.getGroupId()) && parent.getArtifactId().equals(ga.getArtifactId())) {
                            hasException = true;
                        }
                    }

                    if (hasException) {
                        for (MavenDownloadingException exception : byGav.get(ga)) {
                            t = Markup.warn(t, exception);
                        }
                    }
                }
                return super.visitTag(t, integer);
            }
        }.visitNonNull(document, 0);
    }
}
