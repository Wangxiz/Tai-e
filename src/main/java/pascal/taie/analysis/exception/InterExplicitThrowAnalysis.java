/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.exception;

import pascal.taie.ir.IR;

/**
 * Analysis explicit exceptions based on interprocedural analysis.
 * This analysis requires pointer analysis result.
 */
class InterExplicitThrowAnalysis implements ExplicitThrowAnalysis {

    @Override
    public void analyze(IR ir, ThrowResult result) {
        throw new UnsupportedOperationException();
    }
}
