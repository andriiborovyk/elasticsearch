/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.util.BytesRefArray;
import org.elasticsearch.core.Releasables;

import java.util.BitSet;

/**
 * Block implementation that stores values in a {@link BytesRefArrayVector}.
 * Does not take ownership of the given {@link BytesRefArray} and does not adjust circuit breakers to account for it.
 * This class is generated. Do not edit it.
 */
final class BytesRefArrayBlock extends AbstractArrayBlock implements BytesRefBlock {

    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(BytesRefArrayBlock.class);

    private final BytesRefArrayVector vector;

    BytesRefArrayBlock(
        BytesRefArray values,
        int positionCount,
        int[] firstValueIndexes,
        BitSet nulls,
        MvOrdering mvOrdering,
        BlockFactory blockFactory
    ) {
        this(
            new BytesRefArrayVector(values, firstValueIndexes == null ? positionCount : firstValueIndexes[positionCount], blockFactory),
            positionCount,
            firstValueIndexes,
            nulls,
            mvOrdering,
            blockFactory
        );
    }

    private BytesRefArrayBlock(
        BytesRefArrayVector vector,
        int positionCount,
        int[] firstValueIndexes,
        BitSet nulls,
        MvOrdering mvOrdering,
        BlockFactory blockFactory
    ) {
        super(positionCount, firstValueIndexes, nulls, mvOrdering, blockFactory);
        this.vector = vector;
        assert firstValueIndexes == null
            ? vector.getPositionCount() == getPositionCount()
            : firstValueIndexes[getPositionCount()] == vector.getPositionCount();
    }

    @Override
    public BytesRefVector asVector() {
        return null;
    }

    @Override
    public BytesRef getBytesRef(int valueIndex, BytesRef dest) {
        return vector.getBytesRef(valueIndex, dest);
    }

    @Override
    public BytesRefBlock filter(int... positions) {
        // TODO use reference counting to share the vector
        final BytesRef scratch = new BytesRef();
        try (var builder = blockFactory().newBytesRefBlockBuilder(positions.length)) {
            for (int pos : positions) {
                if (isNull(pos)) {
                    builder.appendNull();
                    continue;
                }
                int valueCount = getValueCount(pos);
                int first = getFirstValueIndex(pos);
                if (valueCount == 1) {
                    builder.appendBytesRef(getBytesRef(getFirstValueIndex(pos), scratch));
                } else {
                    builder.beginPositionEntry();
                    for (int c = 0; c < valueCount; c++) {
                        builder.appendBytesRef(getBytesRef(first + c, scratch));
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.mvOrdering(mvOrdering()).build();
        }
    }

    @Override
    public ElementType elementType() {
        return ElementType.BYTES_REF;
    }

    @Override
    public BytesRefBlock expand() {
        if (firstValueIndexes == null) {
            incRef();
            return this;
        }
        if (nullsMask == null) {
            vector.incRef();
            return vector.asBlock();
        }

        // The following line is correct because positions with multi-values are never null.
        int expandedPositionCount = vector.getPositionCount();
        long bitSetRamUsedEstimate = Math.max(nullsMask.size(), BlockRamUsageEstimator.sizeOfBitSet(expandedPositionCount));
        blockFactory().adjustBreaker(bitSetRamUsedEstimate, false);

        BytesRefArrayBlock expanded = new BytesRefArrayBlock(
            vector,
            expandedPositionCount,
            null,
            shiftNullsToExpandedPositions(),
            MvOrdering.DEDUPLICATED_AND_SORTED_ASCENDING,
            blockFactory()
        );
        blockFactory().adjustBreaker(expanded.ramBytesUsedOnlyBlock() - bitSetRamUsedEstimate, true);
        // We need to incRef after adjusting any breakers, otherwise we might leak the vector if the breaker trips.
        vector.incRef();
        return expanded;
    }

    private long ramBytesUsedOnlyBlock() {
        return BASE_RAM_BYTES_USED + BlockRamUsageEstimator.sizeOf(firstValueIndexes) + BlockRamUsageEstimator.sizeOfBitSet(nullsMask);
    }

    @Override
    public long ramBytesUsed() {
        return ramBytesUsedOnlyBlock() + vector.ramBytesUsed();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof BytesRefBlock that) {
            return BytesRefBlock.equals(this, that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return BytesRefBlock.hash(this);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[positions="
            + getPositionCount()
            + ", mvOrdering="
            + mvOrdering()
            + ", vector="
            + vector
            + ']';
    }

    @Override
    public void allowPassingToDifferentDriver() {
        super.allowPassingToDifferentDriver();
        vector.allowPassingToDifferentDriver();
    }

    @Override
    public void closeInternal() {
        blockFactory().adjustBreaker(-ramBytesUsedOnlyBlock(), true);
        Releasables.closeExpectNoException(vector);
    }
}
