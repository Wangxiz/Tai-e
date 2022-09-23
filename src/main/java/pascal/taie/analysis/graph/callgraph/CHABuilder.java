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

import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds call graph via class hierarchy analysis.
 */
class CHABuilder extends PropagationBasedBuilder {

    @Override
    protected void propagateMethod(JMethod method) {
        callGraph.getCallSitesIn(method).forEach(this::processCallSite);
    }

    /**
     * Resolves callees of a call site via class hierarchy analysis.
     */
    @Override
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
        MethodRef methodRef = callSite.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            callees = getSubTypes(cls)
                    .stream()
                    .map(c -> hierarchy.dispatch(c, methodRef))
                    .filter(Objects::nonNull) // filter out null callees
                    .collect(Collectors.toUnmodifiableSet());
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
