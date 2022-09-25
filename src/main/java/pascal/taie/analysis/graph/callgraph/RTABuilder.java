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
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds call graph via rapid type analysis.
 */
public final class RTABuilder extends PropagationBasedBuilder {

    // all instantiated classes
    private Set<JClass> iClasses;

    private MultiMap<JClass, Pair<Invoke, JMethod>> pending;

    @Override
    protected void customInit() {
        iClasses = Sets.newSet();
        pending = Maps.newMultiMap();
    }

    @Override
    protected void processMethod(JMethod method) {
        callGraph.addReachableMethod(method);
        method.getIR().forEach(stmt -> {
            if (stmt instanceof New newStmt) {
                processNew(newStmt);
            }
        });
        callGraph.getCallSitesIn(method).forEach(this::processCallSite);
    }
    @Override
    protected void processNew(New stmt) {
        NewExp newExp = stmt.getRValue();
        if (newExp instanceof NewInstance newInstance) {
            JClass instanceClass = newInstance.getType().getJClass();
            if (!iClasses.contains(instanceClass)) {
                boolean changed = iClasses.add(instanceClass);
                if (changed) {
                    resolvePending(instanceClass);
                }
            }
        }
    }

    private void resolvePending(JClass instanceClass) {
        pending.get(instanceClass).forEach(this::update);
    }
    private void updatePending(JClass clazz, Invoke invoke, JMethod callee) {
        pending.put(clazz, new Pair<>(invoke, callee));
    }

    @Override
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
        MethodRef methodRef = callSite.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            Map<Boolean, Set<JClass>> classes = getSubTypes(cls).stream()
                    .collect(Collectors.groupingBy(iClasses::contains, Collectors.toSet()));
            classes.getOrDefault(false, Set.of()).forEach(clazz -> {
                JMethod callee = hierarchy.dispatch(clazz, methodRef);
                if (Objects.nonNull(callee)) {
                    updatePending(clazz, callSite, callee);
                }
            });
            callees = classes.getOrDefault(true, Set.of())
                    .stream()
                    .map(clazz -> hierarchy.dispatch(clazz, methodRef))
                    .filter(Objects::nonNull) // filter out null callees
                    .collect(Collectors.toSet());
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
