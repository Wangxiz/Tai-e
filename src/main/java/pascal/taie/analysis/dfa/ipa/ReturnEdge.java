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

package pascal.taie.analysis.dfa.ipa;

import pascal.taie.analysis.dataflow.framework.EdgeTransfer;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.HashUtils;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;

public class ReturnEdge<Node> extends ICFGEdge<Node> {

    private final Node callSite;

    /**
     * Variables holding return values.
     */
    private final Set<Var> returnVars;

    /**
     * Exceptions that may be thrown out.
     */
    private final Set<ClassType> exceptions;

    ReturnEdge(Node exit, Node retSite, Node callSite,
                      Collection<Var> retVars, Collection<ClassType> exceptions) {
        super(Kind.RETURN, exit, retSite);
        this.callSite = callSite;
        this.returnVars = Set.copyOf(retVars);
        this.exceptions = Set.copyOf(exceptions);
    }

    public Node getCallSite() {
        return callSite;
    }

    public Stream<Var> returnVars() {
        return returnVars.stream();
    }

    public Stream<ClassType> exceptions() {
        return exceptions.stream();
    }

    @Override
    public <Fact> void accept(EdgeTransfer<Node, Fact> transfer,
                              Fact sourceInFlow, Fact sourceOutFlow,
                              Fact edgeFlow) {
//        transfer.transferReturnEdge(this, sourceOutFlow, edgeFlow);
    }

    @Override
    protected int computeHashCode() {
        return HashUtils.hash(kind, source, target, callSite);
    }
}
