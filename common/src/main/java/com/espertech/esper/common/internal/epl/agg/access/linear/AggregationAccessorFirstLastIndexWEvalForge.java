/*
 ***************************************************************************************
 *  Copyright (C) 2006 EsperTech, Inc. All rights reserved.                            *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 ***************************************************************************************
 */
package com.espertech.esper.common.internal.epl.agg.access.linear;

import com.espertech.esper.common.internal.epl.agg.core.AggregationAccessorForge;
import com.espertech.esper.common.internal.epl.agg.core.AggregationAccessorForgeGetCodegenContext;
import com.espertech.esper.common.internal.epl.expression.core.ExprForge;

/**
 * Represents the aggregation accessor that provides the result for the "first" and "last" aggregation function with index.
 */
public class AggregationAccessorFirstLastIndexWEvalForge implements AggregationAccessorForge {
    private final int streamNum;
    private final ExprForge childNode;
    private final ExprForge indexNode;
    private final int constant;
    private final boolean isFirst;

    /**
     * Ctor.
     *
     * @param streamNum stream id
     * @param childNode expression
     * @param indexNode index expression
     * @param constant  constant index
     * @param isFirst   true if returning first, false for returning last
     */
    public AggregationAccessorFirstLastIndexWEvalForge(int streamNum, ExprForge childNode, ExprForge indexNode, int constant, boolean isFirst) {
        this.streamNum = streamNum;
        this.childNode = childNode;
        this.indexNode = indexNode;
        this.constant = constant;
        this.isFirst = isFirst;
    }

    public void getValueCodegen(AggregationAccessorForgeGetCodegenContext context) {
        AggregationAccessorFirstLastIndexWEval.getValueCodegen(this, context);
    }

    public void getEnumerableEventsCodegen(AggregationAccessorForgeGetCodegenContext context) {
        AggregationAccessorFirstLastIndexWEval.getEnumerableEventsCodegen(this, context);
    }

    public void getEnumerableEventCodegen(AggregationAccessorForgeGetCodegenContext context) {
        AggregationAccessorFirstLastIndexWEval.getEnumerableEventCodegen(this, context);
    }

    public void getEnumerableScalarCodegen(AggregationAccessorForgeGetCodegenContext context) {
        AggregationAccessorFirstLastIndexWEval.getEnumerableScalarCodegen(this, context);
    }

    public int getStreamNum() {
        return streamNum;
    }

    public ExprForge getChildNode() {
        return childNode;
    }

    public ExprForge getIndexNode() {
        return indexNode;
    }

    public int getConstant() {
        return constant;
    }

    public boolean isFirst() {
        return isFirst;
    }
}