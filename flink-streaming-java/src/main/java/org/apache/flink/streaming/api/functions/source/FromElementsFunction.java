/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.functions.source;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@PublicEvolving
public class FromElementsFunction<T> implements SourceFunction<T> {

	private static final long serialVersionUID = 1L;

	/** The (de)serializer to be used for the data elements. */
	private final TypeSerializer<T> serializer;

	/** The actual data elements, in serialized form. */
	private final byte[] elementsSerialized;

	/** The number of serialized elements. */
	private final int numElements;

	/** The number of elements emitted already. */
	private volatile int numElementsEmitted;

	/** The number of elements to skip initially. */
	private volatile int numElementsToSkip;

	/** Flag to make the source cancelable. */
	private volatile boolean isRunning = true;

	private transient ListState<Integer> checkpointedState;

	public FromElementsFunction(TypeSerializer<T> serializer, Iterable<T> elements) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputViewStreamWrapper wrapper = new DataOutputViewStreamWrapper(baos);

		int count = 0;
		try {
			for (T element : elements) {
				serializer.serialize(element, wrapper);
				count++;
			}
		}
		catch (Exception e) {
			throw new IOException("Serializing the source elements failed: " + e.getMessage(), e);
		}

		this.serializer = serializer;
		this.elementsSerialized = baos.toByteArray();
		this.numElements = count;
	}



	@Override
	public void run(SourceContext<T> ctx) throws Exception {
		ByteArrayInputStream bais = new ByteArrayInputStream(elementsSerialized);
		final DataInputView input = new DataInputViewStreamWrapper(bais);

		// if we are restored from a checkpoint and need to skip elements, skip them now.
		int toSkip = numElementsToSkip;
		if (toSkip > 0) {
			try {
				while (toSkip > 0) {
					serializer.deserialize(input);
					toSkip--;
				}
			}
			catch (Exception e) {
				throw new IOException("Failed to deserialize an element from the source. " +
						"If you are using user-defined serialization (Value and Writable types), check the " +
						"serialization functions.\nSerializer is " + serializer, e);
			}

			this.numElementsEmitted = this.numElementsToSkip;
		}

		final Object lock = ctx.getCheckpointLock();

		while (isRunning && numElementsEmitted < numElements) {
			T next;
			try {
				next = serializer.deserialize(input);
			}
			catch (Exception e) {
				throw new IOException("Failed to deserialize an element from the source. " +
						"If you are using user-defined serialization (Value and Writable types), check the " +
						"serialization functions.\nSerializer is " + serializer, e);
			}

			synchronized (lock) {
				ctx.collect(next);
				numElementsEmitted++;
			}
		}
	}

	@Override
	public void cancel() {
		isRunning = false;
	}
}