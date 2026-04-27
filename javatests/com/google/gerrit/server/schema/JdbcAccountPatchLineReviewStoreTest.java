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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.changes.LineReviewedInput;
import com.google.gerrit.extensions.client.Comment.Range;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.extensions.client.Side;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineReviewAction;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineReviewHistoryEntry;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.PatchSetWithReviewedLines;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.ReviewedLine;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link JdbcAccountPatchLineReviewStore} using an in-memory H2 database.
 *
 * <p>These tests exercise the real SQL layer (INSERT, SELECT, DELETE) and verify that the JDBC
 * implementation correctly stores and retrieves line/region reviewed flags.
 */
public class JdbcAccountPatchLineReviewStoreTest {

  private static final Account.Id ACCOUNT_1 = Account.id(1);
  private static final Account.Id ACCOUNT_2 = Account.id(2);
  private static final Change.Id CHANGE_1 = Change.id(100);
  private static final PatchSet.Id PS_1 = PatchSet.id(CHANGE_1, 1);
  private static final PatchSet.Id PS_2 = PatchSet.id(CHANGE_1, 2);
  private static final String FILE_A = "src/Main.java";
  private static final String FILE_B = "src/Util.java";

  private H2AccountPatchLineReviewStore store;

  @Before
  public void setUp() {
    store = JdbcAccountPatchLineReviewStore.createInMemoryForTesting();
    store.start();
  }

  @After
  public void tearDown() throws Exception {
    try (Connection con = store.getConnection();
        Statement stmt = con.createStatement()) {
      stmt.executeUpdate("DROP TABLE IF EXISTS account_patch_line_reviews");
      stmt.executeUpdate("DROP TABLE IF EXISTS account_patch_line_review_history");
    }
  }

  // -- helpers --

  private static LineReviewedInput lineInput(int line) {
    LineReviewedInput input = new LineReviewedInput();
    input.line = line;
    input.side = Side.REVISION;
    return input;
  }

  private static LineReviewedInput lineInput(int line, Side side) {
    LineReviewedInput input = new LineReviewedInput();
    input.line = line;
    input.side = side;
    return input;
  }

  private static LineReviewedInput rangeInput(int line, int startLine, int startChar, int endLine, int endChar) {
    LineReviewedInput input = new LineReviewedInput();
    input.line = line;
    input.side = Side.REVISION;
    Range range = new Range();
    range.startLine = startLine;
    range.startCharacter = startChar;
    range.endLine = endLine;
    range.endCharacter = endChar;
    input.range = range;
    return input;
  }

  private void insertReviewedRow(
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      int line,
      ReviewStatus status,
      boolean tentativeCarryover)
      throws Exception {

    try (Connection con = store.getConnection();
        Statement stmt = con.createStatement()) {
      String sql =
          String.format(
              Locale.US,
              "INSERT INTO account_patch_line_reviews "
                  + "(account_id, change_id, patch_set_id, file_name, line_number, side, "
                  + "start_line, start_char, end_line, end_char, review_status, tentative_carryover) "
                  + "VALUES (%d, %d, %d, '%s', %d, %d, %d, %d, %d, %d, %d, %s)",
              accountId.get(),
              psId.changeId().get(),
              psId.get(),
              path,
              line,
              /* side=REVISION */ 1,
              line,
              0,
              line,
              0,
              status.toDbValue(),
              tentativeCarryover ? "TRUE" : "FALSE");
      stmt.executeUpdate(sql);
    }
  }

  // -- tests --

