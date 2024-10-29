/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.CompositeStateHandle;
import org.apache.flink.runtime.state.SharedStateRegistry;
import org.apache.flink.runtime.state.memory.ByteStreamStateHandle;
import org.apache.flink.util.CollectionUtil;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Simple container class which contains the raw/managed operator state and key-group state handles
 * from all subtasks of an operator and therefore represents the complete state of a logical
 * operator.
 */
public class OperatorState implements CompositeStateHandle {

    private static final long serialVersionUID = -4845578005863201810L;

    /** The id of the operator. */
    private final OperatorID operatorID;

    /** The handles to states created by the parallel tasks: subtaskIndex -> subtaskstate. */
    private final Map<Integer, OperatorSubtaskState> operatorSubtaskStates;

    /** The state of the operator coordinator. Null, if no such state exists. */
    @Nullable private ByteStreamStateHandle coordinatorState;

    /** The parallelism of the operator when it was checkpointed. */
    private final int parallelism;

    /**
     * The maximum parallelism (for number of KeyGroups) of the operator when the job was first
     * created.
     */
    private final int maxParallelism;

    public OperatorState(OperatorID operatorID, int parallelism, int maxParallelism) {
        if (parallelism > maxParallelism) {
            throw new IllegalArgumentException(
                    String.format(
                            "Parallelism %s is not smaller or equal to max parallelism %s.",
                            parallelism, maxParallelism));
        }

        this.operatorID = operatorID;

        this.operatorSubtaskStates = CollectionUtil.newLinkedHashMapWithExpectedSize(parallelism);

        this.parallelism = parallelism;
        this.maxParallelism = maxParallelism;
    }

    public OperatorID getOperatorID() {
        return operatorID;
    }

    public boolean isFullyFinished() {
        return false;
    }

    public void putState(int subtaskIndex, OperatorSubtaskState subtaskState) {
        Preconditions.checkNotNull(subtaskState);

        if (subtaskIndex < 0 || subtaskIndex >= parallelism) {
            throw new IndexOutOfBoundsException(
                    "The given sub task index "
                            + subtaskIndex
                            + " exceeds the maximum number of sub tasks "
                            + operatorSubtaskStates.size());
        } else {
            operatorSubtaskStates.put(subtaskIndex, subtaskState);
        }
    }

    public OperatorSubtaskState getState(int subtaskIndex) {
        if (subtaskIndex < 0 || subtaskIndex >= parallelism) {
            throw new IndexOutOfBoundsException(
                    "The given sub task index "
                            + subtaskIndex
                            + " exceeds the maximum number of sub tasks "
                            + operatorSubtaskStates.size());
        } else {
            return operatorSubtaskStates.get(subtaskIndex);
        }
    }

    public void setCoordinatorState(@Nullable ByteStreamStateHandle coordinatorState) {
        checkState(this.coordinatorState == null, "coordinator state already set");
        this.coordinatorState = coordinatorState;
    }

    @Nullable
    public ByteStreamStateHandle getCoordinatorState() {
        return coordinatorState;
    }

    public Map<Integer, OperatorSubtaskState> getSubtaskStates() {
        return Collections.unmodifiableMap(operatorSubtaskStates);
    }

    public Collection<OperatorSubtaskState> getStates() {
        return operatorSubtaskStates.values();
    }

    public int getNumberCollectedStates() {
        return operatorSubtaskStates.size();
    }

    public int getParallelism() {
        return parallelism;
    }

    public int getMaxParallelism() {
        return maxParallelism;
    }

    public OperatorState copyWithNewOperatorID(OperatorID newOperatorId) {
        OperatorState newState = new OperatorState(newOperatorId, parallelism, maxParallelism);
        operatorSubtaskStates.forEach(newState::putState);
        return newState;
    }

    public OperatorState copyAndDiscardInFlightData() {
        OperatorState newState = new OperatorState(operatorID, parallelism, maxParallelism);

        for (Map.Entry<Integer, OperatorSubtaskState> originalSubtaskStateEntry :
                operatorSubtaskStates.entrySet()) {
            newState.putState(
                    originalSubtaskStateEntry.getKey(),
                    originalSubtaskStateEntry
                            .getValue()
                            .toBuilder()
                            .setResultSubpartitionState(StateObjectCollection.empty())
                            .setInputChannelState(StateObjectCollection.empty())
                            .build());
        }

        return newState;
    }

    @Override
    public void discardState() throws Exception {
        for (OperatorSubtaskState operatorSubtaskState : operatorSubtaskStates.values()) {
            operatorSubtaskState.discardState();
        }

        if (coordinatorState != null) {
            coordinatorState.discardState();
        }
    }

    @Override
    public void registerSharedStates(SharedStateRegistry sharedStateRegistry, long checkpointID) {
        for (OperatorSubtaskState operatorSubtaskState : operatorSubtaskStates.values()) {
            operatorSubtaskState.registerSharedStates(sharedStateRegistry, checkpointID);
        }
    }

    public boolean hasSubtaskStates() {
        return operatorSubtaskStates.size() > 0;
    }

    @Override
    public long getStateSize() {
        long result = coordinatorState == null ? 0L : coordinatorState.getStateSize();

        for (int i = 0; i < parallelism; i++) {
            OperatorSubtaskState operatorSubtaskState = operatorSubtaskStates.get(i);
            if (operatorSubtaskState != null) {
                result += operatorSubtaskState.getStateSize();
            }
        }

        return result;
    }

    @Override
    public long getCheckpointedSize() {
        long result = coordinatorState == null ? 0L : coordinatorState.getStateSize();

        for (int i = 0; i < parallelism; i++) {
            OperatorSubtaskState operatorSubtaskState = operatorSubtaskStates.get(i);
            if (operatorSubtaskState != null) {
                result += operatorSubtaskState.getCheckpointedSize();
            }
        }

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof OperatorState) {
            OperatorState other = (OperatorState) obj;

            boolean same1 = operatorID.equals(other.operatorID);
            boolean same2 = parallelism == other.parallelism;
            boolean same3;

            if (coordinatorState == null && other.coordinatorState == null){
                same3 = true;
            }else if (coordinatorState == null || other.coordinatorState == null){
                same3 = false;
            }else{
                same3 = coordinatorState.getStateSize() == other.coordinatorState.getStateSize();
            }
            boolean same4 = operatorSubtaskStates.size() == other.operatorSubtaskStates.size();

            return same1 && same2 && same3 && same4;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return parallelism + 31 * Objects.hash(operatorID, operatorSubtaskStates);
    }

    @Override
    public String toString() {
        // KvStates are always null in 1.1. Don't print this as it might
        // confuse users that don't care about how we store it internally.
        return "OperatorState("
                + "operatorID: "
                + operatorID
                + ", parallelism: "
                + parallelism
                + ", maxParallelism: "
                + maxParallelism
                + ", coordinatorState: "
                + (coordinatorState == null ? "(none)" : coordinatorState.getStateSize() + " bytes")
                + ", sub task states: "
                + operatorSubtaskStates.size()
                + ", total size (bytes): "
                + getStateSize()
                + ')';
    }
}
