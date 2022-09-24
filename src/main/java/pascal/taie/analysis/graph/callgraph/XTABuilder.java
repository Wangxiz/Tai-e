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

import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.collection.TwoKeyMap;

import java.util.Set;

/**
 * Builds call graph via XTA.
 */
public final class XTABuilder extends AbstractXTABuilder {

    // a map from a method to all instantiated classes in the method
    private MultiMap<JMethod, JClass> iClassesPerMethod;
    // a map from a method to all instantiated classes for the field
    private MultiMap<JField, JClass> iClassesPerField;

    private TwoKeyMap<JClass, JMethod, Set<Pair<Invoke, JMethod>>> pending;

    @Override
    protected void customInit() {
        super.customInit();
        iClassesPerMethod = Maps.newMultiMap();
        iClassesPerField = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();
    }

    @Override
    protected boolean containsInMethod(JMethod method, JClass clazz) {
        return iClassesPerMethod.contains(method, clazz);
    }
    @Override
    protected boolean updateClassesInMethod(JMethod method, JClass clazz) {
        return iClassesPerMethod.put(method, clazz);
    }
    protected Set<JClass> getClassesInMethod(JMethod method) {
        return iClassesPerMethod.get(method);
    }

    @Override
    protected boolean containsInField(JField field, JClass clazz) {
        return iClassesPerField.contains(field, clazz);
    }
    @Override
    protected boolean updateClassesInField(JField field, JClass clazz) {
        return iClassesPerField.put(field, clazz);
    }
    protected Set<JClass> getClassesInField(JField field) {
        return iClassesPerField.get(field);
    }

    @Override
    protected void resolvePending(JClass clazz, JMethod caller) {
        pending.getOrDefault(clazz, caller, Set.of()).forEach(this::update);
    }
    @Override
    protected void updatePending(JClass clazz, JMethod caller, Invoke invoke, JMethod callee) {
        pending.computeIfAbsent(clazz, caller, (c, m) -> Sets.newSet())
                .add(new Pair<>(invoke, callee));
    }

}
