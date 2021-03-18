/*
 * Tai-e: A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Tai-e is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package pascal.taie.pta.core.heap;

import pascal.taie.ir.exp.NewExp;
import pascal.taie.java.classes.JMethod;
import pascal.taie.java.types.Type;

import java.util.Optional;

/**
 * Objects that are created by new statements.
 */
public class NewObj implements Obj {

    private final NewExp newExp;

    public NewObj(NewExp newExp) {
        this.newExp = newExp;
    }

    @Override
    public Type getType() {
        return newExp.getType();
    }

    @Override
    public NewExp getAllocation() {
        return newExp;
    }

    @Override
    public Optional<JMethod> getContainerMethod() {
        return Optional.of(newExp.getAllocationSite().getMethod());
    }

    @Override
    public Type getContainerType() {
        return newExp.getAllocationSite()
                .getMethod()
                .getDeclaringClass()
                .getType();
    }

    @Override
    public String toString() {
        return "NewObj{" + newExp.getAllocationSite() + "/" + newExp + "}";
    }
}