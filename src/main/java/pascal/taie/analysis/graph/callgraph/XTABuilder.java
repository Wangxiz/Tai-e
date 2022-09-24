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
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.collection.TwoKeyMap;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds call graph via XTA.
 */
public final class XTABuilder extends AbstractXTABuilder {

    // a map from a method to all instantiated classes in the method
    private MultiMap<JMethod, JClass> iClassesPerMethod;
    // a map from a method to all instantiated classes for the field
    private MultiMap<JField, JClass> iClassesPerField;

    // a multimap from a method to the fields which are stored by the methods
    private MultiMap<JMethod, JField> stores;
    // a multimap from a field to the methods which load the field
    private MultiMap<JField, JMethod> loads;

    private TwoKeyMap<JClass, JMethod, Set<Pair<Invoke, JMethod>>> pending;

    @Override
    protected void customInit() {
        super.customInit();
        iClassesPerMethod = Maps.newMultiMap();
        iClassesPerField = Maps.newMultiMap();
        stores = Maps.newMultiMap();
        loads = Maps.newMultiMap();
        pending = Maps.newTwoKeyMap();
    }

    @Override
    protected void propagateToMethod(Set<JClass> classes, JMethod method) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            boolean cgd = iClassesPerMethod.put(method, instanceClass);
            changed |= cgd;
            if (cgd) {
                resolvePending(instanceClass, method);
            }
        }
        if (changed) {
            propagateCalleeToCallers(method);
            propagateCallerToCallees(method);
            propagateMethodToFields(method);
        }
    }

    @Override
    protected void propagateToField(Set<JClass> classes, JField field) {
        boolean changed = false;
        for (JClass instanceClass : classes) {
            changed |= iClassesPerField.put(field, instanceClass);
        }
        if (changed) {
            propagateFieldToMethods(field);
        }
    }

    @Override
    protected void propagateCallerToCallee(JMethod caller, JMethod callee) {
        Set<JClass> classes = getParamSubTypes(callee).stream()
                .filter(c -> iClassesPerMethod.contains(caller, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, callee);
    }

    @Override
    protected void propagateCalleeToCaller(JMethod callee, JMethod caller) {
        Set<JClass> classes = getReturnSubTypes(callee).stream()
                .filter(c -> iClassesPerMethod.contains(callee, c))
                .collect(Collectors.toSet());
        propagateToMethod(classes, caller);
    }

    private void propagateFieldToMethod(JField field, JMethod method) {
        propagateToMethod(iClassesPerField.get(field), method);
    }

    private void propagateFieldToMethods(JField field) {
        loads.get(field)
                .forEach(method -> propagateFieldToMethod(field, method));
    }

    private void propagateMethodToField(JMethod method, JField field) {
        Set<JClass> classes = getFieldSubTypes(field).stream()
                .filter(c -> iClassesPerMethod.contains(method, c))
                .collect(Collectors.toSet());
        propagateToField(classes, field);
    }

    private void propagateMethodToFields(JMethod method) {
        stores.get(method)
                .forEach(field -> propagateMethodToField(method, field));
    }

    @Override
    protected void processNewStmt(New stmt) {
        NewExp newExp = stmt.getRValue();
        JMethod method = stmt.getContainer();
        if (newExp instanceof NewInstance newInstance) {
            JClass instanceClass = newInstance.getType().getJClass();
            if (!iClassesPerMethod.contains(method, instanceClass)) {
                boolean changed = iClassesPerMethod.put(method, instanceClass);
                if (changed) {
                    resolvePending(instanceClass, method);
                    propagateCalleeToCallers(method);
                    propagateCallerToCallees(method);
                    propagateMethodToFields(method);
                }
            }
        }
    }

    @Override
    protected void processStoreField(JMethod method, StoreField storeField) {
        JField field = storeField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            propagateMethodToField(method, field);
            stores.put(method, field);
        }
    }

    @Override
    protected void processLoadField(JMethod method, LoadField loadField) {
        JField field = loadField.getFieldRef().resolve();
        Type type = field.getType();
        if (type instanceof ClassType) {
            propagateFieldToMethod(field, method);
            loads.put(field, method);
        }
    }

    @Override
    protected void resolvePending(JClass instanceClass, JMethod method) {
        pending.getOrDefault(instanceClass, method, Set.of()).forEach(this::update);
    }

    @Override
    protected Set<JMethod> resolveVirtualCalleesOf(Invoke callSite) {
        MethodRef methodRef = callSite.getMethodRef();
        JClass cls = methodRef.getDeclaringClass();
        JMethod caller = callSite.getContainer();
        Set<JMethod> callees = resolveTable.get(cls, methodRef);
        if (callees == null) {
            Map<Boolean, Set<JClass>> classes = getSubTypes(cls).stream()
                    .collect(Collectors.groupingBy(c -> iClassesPerMethod.contains(caller, c), Collectors.toSet()));
            classes.getOrDefault(false, Set.of()).forEach(targetClass -> {
                JMethod method = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(method)) {
                    pending.computeIfAbsent(targetClass, caller, (c, m) -> Sets.newSet())
                            .add(new Pair<>(callSite, method));
                }
            });
            Set<JMethod> methods = Sets.newSet();
            classes.getOrDefault(true, Set.of()).forEach(targetClass -> {
                JMethod callee = hierarchy.dispatch(targetClass, methodRef);
                if (Objects.nonNull(callee)) {
                    methods.add(callee);
                    boolean changed = iClassesPerMethod.put(callee, targetClass);
                    if (changed) {
                        resolvePending(targetClass, callee);
                        propagateCalleeToCallers(callee);
                        propagateCallerToCallees(callee);
                        propagateMethodToFields(callee);
                    }
                }
            });
            callees = methods;
            resolveTable.put(cls, methodRef, callees);
        }
        return callees;
    }

}
