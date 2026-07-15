package com.metallum.client.metal.render.framegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Build-time validation for the immutable frame graph. */
public final class FrameGraphValidator {
    private FrameGraphValidator() {
    }

    public static void validate(final FrameGraph graph) {
        Map<Integer, FrameGraph.ResourceDesc> resources = resourceMap(graph.resources());
        Map<Integer, FrameGraph.PassDesc> passes = passMap(graph.passes());
        Map<Integer, Set<Integer>> successors = dependencyGraph(graph.passes(), passes);
        List<Integer> topological = topologicalOrder(successors, passes.keySet());
        Map<Integer, Set<Integer>> reachable = reachability(topological, successors);

        validateLifetimes(graph, resources, passes, reachable);
        validateAccesses(graph, resources, reachable);
    }

    private static Map<Integer, FrameGraph.ResourceDesc> resourceMap(
            final List<FrameGraph.ResourceDesc> declarations
    ) {
        Map<Integer, FrameGraph.ResourceDesc> result = new HashMap<>();
        for (FrameGraph.ResourceDesc declaration : declarations) {
            FrameGraph.ResourceDesc previous = result.put(declaration.id().value(), declaration);
            if (previous != null) {
                throw invalid("duplicate resource ID " + declaration.id().value());
            }
        }
        return result;
    }

    private static Map<Integer, FrameGraph.PassDesc> passMap(
            final List<FrameGraph.PassDesc> declarations
    ) {
        Map<Integer, FrameGraph.PassDesc> result = new HashMap<>();
        for (FrameGraph.PassDesc declaration : declarations) {
            FrameGraph.PassDesc previous = result.put(declaration.id().value(), declaration);
            if (previous != null) {
                throw invalid("duplicate pass ID " + declaration.id().value());
            }
        }
        return result;
    }

    private static Map<Integer, Set<Integer>> dependencyGraph(
            final List<FrameGraph.PassDesc> declarations,
            final Map<Integer, FrameGraph.PassDesc> passes
    ) {
        Map<Integer, Set<Integer>> successors = new HashMap<>();
        for (Integer id : passes.keySet()) {
            successors.put(id, new HashSet<>());
        }
        for (FrameGraph.PassDesc pass : declarations) {
            Set<Integer> uniqueDependencies = new HashSet<>();
            for (FrameGraph.PassId dependency : pass.dependencies()) {
                FrameGraph.PassDesc declaredDependency = passes.get(dependency.value());
                if (declaredDependency == null) {
                    throw invalid("pass " + pass.id().name() + " depends on missing pass " + dependency.name());
                }
                requireSameId(dependency, declaredDependency.id(), "pass dependency");
                if (dependency.value() == pass.id().value()) {
                    throw invalid("pass " + pass.id().name() + " depends on itself");
                }
                if (!uniqueDependencies.add(dependency.value())) {
                    throw invalid("pass " + pass.id().name() + " repeats dependency " + dependency.name());
                }
                successors.get(dependency.value()).add(pass.id().value());
            }
        }
        return successors;
    }

    private static List<Integer> topologicalOrder(
            final Map<Integer, Set<Integer>> successors,
            final Set<Integer> passIds
    ) {
        Map<Integer, Integer> indegree = new HashMap<>();
        for (Integer id : passIds) {
            indegree.put(id, 0);
        }
        for (Set<Integer> children : successors.values()) {
            for (Integer child : children) {
                indegree.put(child, indegree.get(child) + 1);
            }
        }

        ArrayDeque<Integer> ready = new ArrayDeque<>();
        passIds.stream().filter(id -> indegree.get(id) == 0).sorted().forEach(ready::addLast);
        List<Integer> order = new ArrayList<>(passIds.size());
        while (!ready.isEmpty()) {
            int id = ready.removeFirst();
            order.add(id);
            successors.get(id).stream().sorted().forEach(child -> {
                int remaining = indegree.get(child) - 1;
                indegree.put(child, remaining);
                if (remaining == 0) {
                    ready.addLast(child);
                }
            });
        }
        if (order.size() != passIds.size()) {
            throw invalid("pass dependency graph contains a cycle");
        }
        return order;
    }

