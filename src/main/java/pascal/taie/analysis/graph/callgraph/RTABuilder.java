/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.graph.callgraph;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.NewInstance;
import pascal.taie.ir.proginfo.MemberRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.collection.TwoKeyMap;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RTABuilder implements CGBuilder<Invoke, JMethod> {

    private static final Logger logger = LogManager.getLogger(RTABuilder.class);

    private DefaultCallGraph callGraph;

    private ClassHierarchy hierarchy;
    /**
     * Cache resolve results for interface/virtual invocations.
     */
    private TwoKeyMap<JClass, MemberRef, Set<JMethod>> resolveTable;
    private Set<JClass> instantiatedClasses;
    private Map<JClass, Set<Pair<Invoke, JMethod>>> pending;

    private Queue<JMethod> workList;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        return buildCallGraph(World.get().getMainMethod());
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);

        hierarchy = World.get().getClassHierarchy();
        resolveTable = Maps.newTwoKeyMap();
        instantiatedClasses = Sets.newSet();
        pending = Maps.newMap();

        workList = new ArrayDeque<>();
        workList.add(entry);
        while (!workList.isEmpty()) {
            JMethod method = workList.poll();
            callGraph.addReachableMethod(method);
            processMethod(method);
        }
        return callGraph;
    }

    private void processMethod(JMethod method) {
        method.getIR().forEach(stmt -> {
            if (stmt instanceof New newStmt) {
                processNewStmt(newStmt);
            }
        });
        callGraph.getCallSitesIn(method).forEach(this::processCallSite);
    }

    private void processCallSite(Invoke callSite) {
        resolveCalleesOf(callSite).forEach(callee -> {
            if (!callGraph.contains(callee)) {
                workList.add(callee);
            }
            callGraph.addEdge(new Edge<>(
                    CallGraphs.getCallKind(callSite), callSite, callee));
        });
    }

    private void processNewStmt(New stmt) {
        NewExp newExp = stmt.getRValue();
        if (newExp instanceof NewInstance newInstance) {
            JClass jClass = newInstance.getType().getJClass();
            if (!instantiatedClasses.contains(jClass)) {
                instantiatedClasses.add(jClass);
                resolvePending(jClass);
            }
        }
    }

    private void resolvePending(JClass jClass) {
        if (pending.containsKey(jClass)) {
            for (Pair<Invoke, JMethod> pair : pending.get(jClass)) {
                // update callGraph by adding new edge
                Invoke invoke = pair.first();
                JMethod method = pair.second();
                callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(invoke), invoke, method));
                // update resolveTable by adding new cache
                MethodRef methodRef = invoke.getMethodRef();
                JClass cls = methodRef.getDeclaringClass();
                resolveTable.computeIfAbsent(cls, methodRef, (c, m) -> Sets.newSet()).add(method);
            }
        }
    }

    private Set<JMethod> resolveCalleesOf(Invoke callSite) {
        CallKind kind = CallGraphs.getCallKind(callSite);
        return switch (kind) {
            case INTERFACE, VIRTUAL -> {
                MethodRef methodRef = callSite.getMethodRef();
                JClass cls = methodRef.getDeclaringClass();
                Set<JMethod> callees = resolveTable.get(cls, methodRef);
                if (callees == null) {
                    List<JClass> classes = hierarchy.getAllSubclassesOf(cls)
                            .stream()
                            .filter(Predicate.not(JClass::isAbstract))
                            .toList();
                    classes.stream()
                            .filter(Predicate.not(instantiatedClasses::contains))
                            .forEach(c -> {
                                JMethod method = hierarchy.dispatch(c, methodRef);
                                Pair<Invoke, JMethod> pair = new Pair<>(callSite, method);
                                pending.computeIfAbsent(c, k -> Sets.newSet()).add(pair);
                            });
                    callees = classes.stream()
                            .filter(instantiatedClasses::contains)
                            .map(c -> hierarchy.dispatch(c, methodRef))
                            .filter(Objects::nonNull) // filter out null callees
                            .collect(Collectors.toSet());
                    resolveTable.put(cls, methodRef, callees);
                }
                yield callees;
            }
            case SPECIAL, STATIC -> Set.of(callSite.getMethodRef().resolve());
            case DYNAMIC -> {
                logger.debug("RTA cannot resolve invokedynamic " + callSite);
                yield Set.of();
            }
            default -> throw new AnalysisException(
                    "Failed to resolve call site: " + callSite);
        };
    }

}
