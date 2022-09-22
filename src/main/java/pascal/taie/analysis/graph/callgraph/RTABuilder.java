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

import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.NewInstance;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RTABuilder extends PropagationBasedBuilder {

    private Set<JClass> instantiatedClasses;
    private Map<JClass, Set<Pair<Invoke, JMethod>>> pending;

    @Override
    protected void customInit() {
        instantiatedClasses = Sets.newSet();
        pending = Maps.newMap();
    }

    @Override
    protected void propagateMethod(JMethod method) {
        method.getIR().forEach(stmt -> {
            if (stmt instanceof New newStmt) {
                processNewStmt(newStmt);
            }
        });
        callGraph.getCallSitesIn(method).forEach(this::processCallSite);
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
        pending.getOrDefault(jClass, Set.of()).forEach(pair -> {
            // update callGraph by adding new edge
            Invoke invoke = pair.first();
            JMethod callee = pair.second();
            callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(invoke), invoke, callee));
            // update resolveTable by updating cache
            addCGEdge(invoke, callee);
            MethodRef methodRef = invoke.getMethodRef();
            JClass cls = methodRef.getDeclaringClass();
            resolveTable.computeIfAbsent(cls, methodRef, (c, m) -> Sets.newSet()).add(callee);
        });
    }

    @Override
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
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
                        var pair = new Pair<>(callSite, method);
                        pending.computeIfAbsent(c, k -> Sets.newSet()).add(pair);
                    });
            callees = classes.stream()
                    .filter(instantiatedClasses::contains)
                    .map(c -> hierarchy.dispatch(c, methodRef))
                    .filter(Objects::nonNull) // filter out null callees
                    .collect(Collectors.toSet());
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