    private static Map<Integer, Set<Integer>> reachability(
            final List<Integer> topological,
            final Map<Integer, Set<Integer>> successors
    ) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (int index = topological.size() - 1; index >= 0; index--) {
            int pass = topological.get(index);
            Set<Integer> descendants = new HashSet<>();
            for (Integer child : successors.get(pass)) {
                descendants.add(child);
                descendants.addAll(result.get(child));
            }
            result.put(pass, descendants);
        }
        return result;
    }

    private static void validateLifetimes(
            final FrameGraph graph,
            final Map<Integer, FrameGraph.ResourceDesc> resources,
            final Map<Integer, FrameGraph.PassDesc> passes,
            final Map<Integer, Set<Integer>> reachable
    ) {
        for (FrameGraph.ResourceDesc resource : resources.values()) {
            FrameGraph.Lifetime lifetime = resource.lifetime();
            if (lifetime.isWholeGraph()) {
                continue;
            }
            int first = lifetime.first().value();
            int last = lifetime.last().value();
            if (!passes.containsKey(first) || !passes.containsKey(last)) {
                throw invalid("resource " + resource.id().name() + " lifetime references a missing pass");
            }
            requireSameId(lifetime.first(), passes.get(first).id(), "lifetime start");
            requireSameId(lifetime.last(), passes.get(last).id(), "lifetime end");
            if (!orderedOrSame(first, last, reachable)) {
                throw invalid("resource " + resource.id().name() + " lifetime endpoints are unordered");
            }
        }

        for (FrameGraph.PassDesc pass : graph.passes()) {
            for (FrameGraph.ResourceAccess access : pass.accesses()) {
                FrameGraph.ResourceDesc resource = resources.get(access.resource().value());
                if (resource == null || resource.lifetime().isWholeGraph()) {
                    continue;
                }
                int current = pass.id().value();
                int first = resource.lifetime().first().value();
                int last = resource.lifetime().last().value();
                if (!orderedOrSame(first, current, reachable)
                        || !orderedOrSame(current, last, reachable)) {
                    throw invalid("pass " + pass.id().name() + " accesses " + resource.id().name()
                            + " outside its declared lifetime");
                }
            }
        }
    }

    private static void validateAccesses(
            final FrameGraph graph,
            final Map<Integer, FrameGraph.ResourceDesc> resources,
            final Map<Integer, Set<Integer>> reachable
    ) {
        Map<Integer, List<PassAccess>> byResource = new HashMap<>();
        for (FrameGraph.PassDesc pass : graph.passes()) {
            Set<Integer> accessedInPass = new HashSet<>();
            for (FrameGraph.ResourceAccess access : pass.accesses()) {
                FrameGraph.ResourceDesc resource = resources.get(access.resource().value());
                if (resource == null) {
                    throw invalid("pass " + pass.id().name() + " accesses undeclared resource "
                            + access.resource().name());
                }
                requireSameId(access.resource(), resource.id(), "resource access");
                if (!accessedInPass.add(access.resource().value())) {
                    throw invalid("pass " + pass.id().name() + " declares resource "
                            + access.resource().name() + " more than once; use READ_WRITE");
                }
                validateStage(pass, access);
                validateAttachment(pass, access, resource);
                validateHistory(pass, access, resource);
                byResource.computeIfAbsent(access.resource().value(), ignored -> new ArrayList<>())
                        .add(new PassAccess(
                                pass.id().value(),
                                access.kind(),
                                definesResourceAfterPass(access)
                        ));
            }
        }

        for (Map.Entry<Integer, List<PassAccess>> entry : byResource.entrySet()) {
            FrameGraph.ResourceDesc resource = resources.get(entry.getKey());
            List<PassAccess> accesses = entry.getValue();
            for (PassAccess access : accesses) {
                if (access.kind().reads() && !resource.initiallyDefined()
                        && !hasOrderedWriter(access.pass(), accesses, reachable)) {
                    throw invalid("resource " + resource.id().name() + " is read before an ordered write");
                }
            }
            for (int leftIndex = 0; leftIndex < accesses.size(); leftIndex++) {
                PassAccess left = accesses.get(leftIndex);
                for (int rightIndex = leftIndex + 1; rightIndex < accesses.size(); rightIndex++) {
                    PassAccess right = accesses.get(rightIndex);
                    if (!(left.kind().writes() || right.kind().writes())) {
                        continue;
                    }
                    if (!orderedOrSame(left.pass(), right.pass(), reachable)
                            && !orderedOrSame(right.pass(), left.pass(), reachable)) {
                        throw invalid("resource " + resource.id().name()
                                + " has unordered conflicting accesses");
                    }
                }
            }
        }
    }

    private static boolean hasOrderedWriter(
            final int reader,
            final List<PassAccess> accesses,
            final Map<Integer, Set<Integer>> reachable
    ) {
        for (PassAccess candidate : accesses) {
            if (candidate.definesAfterPass()
                    && candidate.pass() != reader
                    && reachable.get(candidate.pass()).contains(reader)) {
                return true;
            }
        }
        return false;
    }

    private static void validateStage(
            final FrameGraph.PassDesc pass,
            final FrameGraph.ResourceAccess access
    ) {
        boolean compatible = switch (pass.encoder()) {
            case RENDER -> access.stage() == FrameGraph.PipelineStage.VERTEX
                    || access.stage() == FrameGraph.PipelineStage.FRAGMENT;
            case COMPUTE -> access.stage() == FrameGraph.PipelineStage.COMPUTE;
            case BLIT -> access.stage() == FrameGraph.PipelineStage.BLIT;
            case EXTERNAL_METALFX -> access.stage() == FrameGraph.PipelineStage.METALFX;
        };
        if (!compatible) {
            throw invalid("pass " + pass.id().name() + " uses " + access.stage()
                    + " access with a " + pass.encoder() + " encoder");
        }
    }

    private static void validateAttachment(
            final FrameGraph.PassDesc pass,
            final FrameGraph.ResourceAccess access,
            final FrameGraph.ResourceDesc resource
    ) {
        FrameGraph.AttachmentContract attachment = access.attachment();
        if (!attachment.isAttachment()) {
            return;
        }
        if (pass.encoder() != FrameGraph.EncoderClass.RENDER
                || access.stage() != FrameGraph.PipelineStage.FRAGMENT) {
            throw invalid("attachment " + resource.id().name()
                    + " must use a render encoder fragment-stage access");
        }
        if (resource.shape().type() != FrameGraph.ResourceType.TEXTURE) {
            throw invalid("attachment " + resource.id().name() + " must be a texture");
        }
        boolean loadsPreviousContents = attachment.loadAction() == FrameGraph.LoadAction.LOAD;
        if (loadsPreviousContents != access.kind().reads()) {
            throw invalid("attachment " + resource.id().name()
                    + " load action does not match its read access");
        }
        if (!access.kind().writes()) {
            throw invalid("attachment " + resource.id().name() + " must declare a write access");
        }
    }

    private static boolean definesResourceAfterPass(final FrameGraph.ResourceAccess access) {
        return access.kind().writes()
                && (!access.attachment().isAttachment()
                    || access.attachment().storeAction() == FrameGraph.StoreAction.STORE);
    }

    private static void validateHistory(
            final FrameGraph.PassDesc pass,
            final FrameGraph.ResourceAccess access,
            final FrameGraph.ResourceDesc resource
    ) {
        FrameGraph.HistoryRole role = access.historyRole();
        if (role == FrameGraph.HistoryRole.NONE) {
            return;
        }
        if (resource.persistence() != FrameGraph.PersistenceClass.HISTORY) {
            throw invalid("pass " + pass.id().name() + " declares history access to non-history resource "
                    + resource.id().name());
        }
        if (role.reads() != access.kind().reads() || role.writes() != access.kind().writes()) {
            throw invalid("pass " + pass.id().name() + " history role does not match access kind for "
                    + resource.id().name());
        }
    }

    private static boolean orderedOrSame(
            final int first,
            final int second,
            final Map<Integer, Set<Integer>> reachable
    ) {
        return first == second || reachable.get(first).contains(second);
    }

    private static IllegalArgumentException invalid(final String message) {
        return new IllegalArgumentException("Invalid Metal frame graph: " + message);
    }

    private static void requireSameId(
            final FrameGraph.PassId reference,
            final FrameGraph.PassId declaration,
            final String context
    ) {
        if (!reference.equals(declaration)) {
            throw invalid(context + " ID/name does not match its declaration");
        }
    }

    private static void requireSameId(
            final FrameGraph.ResourceId reference,
            final FrameGraph.ResourceId declaration,
            final String context
    ) {
        if (!reference.equals(declaration)) {
            throw invalid(context + " ID/name does not match its declaration");
        }
    }

    private record PassAccess(
            int pass,
            FrameGraph.AccessKind kind,
            boolean definesAfterPass
    ) {
    }
}
