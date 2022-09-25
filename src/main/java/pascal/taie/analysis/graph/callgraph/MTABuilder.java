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
import java.util.function.Predicate;

/**
 * Builds call graph via MTA.
 */
public final class MTABuilder extends AbstractXTABuilder {

    // a map from a class to all instantiated classes in all methods in the class
    private MultiMap<JClass, JClass> iClassesPerClass;
    // a map from a method to all instantiated classes for the field
    private MultiMap<JField, JClass> iClassesPerField;

    private TwoKeyMap<JClass, JClass, Set<Pair<Invoke, JMethod>>> pending;

    @Override
    protected void customInit() {
        super.customInit();
        iClassesPerClass = Maps.newMultiMap();
        iClassesPerField = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();
    }

    @Override
    public boolean containsInMethod(JMethod method, JClass clazz) {
        return iClassesPerClass.contains(method.getDeclaringClass(), clazz);
    }
    @Override
    public boolean updateClassesInMethod(JMethod method, JClass clazz) {
        return iClassesPerClass.put(method.getDeclaringClass(), clazz);
    }
    @Override
    public Set<JClass> getClassesInMethod(JMethod method) {
        return iClassesPerClass.get(method.getDeclaringClass());
    }
    @Override
    public boolean containsInField(JField field, JClass clazz) {
        return iClassesPerField.contains(field, clazz);
    }
    @Override
    public boolean updateClassesInField(JField field, JClass clazz) {
        return iClassesPerField.put(field, clazz);
    }
    @Override
    public Set<JClass> getClassesInField(JField field) {
        return iClassesPerField.get(field);
    }

    @Override
    protected void resolvePending(JClass clazz, JMethod caller) {
        pending.getOrDefault(clazz, caller.getDeclaringClass(), Set.of()).forEach(this::update);
    }
    @Override
    protected void updatePending(JClass clazz, JMethod caller, Invoke invoke, JMethod callee) {
        pending.computeIfAbsent(clazz, caller.getDeclaringClass(), (c, m) -> Sets.newSet())
                .add(new Pair<>(invoke, callee));
    }

    @Override
    protected void propagateMethod(JMethod method) {
        method.getDeclaringClass()
                .getDeclaredMethods()
                .stream()
                .filter(Predicate.not(JMethod::isAbstract))
                .forEach(super::propagateMethod);
    }

}