  @Test
  public void markAndFindLine() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    Optional<PatchSetWithReviewedLines> result = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);

    assertThat(result).isPresent();
    assertThat(result.get().lines()).hasSize(1);
    ReviewedLine line = result.get().lines().get(0);
    assertThat(line.lineNumber()).isEqualTo(5);
    assertThat(line.path()).isEqualTo(FILE_A);
    assertThat(line.getSide()).isEqualTo(Side.REVISION);
    assertThat(line.reviewStatus()).isEqualTo(ReviewStatus.READ);
  }

  @Test
  public void markIdempotent() {
    boolean first = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));
    boolean second = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    assertThat(first).isTrue();
    assertThat(second).isFalse();

    Optional<PatchSetWithReviewedLines> result = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);
    assertThat(result).isPresent();
    assertThat(result.get().lines()).hasSize(1);
  }

  @Test
  public void clearExistingLine() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));
    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    Optional<PatchSetWithReviewedLines> result = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);
    assertThat(result).isEmpty();
  }

  @Test
  public void clearNonExistent_noException() {
    // Should not throw even if the row does not exist.
    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(99));

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A)).isEmpty();
  }

  @Test
  public void isolationByAccount() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A)).isPresent();
    assertThat(store.findReviewedLines(PS_1, ACCOUNT_2, FILE_A)).isEmpty();
  }

  @Test
  public void isolationByPatchSet() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A)).isPresent();
    assertThat(store.findReviewedLines(PS_2, ACCOUNT_1, FILE_A)).isEmpty();
  }

  @Test
  public void findWithNullPath_returnsAllFiles() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_B, lineInput(2));

    Optional<PatchSetWithReviewedLines> all = store.findReviewedLines(PS_1, ACCOUNT_1, null);
    assertThat(all).isPresent();
    assertThat(all.get().lines()).hasSize(2);

    Optional<PatchSetWithReviewedLines> fileA = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);
    assertThat(fileA).isPresent();
    assertThat(fileA.get().lines()).hasSize(1);
    assertThat(fileA.get().lines().get(0).path()).isEqualTo(FILE_A);
  }

  @Test
  public void markAndFindWithRange() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, rangeInput(10, 10, 3, 12, 7));

    Optional<PatchSetWithReviewedLines> result = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);
    assertThat(result).isPresent();
    ReviewedLine line = result.get().lines().get(0);
    assertThat(line.lineNumber()).isEqualTo(10);
    assertThat(line.startLine()).isEqualTo(10);
    assertThat(line.startChar()).isEqualTo(3);
    assertThat(line.endLine()).isEqualTo(12);
    assertThat(line.endChar()).isEqualTo(7);
  }

  @Test
  public void markParentSide() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(3, Side.PARENT));

    Optional<PatchSetWithReviewedLines> result = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);
    assertThat(result).isPresent();
    assertThat(result.get().lines().get(0).getSide()).isEqualTo(Side.PARENT);
  }

  @Test
  public void clearByPatchSet_removesAllLinesForThatPatchSet() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_B, lineInput(2));
    var unused3 = store.markLineReviewed(PS_2, ACCOUNT_1, FILE_A, lineInput(3));

    store.clearLineReviewed(PS_1);

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, null)).isEmpty();
    assertThat(store.findReviewedLines(PS_2, ACCOUNT_1, FILE_A)).isPresent();
  }

  @Test
  public void clearByChangeId_removesAllPatchSetsForThatChange() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_2, ACCOUNT_1, FILE_A, lineInput(2));

    store.clearLineReviewed(CHANGE_1);

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, null)).isEmpty();
    assertThat(store.findReviewedLines(PS_2, ACCOUNT_1, null)).isEmpty();
  }

  @Test
  public void clearLineReviewedBy_removesAllLinesForThatAccount() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_2, FILE_A, lineInput(1));

    store.clearLineReviewedBy(ACCOUNT_1);

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A)).isEmpty();
    assertThat(store.findReviewedLines(PS_1, ACCOUNT_2, FILE_A)).isPresent();
  }

  @Test
  public void clearReadRestoresTentativeWhenCarryover() throws Exception {
    // Simulate a propagated row (tentative + carryover=true), then verify explicit read and clear
    // transitions: TENTATIVELY_READ -> READ -> TENTATIVELY_READ.
    insertReviewedRow(PS_1, ACCOUNT_1, FILE_A, 5, ReviewStatus.TENTATIVELY_READ, true);

    boolean upgraded = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));
    assertThat(upgraded).isTrue();
    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A).get().lines().get(0).reviewStatus())
        .isEqualTo(ReviewStatus.READ);

    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    Optional<PatchSetWithReviewedLines> after = store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A);
    assertThat(after).isPresent();
    assertThat(after.get().lines()).hasSize(1);
    assertThat(after.get().lines().get(0).reviewStatus())
        .isEqualTo(ReviewStatus.TENTATIVELY_READ);
  }

  @Test
  public void clearTentativeRowRemovesLine() throws Exception {
    insertReviewedRow(PS_1, ACCOUNT_1, FILE_A, 7, ReviewStatus.TENTATIVELY_READ, true);

    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(7));

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, FILE_A)).isEmpty();
  }

  @Test
  public void accountsWithLineReviews_returnsDistinctAccounts() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_2, FILE_B, lineInput(2));

    assertThat(store.accountsWithLineReviews(PS_1))
        .containsExactlyElementsIn(ImmutableSet.of(ACCOUNT_1, ACCOUNT_2));
    assertThat(store.accountsWithLineReviews(PS_2)).isEmpty();
  }

  @Test
  public void insertPropagatedTentativeReviews_insertsTentativeWithCarryover() {
    ReviewedLine line =
        ReviewedLine.create(
            FILE_A, 4, (short) 1, 4, 0, 4, 0, ReviewStatus.READ);
    store.insertPropagatedTentativeReviews(PS_2, ACCOUNT_1, ImmutableList.of(line));

    Optional<PatchSetWithReviewedLines> found =
        store.findReviewedLines(PS_2, ACCOUNT_1, FILE_A);
    assertThat(found).isPresent();
    ReviewedLine stored = found.get().lines().get(0);
    // JDBC insertion ignores incoming status and persists propagated rows as tentative carryover.
    assertThat(stored.reviewStatus()).isEqualTo(ReviewStatus.TENTATIVELY_READ);
    assertThat(stored.lineNumber()).isEqualTo(4);
  }

  @Test
  public void insertPropagatedTentativeReviews_skipsExistingKey() {
    ReviewedLine line =
        ReviewedLine.create(
            FILE_A, 4, (short) 1, 4, 0, 4, 0, ReviewStatus.TENTATIVELY_READ);
    store.insertPropagatedTentativeReviews(PS_1, ACCOUNT_1, ImmutableList.of(line));
    store.insertPropagatedTentativeReviews(PS_1, ACCOUNT_1, ImmutableList.of(line));

    assertThat(store.findReviewedLines(PS_1, ACCOUNT_1, null).get().lines()).hasSize(1);
  // -- tests for findAllReviewedLines --

  @Test
  public void findAllReviewedLines_noMarkers_returnsEmptyMap() {
    ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> result =
        store.findAllReviewedLines(PS_1, FILE_A);

    assertThat(result).isEmpty();
  }

  @Test
  public void findAllReviewedLines_singleAccount_returnsLines() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(3));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(7));

    ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> result =
        store.findAllReviewedLines(PS_1, FILE_A);

    assertThat(result).hasSize(1);
    assertThat(result).containsKey(ACCOUNT_1);
    assertThat(result.get(ACCOUNT_1)).hasSize(2);
    assertThat(result.get(ACCOUNT_1).get(0).lineNumber()).isEqualTo(3);
    assertThat(result.get(ACCOUNT_1).get(1).lineNumber()).isEqualTo(7);
  }

  @Test
  public void findAllReviewedLines_multipleAccounts_groupedByAccount() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_2, FILE_A, lineInput(5));
    var unused3 = store.markLineReviewed(PS_1, ACCOUNT_2, FILE_A, lineInput(10));

    ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> result =
        store.findAllReviewedLines(PS_1, FILE_A);

    assertThat(result).hasSize(2);
    assertThat(result.get(ACCOUNT_1)).hasSize(1);
    assertThat(result.get(ACCOUNT_1).get(0).lineNumber()).isEqualTo(1);
    assertThat(result.get(ACCOUNT_2)).hasSize(2);
    assertThat(result.get(ACCOUNT_2).get(0).lineNumber()).isEqualTo(5);
    assertThat(result.get(ACCOUNT_2).get(1).lineNumber()).isEqualTo(10);
  }

  @Test
  public void findAllReviewedLines_isolatedByFile() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_2, FILE_B, lineInput(2));

    ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> resultA =
        store.findAllReviewedLines(PS_1, FILE_A);
    ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> resultB =
        store.findAllReviewedLines(PS_1, FILE_B);

    assertThat(resultA).hasSize(1);
    assertThat(resultA).containsKey(ACCOUNT_1);
    assertThat(resultB).hasSize(1);
    assertThat(resultB).containsKey(ACCOUNT_2);
  }

  @Test
  public void findAllReviewedLines_isolatedByPatchSet() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_2, ACCOUNT_2, FILE_A, lineInput(2));

    ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> result =
        store.findAllReviewedLines(PS_1, FILE_A);

    assertThat(result).hasSize(1);
    assertThat(result).containsKey(ACCOUNT_1);
    assertThat(result).doesNotContainKey(ACCOUNT_2);
  }

  // -- history tests --

  @Test
  public void markLogsMarkedEntry() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    ImmutableList<LineReviewHistoryEntry> history =
        store.findLineReviewHistory(CHANGE_1);

    assertThat(history).hasSize(1);
    assertThat(history.get(0).action()).isEqualTo(LineReviewAction.MARKED);
    assertThat(history.get(0).path()).isEqualTo(FILE_A);
    assertThat(history.get(0).lineNumber()).isEqualTo(5);
  }

  @Test
  public void clearLogsUnmarkedEntry() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));
    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(5));

    ImmutableList<LineReviewHistoryEntry> history =
        store.findLineReviewHistory(CHANGE_1);

    assertThat(history).hasSize(2);
    assertThat(history.get(0).action()).isEqualTo(LineReviewAction.MARKED);
    assertThat(history.get(1).action()).isEqualTo(LineReviewAction.UNMARKED);
  }

  @Test
  public void markUnmarkMark_threeEntriesInOrder() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(3));
    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(3));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(3));

    ImmutableList<LineReviewHistoryEntry> history =
        store.findLineReviewHistory(CHANGE_1);

    assertThat(history).hasSize(3);
    assertThat(history.get(0).action()).isEqualTo(LineReviewAction.MARKED);
    assertThat(history.get(1).action()).isEqualTo(LineReviewAction.UNMARKED);
    assertThat(history.get(2).action()).isEqualTo(LineReviewAction.MARKED);
  }

  @Test
  public void historyUnifiedAcrossAccounts() {
    var unused1 = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    var unused2 = store.markLineReviewed(PS_1, ACCOUNT_2, FILE_A, lineInput(2));

    ImmutableList<LineReviewHistoryEntry> history = store.findLineReviewHistory(CHANGE_1);

    assertThat(history).hasSize(2);
    assertThat(history.stream().map(LineReviewHistoryEntry::accountId))
        .containsExactly(ACCOUNT_1, ACCOUNT_2)
        .inOrder();
  }

  @Test
  public void bulkClear_doesNotLogHistory() {
    var unused = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(1));
    store.clearLineReviewed(PS_1);

    ImmutableList<LineReviewHistoryEntry> history =
        store.findLineReviewHistory(CHANGE_1);

    assertThat(history).hasSize(1);
    assertThat(history.get(0).action()).isEqualTo(LineReviewAction.MARKED);
  }

  @Test
  public void idempotentMark_doesNotLogDuplicate() {
    boolean first = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(7));
    boolean second = store.markLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(7));

    assertThat(first).isTrue();
    assertThat(second).isFalse();

    ImmutableList<LineReviewHistoryEntry> history =
        store.findLineReviewHistory(CHANGE_1);

    assertThat(history).hasSize(1);
    assertThat(history.get(0).action()).isEqualTo(LineReviewAction.MARKED);
  }

  @Test
  public void clearNonExistent_doesNotLogHistory() {
    store.clearLineReviewed(PS_1, ACCOUNT_1, FILE_A, lineInput(99));

    ImmutableList<LineReviewHistoryEntry> history =
        store.findLineReviewHistory(CHANGE_1);

    assertThat(history).isEmpty();
  }
}
