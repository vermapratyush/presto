/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.aggregation.builder;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.common.Page;
import com.facebook.presto.common.PageBuilder;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.memory.context.LocalMemoryContext;
import com.facebook.presto.operator.GroupByHash;
import com.facebook.presto.operator.HashAggregationOperator.ReserveType;
import com.facebook.presto.operator.HashCollisionsCounter;
import com.facebook.presto.operator.OperatorContext;
import com.facebook.presto.operator.TransformWork;
import com.facebook.presto.operator.UpdateMemory;
import com.facebook.presto.operator.Work;
import com.facebook.presto.operator.WorkProcessor;
import com.facebook.presto.operator.WorkProcessor.ProcessState;
import com.facebook.presto.operator.aggregation.AccumulatorFactory;
import com.facebook.presto.spi.function.aggregation.GroupByIdBlock;
import com.facebook.presto.spi.function.aggregation.GroupedAccumulator;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.AggregationNode.Step;
import com.facebook.presto.sql.gen.JoinCompiler;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntIterators;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import static com.facebook.presto.SystemSessionProperties.isDictionaryAggregationEnabled;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.operator.GroupByHash.createGroupByHash;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class InMemoryHashAggregationBuilder
        implements HashAggregationBuilder
{
    private final GroupByHash groupByHash;
    private final List<Aggregator> aggregators;
    private final OperatorContext operatorContext;
    private final boolean partial;
    private final OptionalLong maxPartialMemory;
    private final LocalMemoryContext systemMemoryContext;
    private final LocalMemoryContext localUserMemoryContext;
    private final ReserveType reserveType;
    private final Consumer<Long> memoryConsumer;

    private boolean full;
    private static final Logger log = Logger.get(InMemoryHashAggregationBuilder.class);
    public InMemoryHashAggregationBuilder(
            List<AccumulatorFactory> accumulatorFactories,
            Step step,
            int expectedGroups,
            List<Type> groupByTypes,
            List<Integer> groupByChannels,
            Optional<Integer> hashChannel,
            OperatorContext operatorContext,
            Optional<DataSize> maxPartialMemory,
            JoinCompiler joinCompiler,
            boolean yieldForMemoryReservation,
            ReserveType reserveType)
    {
        this(accumulatorFactories,
                step,
                expectedGroups,
                groupByTypes,
                groupByChannels,
                hashChannel,
                operatorContext,
                maxPartialMemory,
                Optional.empty(),
                joinCompiler,
                yieldForMemoryReservation,
                reserveType,
                Optional.empty());
    }

    public InMemoryHashAggregationBuilder(
            List<AccumulatorFactory> accumulatorFactories,
            Step step,
            int expectedGroups,
            List<Type> groupByTypes,
            List<Integer> groupByChannels,
            Optional<Integer> hashChannel,
            OperatorContext operatorContext,
            Optional<DataSize> maxPartialMemory,
            JoinCompiler joinCompiler,
            boolean yieldForMemoryReservation,
            Optional<Consumer<Long>> memoryConsumer)
    {
        this(accumulatorFactories,
                step,
                expectedGroups,
                groupByTypes,
                groupByChannels,
                hashChannel,
                operatorContext,
                maxPartialMemory,
                Optional.empty(),
                joinCompiler,
                yieldForMemoryReservation,
                ReserveType.REVOCABLE,
                memoryConsumer);
    }

    public InMemoryHashAggregationBuilder(
            List<AccumulatorFactory> accumulatorFactories,
            Step step,
            int expectedGroups,
            List<Type> groupByTypes,
            List<Integer> groupByChannels,
            Optional<Integer> hashChannel,
            OperatorContext operatorContext,
            Optional<DataSize> maxPartialMemory,
            Optional<Integer> overwriteIntermediateChannelOffset,
            JoinCompiler joinCompiler,
            boolean yieldForMemoryReservation,
            ReserveType reserveType,
            Optional<Consumer<Long>> memoryConsumer)
    {
        log.error(new Throwable(), getId() + " creating InMemoryHashAggregationBuilder with reserveType " + reserveType.name() + " memoryConsumer is present=" + memoryConsumer.isPresent() + "--- is partial = " + step.isOutputPartial());
        // reserveType is REVOCABLE implies current InMemoryHashAggregationBuilder is built from SpillableHashAggregationBuilder
        //  and it will accept a customized memoryConsumer for memory update
        if (reserveType == ReserveType.REVOCABLE) {
            checkArgument(memoryConsumer.isPresent(),
                    "memoryConsumer must be present when reserve type is REVOCABLE");
        }

        this.reserveType = reserveType;
        if (memoryConsumer.isPresent()) {
            this.memoryConsumer = memoryConsumer.get();
        }
        else {
            this.memoryConsumer = this::updateMemory;
        }

        UpdateMemory updateMemory;
        if (yieldForMemoryReservation) {
            updateMemory = this::updateMemoryWithYieldInfo;
        }
        else {
            // Report memory usage but do not yield for memory.
            // This is specially used for spillable hash aggregation operator.
            updateMemory = () -> {
                updateMemoryWithYieldInfo();
                return true;
            };
        }
        this.groupByHash = createGroupByHash(
                groupByTypes,
                Ints.toArray(groupByChannels),
                hashChannel,
                expectedGroups,
                isDictionaryAggregationEnabled(operatorContext.getSession()),
                joinCompiler,
                updateMemory);
        this.operatorContext = operatorContext;
        this.partial = step.isOutputPartial();
        this.maxPartialMemory = maxPartialMemory.map(dataSize -> OptionalLong.of(dataSize.toBytes())).orElseGet(OptionalLong::empty);
        this.systemMemoryContext = operatorContext.newLocalSystemMemoryContext(InMemoryHashAggregationBuilder.class.getSimpleName());
        this.localUserMemoryContext = operatorContext.localUserMemoryContext();

        // wrapper each function with an aggregator
        ImmutableList.Builder<Aggregator> builder = ImmutableList.builder();
        requireNonNull(accumulatorFactories, "accumulatorFactories is null");
        for (int i = 0; i < accumulatorFactories.size(); i++) {
            AccumulatorFactory accumulatorFactory = accumulatorFactories.get(i);
            Optional<Integer> overwriteIntermediateChannel = Optional.empty();
            if (overwriteIntermediateChannelOffset.isPresent()) {
                overwriteIntermediateChannel = Optional.of(overwriteIntermediateChannelOffset.get() + i);
            }
            builder.add(new Aggregator(accumulatorFactory, step, overwriteIntermediateChannel, updateMemory));
        }
        aggregators = builder.build();
    }

    @Override
    public void close()
    {
        memoryConsumer.accept(0L);
    }

    @Override
    public Work<?> processPage(Page page)
    {
        if (aggregators.isEmpty()) {
            return groupByHash.addPage(page);
        }
        else {
            return new TransformWork<>(
                    groupByHash.getGroupIds(page),
                    groupByIdBlock -> {
                        for (Aggregator aggregator : aggregators) {
                            aggregator.processPage(groupByIdBlock, page);
                        }
                        // we do not need any output from TransformWork for this case
                        return null;
                    });
        }
    }

    @Override
    public void updateMemory()
    {
        updateMemoryWithYieldInfo();
    }

    @Override
    public boolean isFull()
    {
        return full;
    }

    @Override
    public void recordHashCollisions(HashCollisionsCounter hashCollisionsCounter)
    {
        hashCollisionsCounter.recordHashCollision(groupByHash.getHashCollisions(), groupByHash.getExpectedHashCollisions());
    }

    public long getHashCollisions()
    {
        return groupByHash.getHashCollisions();
    }

    public double getExpectedHashCollisions()
    {
        return groupByHash.getExpectedHashCollisions();
    }

    @Override
    public ListenableFuture<?> startMemoryRevoke()
    {
        throw new UnsupportedOperationException("startMemoryRevoke not supported for InMemoryHashAggregationBuilder");
    }

    @Override
    public void finishMemoryRevoke()
    {
        throw new UnsupportedOperationException("finishMemoryRevoke not supported for InMemoryHashAggregationBuilder");
    }

    public long getSizeInMemory()
    {
        long sizeInMemory = groupByHash.getEstimatedSize();
        for (Aggregator aggregator : aggregators) {
            sizeInMemory += aggregator.getEstimatedSize();
        }
        return sizeInMemory;
    }

    /**
     * building hash sorted results requires memory for sorting group IDs.
     * This method returns size of that memory requirement.
     */
    public long getGroupIdsSortingSize()
    {
        return getGroupCount() * Integer.BYTES;
    }

    public void setOutputPartial()
    {
        for (Aggregator aggregator : aggregators) {
            aggregator.setOutputPartial();
        }
    }

    public int getKeyChannels()
    {
        return groupByHash.getTypes().size();
    }

    public long getGroupCount()
    {
        return groupByHash.getGroupCount();
    }

    @Override
    public WorkProcessor<Page> buildResult()
    {
        for (Aggregator aggregator : aggregators) {
            aggregator.prepareFinal();
        }
        return buildResult(consecutiveGroupIds());
    }

    public WorkProcessor<Page> buildHashSortedResult()
    {
        return buildResult(groupByHash.getHashSortedGroupIds());
    }

    public List<Type> buildIntermediateTypes()
    {
        ArrayList<Type> types = new ArrayList<>(groupByHash.getTypes());
        for (InMemoryHashAggregationBuilder.Aggregator aggregator : aggregators) {
            types.add(aggregator.getIntermediateType());
        }
        return types;
    }

    @VisibleForTesting
    public int getCapacity()
    {
        return groupByHash.getCapacity();
    }

    private WorkProcessor<Page> buildResult(IntIterator groupIds)
    {
        final PageBuilder pageBuilder = new PageBuilder(buildTypes());
        return WorkProcessor.create(() -> {
            if (!groupIds.hasNext()) {
                return ProcessState.finished();
            }

            pageBuilder.reset();

            List<Type> types = groupByHash.getTypes();
            while (!pageBuilder.isFull() && groupIds.hasNext()) {
                int groupId = groupIds.nextInt();

                groupByHash.appendValuesTo(groupId, pageBuilder, 0);

                pageBuilder.declarePosition();
                for (int i = 0; i < aggregators.size(); i++) {
                    Aggregator aggregator = aggregators.get(i);
                    BlockBuilder output = pageBuilder.getBlockBuilder(types.size() + i);
                    aggregator.evaluate(groupId, output);
                }
            }

            return ProcessState.ofResult(pageBuilder.build());
        });
    }

    public List<Type> buildTypes()
    {
        ArrayList<Type> types = new ArrayList<>(groupByHash.getTypes());
        for (Aggregator aggregator : aggregators) {
            types.add(aggregator.getType());
        }
        return types;
    }

    /**
     * Update memory usage with extra memory needed.
     *
     * @return true to if the reservation is within the limit
     */
    // TODO: update in the interface after the new memory tracking framework is landed
    // Essentially we would love to have clean interfaces to support both pushing and pulling memory usage
    // The following implementation is a hybrid model, where the push model is going to call the pull model causing reentrancy
    private boolean updateMemoryWithYieldInfo()
    {
        long memorySize = getSizeInMemory();
        log.error(new Throwable(), getId() + ": IN updateMemoryWithYieldInfo with memorySize=" + memorySize);
        if (partial && maxPartialMemory.isPresent()) {
            memoryConsumer.accept(memorySize);
            full = (memorySize > maxPartialMemory.getAsLong());
            return true;
        }
        // Operator/driver will be blocked on memory after we call setBytes.
        // If memory is not available, once we return, this operator will be blocked until memory is available.
        memoryConsumer.accept(memorySize);
        // If memory is not available, inform the caller that we cannot proceed for allocation.
        return operatorContext.isWaitingForMemory().isDone();
    }

    private void updateMemory(long memorySize)
    {
        log.error(getId() + "updatingMemory with reserveType=%s", reserveType);
        switch (reserveType) {
            case USER:
                localUserMemoryContext.setBytes(memorySize);
                break;
            case SYSTEM:
                systemMemoryContext.setBytes(memorySize);
                break;
            default:
                throw new AssertionError("InMemoryHashAggregationBuilder do not support reserve type: " + reserveType);
        }
    }

    private IntIterator consecutiveGroupIds()
    {
        return IntIterators.fromTo(0, groupByHash.getGroupCount());
    }

    private static class Aggregator
    {
        private final GroupedAccumulator aggregation;
        private AggregationNode.Step step;
        private final int intermediateChannel;

        private Aggregator(
                AccumulatorFactory accumulatorFactory,
                AggregationNode.Step step,
                Optional<Integer> overwriteIntermediateChannel,
                UpdateMemory updateMemory)
        {
            if (step.isInputRaw()) {
                this.intermediateChannel = -1;
                this.aggregation = accumulatorFactory.createGroupedAccumulator(updateMemory);
            }
            else if (overwriteIntermediateChannel.isPresent()) {
                this.intermediateChannel = overwriteIntermediateChannel.get();
                this.aggregation = accumulatorFactory.createGroupedIntermediateAccumulator(updateMemory);
            }
            else {
                checkArgument(accumulatorFactory.getInputChannels().size() == 1, "expected 1 input channel for intermediate aggregation");
                this.intermediateChannel = accumulatorFactory.getInputChannels().get(0);
                this.aggregation = accumulatorFactory.createGroupedIntermediateAccumulator(updateMemory);
            }
            this.step = step;
        }

        public long getEstimatedSize()
        {
            return aggregation.getEstimatedSize();
        }

        public Type getType()
        {
            if (step.isOutputPartial()) {
                return aggregation.getIntermediateType();
            }
            else {
                return aggregation.getFinalType();
            }
        }

        public void processPage(GroupByIdBlock groupIds, Page page)
        {
            if (step.isInputRaw()) {
                aggregation.addInput(groupIds, page);
            }
            else {
                aggregation.addIntermediate(groupIds, page.getBlock(intermediateChannel));
            }
        }

        public void prepareFinal()
        {
            aggregation.prepareFinal();
        }

        public void evaluate(int groupId, BlockBuilder output)
        {
            if (step.isOutputPartial()) {
                aggregation.evaluateIntermediate(groupId, output);
            }
            else {
                aggregation.evaluateFinal(groupId, output);
            }
        }

        public void setOutputPartial()
        {
            step = AggregationNode.Step.partialOutput(step);
        }

        public Type getIntermediateType()
        {
            return aggregation.getIntermediateType();
        }
    }

    public static List<Type> toTypes(List<? extends Type> groupByType, Step step, List<AccumulatorFactory> factories, Optional<Integer> hashChannel)
    {
        ImmutableList.Builder<Type> types = ImmutableList.builder();
        types.addAll(groupByType);
        if (hashChannel.isPresent()) {
            types.add(BIGINT);
        }
        for (AccumulatorFactory factory : factories) {
            // Create an aggregator just to figure out the output type
            // It is fine not to specify a memory reservation callback as it doesn't accept any input
            types.add(new Aggregator(factory, step, Optional.empty(), UpdateMemory.NOOP).getType());
        }
        return types.build();
    }
    private String getId()
    {
        return operatorContext.getOperatorType() + "-" + this.partial + "-" + operatorContext.getOperatorId() + "-" + Thread.currentThread().getId() + ":";
    }
}
