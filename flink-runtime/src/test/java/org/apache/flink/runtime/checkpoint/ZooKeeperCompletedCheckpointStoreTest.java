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

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.Pathable;
import org.apache.curator.utils.EnsurePath;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.concurrent.Executors;
import org.apache.flink.runtime.state.RetrievableStateHandle;
import org.apache.flink.runtime.zookeeper.RetrievableStateStorageHelper;
import org.apache.flink.runtime.zookeeper.ZooKeeperStateHandleStore;
import org.apache.flink.util.TestLogger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ZooKeeperCompletedCheckpointStore.class)
public class ZooKeeperCompletedCheckpointStoreTest extends TestLogger {

	@Test
	public void testPathConversion() {
		final long checkpointId = 42L;

		final String path = ZooKeeperCompletedCheckpointStore.checkpointIdToPath(checkpointId);

		assertEquals(checkpointId, ZooKeeperCompletedCheckpointStore.pathToCheckpointId(path));
	}

	/**
	 * Tests that the completed checkpoint store can retrieve all checkpoints stored in ZooKeeper
	 * and ignores those which cannot be retrieved via their state handles.
	 */
	@Test
	public void testCheckpointRecovery() throws Exception {
		final List<Tuple2<RetrievableStateHandle<CompletedCheckpoint>, String>> checkpointsInZooKeeper = new ArrayList<>(4);

		final CompletedCheckpoint completedCheckpoint1 = mock(CompletedCheckpoint.class);
		when(completedCheckpoint1.getCheckpointID()).thenReturn(1L);
		final CompletedCheckpoint completedCheckpoint2 = mock(CompletedCheckpoint.class);
		when(completedCheckpoint2.getCheckpointID()).thenReturn(2L);

		final Collection<Long> expectedCheckpointIds = new HashSet<>(2);
		expectedCheckpointIds.add(1L);
		expectedCheckpointIds.add(2L);

		final RetrievableStateHandle<CompletedCheckpoint> failingRetrievableStateHandle = mock(RetrievableStateHandle.class);
		when(failingRetrievableStateHandle.retrieveState()).thenThrow(new Exception("Test exception"));

		final RetrievableStateHandle<CompletedCheckpoint> retrievableStateHandle1 = mock(RetrievableStateHandle.class);
		when(retrievableStateHandle1.retrieveState()).thenReturn(completedCheckpoint1);

		final RetrievableStateHandle<CompletedCheckpoint> retrievableStateHandle2 = mock(RetrievableStateHandle.class);
		when(retrievableStateHandle2.retrieveState()).thenReturn(completedCheckpoint2);

		checkpointsInZooKeeper.add(Tuple2.of(retrievableStateHandle1, "/foobar1"));
		checkpointsInZooKeeper.add(Tuple2.of(failingRetrievableStateHandle, "/failing1"));
		checkpointsInZooKeeper.add(Tuple2.of(retrievableStateHandle2, "/foobar2"));
		checkpointsInZooKeeper.add(Tuple2.of(failingRetrievableStateHandle, "/failing2"));

		final CuratorFramework client = mock(CuratorFramework.class, Mockito.RETURNS_DEEP_STUBS);
		final RetrievableStateStorageHelper<CompletedCheckpoint> storageHelperMock = mock(RetrievableStateStorageHelper.class);

		ZooKeeperStateHandleStore<CompletedCheckpoint> zooKeeperStateHandleStoreMock = spy(new ZooKeeperStateHandleStore<>(client, storageHelperMock, Executors.directExecutor()));
		whenNew(ZooKeeperStateHandleStore.class).withAnyArguments().thenReturn(zooKeeperStateHandleStoreMock);
		doReturn(checkpointsInZooKeeper).when(zooKeeperStateHandleStoreMock).getAllSortedByName();

		final int numCheckpointsToRetain = 1;

		// Mocking for the delete operation on the CuratorFramework client
		// It assures that the callback is executed synchronously

		final EnsurePath ensurePathMock = mock(EnsurePath.class);
		final CuratorEvent curatorEventMock = mock(CuratorEvent.class);
		when(curatorEventMock.getType()).thenReturn(CuratorEventType.DELETE);
		when(curatorEventMock.getResultCode()).thenReturn(0);
		when(client.newNamespaceAwareEnsurePath(anyString())).thenReturn(ensurePathMock);

		when(
			client
				.delete()
				.deletingChildrenIfNeeded()
				.inBackground(any(BackgroundCallback.class), any(Executor.class))
		).thenAnswer(new Answer<Pathable<Void>>() {
			@Override
			public Pathable<Void> answer(InvocationOnMock invocation) throws Throwable {
				final BackgroundCallback callback = (BackgroundCallback) invocation.getArguments()[0];

				Pathable<Void> result = mock(Pathable.class);

				when(result.forPath(anyString())).thenAnswer(new Answer<Void>() {
					@Override
					public Void answer(InvocationOnMock invocation) throws Throwable {

						callback.processResult(client, curatorEventMock);

						return null;
					}
				});

				return result;
			}
		});

		final String checkpointsPath = "foobar";
		final RetrievableStateStorageHelper<CompletedCheckpoint> stateSotrage = mock(RetrievableStateStorageHelper.class);

		ZooKeeperCompletedCheckpointStore zooKeeperCompletedCheckpointStore = new ZooKeeperCompletedCheckpointStore(
			numCheckpointsToRetain,
			client,
			checkpointsPath,
			stateSotrage,
			Executors.directExecutor());

		zooKeeperCompletedCheckpointStore.recover();

		CompletedCheckpoint latestCompletedCheckpoint = zooKeeperCompletedCheckpointStore.getLatestCheckpoint();

		// check that we return the latest retrievable checkpoint
		// this should remove the latest checkpoint because it is broken
		assertEquals(completedCheckpoint2.getCheckpointID(), latestCompletedCheckpoint.getCheckpointID());

		// this should remove the second broken checkpoint because we're iterating over all checkpoints
		List<CompletedCheckpoint> completedCheckpoints = zooKeeperCompletedCheckpointStore.getAllCheckpoints();

		Collection<Long> actualCheckpointIds = new HashSet<>(completedCheckpoints.size());

		for (CompletedCheckpoint completedCheckpoint : completedCheckpoints) {
			actualCheckpointIds.add(completedCheckpoint.getCheckpointID());
		}

		assertEquals(expectedCheckpointIds, actualCheckpointIds);

		// check that we did not discard any of the state handles which were retrieved
		verify(retrievableStateHandle1, never()).discardState();
		verify(retrievableStateHandle2, never()).discardState();

		// check that we have discarded the state handles which could not be retrieved
		verify(failingRetrievableStateHandle, times(2)).discardState();
	}
}
