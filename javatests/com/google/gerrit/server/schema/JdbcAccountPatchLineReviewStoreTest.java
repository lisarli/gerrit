// Copyright (C) 2025 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.schema;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineMarker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JdbcAccountPatchLineReviewStoreTest {

  private H2AccountPatchLineReviewStore store;

  @Before
  public void setUp() {
    store = JdbcAccountPatchLineReviewStore.createInMemoryForTesting();
    store.createTableIfNotExists();
  }

  @After
  public void tearDown() {
    store.dropTableIfExists();
  }

  private static PatchSet.Id psId(int changeId, int psNum) {
    return PatchSet.id(Change.id(changeId), psNum);
  }

  private static Account.Id accountId(int id) {
    return Account.id(id);
  }

  @Test
  public void markThenList_returnsMarker() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps, account, "file.txt", Side.REVISION, 5);

    ImmutableList<LineMarker> markers = store.listReviewedLines(ps, account, "file.txt");
    assertThat(markers).containsExactly(LineMarker.create(Side.REVISION, 5));
  }

  @Test
  public void markTwice_isIdempotent() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps, account, "file.txt", Side.REVISION, 5);
    store.markReviewedLine(ps, account, "file.txt", Side.REVISION, 5); // no exception

    ImmutableList<LineMarker> markers = store.listReviewedLines(ps, account, "file.txt");
    assertThat(markers).hasSize(1);
  }

  @Test
  public void clearExistingMarker_removesRow() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps, account, "file.txt", Side.REVISION, 5);
    store.clearReviewedLine(ps, account, "file.txt", Side.REVISION, 5);

    ImmutableList<LineMarker> markers = store.listReviewedLines(ps, account, "file.txt");
    assertThat(markers).isEmpty();
  }

  @Test
  public void clearNonExistentMarker_isNoOp() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    // Should not throw.
    store.clearReviewedLine(ps, account, "file.txt", Side.REVISION, 99);

    assertThat(store.listReviewedLines(ps, account, "file.txt")).isEmpty();
  }

  @Test
  public void multipleLines_allReturned() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps, account, "file.txt", Side.REVISION, 3);
    store.markReviewedLine(ps, account, "file.txt", Side.REVISION, 7);
    store.markReviewedLine(ps, account, "file.txt", Side.PARENT, 3);

    ImmutableList<LineMarker> markers = store.listReviewedLines(ps, account, "file.txt");
    assertThat(markers)
        .containsExactlyElementsIn(
            ImmutableList.of(
                LineMarker.create(Side.REVISION, 3),
                LineMarker.create(Side.REVISION, 7),
                LineMarker.create(Side.PARENT, 3)));
  }

  @Test
  public void isolation_differentAccount_noLeakage() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id accountA = accountId(10);
    Account.Id accountB = accountId(20);

    store.markReviewedLine(ps, accountA, "file.txt", Side.REVISION, 5);

    assertThat(store.listReviewedLines(ps, accountB, "file.txt")).isEmpty();
  }

  @Test
  public void isolation_differentPatchSet_noLeakage() {
    PatchSet.Id ps1 = psId(1, 1);
    PatchSet.Id ps2 = psId(1, 2);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps1, account, "file.txt", Side.REVISION, 5);

    assertThat(store.listReviewedLines(ps2, account, "file.txt")).isEmpty();
  }

  @Test
  public void isolation_differentFilePath_noLeakage() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps, account, "file_a.txt", Side.REVISION, 5);

    assertThat(store.listReviewedLines(ps, account, "file_b.txt")).isEmpty();
  }

  @Test
  public void listReviewedLines_onlyReturnsMatchingFile() {
    PatchSet.Id ps = psId(1, 1);
    Account.Id account = accountId(10);

    store.markReviewedLine(ps, account, "file_a.txt", Side.REVISION, 5);
    store.markReviewedLine(ps, account, "file_b.txt", Side.REVISION, 10);

    ImmutableList<LineMarker> markers = store.listReviewedLines(ps, account, "file_a.txt");
    assertThat(markers).containsExactly(LineMarker.create(Side.REVISION, 5));
  }
}
