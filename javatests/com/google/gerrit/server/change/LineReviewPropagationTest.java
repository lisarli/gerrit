// Copyright (C) 2026 The Android Open Source Project
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

package com.google.gerrit.server.change;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.server.CommentsUtil;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.PatchSetWithReviewedLines;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.ReviewedLine;
import com.google.gerrit.server.patch.ComparisonType;
import com.google.gerrit.server.patch.DiffOperations;
import com.google.gerrit.server.patch.DiffOptions;
import com.google.gerrit.server.patch.filediff.Edit;
import com.google.gerrit.server.patch.filediff.FileDiffOutput;
import com.google.gerrit.server.patch.filediff.TaggedEdit;
import com.google.gerrit.server.plugincontext.PluginContext.ExtensionImplConsumer;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link LineReviewPropagation}. Propagation is revision-side only; see {@code
 * ignoresParentSideMarkers}.
 */
@RunWith(MockitoJUnitRunner.class)
public class LineReviewPropagationTest {
  private static final Project.NameKey PROJECT = Project.nameKey("test");
  private static final Change.Id CHANGE_ID = Change.id(1);
  private static final Account.Id ACCOUNT_ID = Account.id(1001);
  private static final PatchSet.Id PS1 = PatchSet.id(CHANGE_ID, 1);
  private static final PatchSet.Id PS2 = PatchSet.id(CHANGE_ID, 2);
  private static final String FILE = "src/Main.java";

  private static final ObjectId OLD_COMMIT =
      ObjectId.fromString("1111111111111111111111111111111111111111");
  private static final ObjectId NEW_COMMIT =
      ObjectId.fromString("2222222222222222222222222222222222222222");

  @Mock private PluginItemContext<AccountPatchLineReviewStore> pluginItemContext;
  @Mock private DiffOperations diffOperations;
  @Mock private CommentsUtil commentsUtil;
  @Mock private AccountPatchLineReviewStore store;

  private LineReviewPropagation propagation;
  private Change change;
  private PatchSet patchSet1;
  private PatchSet patchSet2;

  private Optional<ObjectId> commitForPatchSet(InvocationOnMock invocation) {
    PatchSet patchSet = invocation.getArgument(1);
    return patchSet.id().equals(PS1) ? Optional.of(OLD_COMMIT) : Optional.of(NEW_COMMIT);
  }

  @Before
  public void setUp() {
    propagation = new LineReviewPropagation(pluginItemContext, diffOperations, commentsUtil);
    change =
        new Change(
            Change.key("I123"),
            CHANGE_ID,
            Account.id(7),
            BranchNameKey.create(PROJECT, "refs/heads/main"),
            Instant.now());
    patchSet1 =
        PatchSet.builder()
            .id(PS1)
            .commitId(OLD_COMMIT)
            .uploader(Account.id(7))
            .realUploader(Account.id(7))
            .createdOn(Instant.now())
            .build();
    patchSet2 =
        PatchSet.builder()
            .id(PS2)
            .commitId(NEW_COMMIT)
            .uploader(Account.id(7))
            .realUploader(Account.id(7))
            .createdOn(Instant.now())
            .build();
  }

  @Test
  public void propagatesRevisionLineAsTentative() throws Exception {
    ReviewedLine reviewed = ReviewedLine.create(FILE, 5, (short) 1, 5, 0, 5, 0, ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(reviewed))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort()))
        .thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(
            ImmutableMap.of(
                FILE,
                modifiedDiff(
                    FILE,
                    FILE,
                    ImmutableList.of(
                        // Insert 2 lines at the top: old line 5 should map to new line 7.
                        TaggedEdit.create(Edit.create(0, 0, 0, 2), false)))));

    propagation.propagate(store, change, patchSet1, patchSet2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<ReviewedLine>> captor =
        (ArgumentCaptor<Collection<ReviewedLine>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Collection.class);
    verify(store).insertPropagatedTentativeReviews(eq(PS2), eq(ACCOUNT_ID), captor.capture());
    assertThat(captor.getValue()).hasSize(1);
    ReviewedLine propagated = captor.getValue().iterator().next();
    // Geometry is remapped (5 -> 7) but status is downgraded to TENTATIVELY_READ on carryover.
    assertThat(propagated.path()).isEqualTo(FILE);
    assertThat(propagated.lineNumber()).isEqualTo(7);
    assertThat(propagated.reviewStatus()).isEqualTo(ReviewStatus.TENTATIVELY_READ);
  }

  @Test
  public void ignoresParentSideMarkers() throws Exception {
    ReviewedLine parentSide =
        ReviewedLine.create(FILE, 8, (short) 0, 8, 0, 8, 0, ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(parentSide))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort()))
        .thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(ImmutableMap.of());

    propagation.propagate(store, change, patchSet1, patchSet2);

    verify(store, never()).insertPropagatedTentativeReviews(any(), any(), any());
  }

  @Test
  public void skipsWhenCommitIdIsMissing() throws Exception {
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenReturn(Optional.empty());

    propagation.propagate(store, change, patchSet1, patchSet2);

    verify(store, never()).accountsWithLineReviews(any());
    verify(store, never()).insertPropagatedTentativeReviews(any(), any(), any());
  }

  @Test
  public void dropsMarkersWhenFileDeleted() throws Exception {
    ReviewedLine reviewed = ReviewedLine.create(FILE, 4, (short) 1, 4, 0, 4, 0, ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(reviewed))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort()))
        .thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(
            ImmutableMap.of(
                FILE,
                modifiedDiff(Optional.of(FILE), Optional.empty(), ImmutableList.of())));

    propagation.propagate(store, change, patchSet1, patchSet2);

    verify(store, never()).insertPropagatedTentativeReviews(any(), any(), any());
  }

  @Test
  public void skipsWhenPatchSetsBelongToDifferentChanges() throws Exception {
    PatchSet.Id otherPsId = PatchSet.id(Change.id(2), 2);
    PatchSet otherPatchSet =
        PatchSet.builder()
            .id(otherPsId)
            .commitId(NEW_COMMIT)
            .uploader(Account.id(7))
            .realUploader(Account.id(7))
            .createdOn(Instant.now())
            .build();

    propagation.propagate(store, change, patchSet1, otherPatchSet);

    verify(commentsUtil, never()).determineCommitId(any(), any(), anyShort());
    verify(store, never()).accountsWithLineReviews(any());
  }

  @Test
  public void skipsAccountWithoutPriorReviewedLines() throws Exception {
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null)).thenReturn(Optional.empty());
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(ImmutableMap.of());

    propagation.propagate(store, change, patchSet1, patchSet2);

    verify(store, never()).insertPropagatedTentativeReviews(any(), any(), any());
  }

  @Test
  public void skipsUnreadMarkers() throws Exception {
    ReviewedLine unread =
        ReviewedLine.create(FILE, 6, (short) 1, 6, 0, 6, 0, ReviewStatus.UNREAD);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(unread))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(ImmutableMap.of());

    propagation.propagate(store, change, patchSet1, patchSet2);

    verify(store, never()).insertPropagatedTentativeReviews(any(), any(), any());
  }

  @Test
  public void propagatesRangeMarkerKeepingCharacterGeometry() throws Exception {
    ReviewedLine range =
        ReviewedLine.create(
            FILE,
            12,
            (short) 1,
            10,
            2,
            12,
            5,
            ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(range))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(
            ImmutableMap.of(
                FILE,
                modifiedDiff(
                    FILE,
                    FILE,
                    ImmutableList.of(
                        // Insert two lines above the range start and end.
                        TaggedEdit.create(Edit.create(0, 0, 0, 2), false)))));

    propagation.propagate(store, change, patchSet1, patchSet2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<ReviewedLine>> captor =
        (ArgumentCaptor<Collection<ReviewedLine>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Collection.class);
    verify(store, times(1)).insertPropagatedTentativeReviews(eq(PS2), eq(ACCOUNT_ID), captor.capture());
    ReviewedLine propagated = captor.getValue().iterator().next();
    assertThat(propagated.startLine()).isEqualTo(12);
    assertThat(propagated.endLine()).isEqualTo(14);
    assertThat(propagated.startChar()).isEqualTo(2);
    assertThat(propagated.endChar()).isEqualTo(5);
    assertThat(propagated.lineNumber()).isEqualTo(14);
    assertThat(propagated.reviewStatus()).isEqualTo(ReviewStatus.TENTATIVELY_READ);
  }

  @Test
  public void propagateOnNewPatchSet_runsInsidePluginContext() throws Exception {
    ReviewedLine reviewed = ReviewedLine.create(FILE, 5, (short) 1, 5, 0, 5, 0, ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(reviewed))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(
            ImmutableMap.of(
                FILE,
                modifiedDiff(
                    FILE,
                    FILE,
                    ImmutableList.of(TaggedEdit.create(Edit.create(0, 0, 0, 1), false)))));
    doAnswer(
            invocation -> {
              ExtensionImplConsumer<AccountPatchLineReviewStore> callback = invocation.getArgument(0);
              callback.run(store);
              return null;
            })
        .when(pluginItemContext)
        .run(any());

    propagation.propagateOnNewPatchSet(change, patchSet1, patchSet2);

    verify(pluginItemContext, times(1)).run(any());
    verify(store, times(1)).insertPropagatedTentativeReviews(any(), any(), any());
  }

  @Test
  public void propagateOnNewPatchSet_swallowsPluginContextException() throws Exception {
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenThrow(new RuntimeException("boom"));
    doAnswer(
            invocation -> {
              ExtensionImplConsumer<AccountPatchLineReviewStore> callback = invocation.getArgument(0);
              callback.run(store);
              return null;
            })
        .when(pluginItemContext)
        .run(any());

    propagation.propagateOnNewPatchSet(change, patchSet1, patchSet2);

    verify(pluginItemContext, times(1)).run(any());
  }

  @Test
  public void propagatesPerAccountAndSkipsAccountWithoutMappedLines() throws Exception {
    Account.Id otherAccount = Account.id(1002);
    ReviewedLine account1Line =
        ReviewedLine.create(FILE, 5, (short) 1, 5, 0, 5, 0, ReviewStatus.READ);
    ReviewedLine account2ParentOnly =
        ReviewedLine.create(FILE, 8, (short) 0, 8, 0, 8, 0, ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID, otherAccount));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(account1Line))));
    when(store.findReviewedLines(PS1, otherAccount, null))
        .thenReturn(Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(account2ParentOnly))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(
            ImmutableMap.of(
                FILE,
                modifiedDiff(
                    FILE,
                    FILE,
                    ImmutableList.of(TaggedEdit.create(Edit.create(0, 0, 0, 1), false)))));

    propagation.propagate(store, change, patchSet1, patchSet2);

    verify(store, times(1)).insertPropagatedTentativeReviews(eq(PS2), eq(ACCOUNT_ID), any());
    verify(store, never()).insertPropagatedTentativeReviews(eq(PS2), eq(otherAccount), any());
  }

  @Test
  public void propagatesRangeEndingAtLineBoundary() throws Exception {
    ReviewedLine rangeAtBoundary =
        ReviewedLine.create(
            FILE,
            12,
            (short) 1,
            10,
            0,
            12,
            0,
            ReviewStatus.READ);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_ID));
    when(store.findReviewedLines(PS1, ACCOUNT_ID, null))
        .thenReturn(
            Optional.of(PatchSetWithReviewedLines.create(PS1, ImmutableList.of(rangeAtBoundary))));
    when(commentsUtil.determineCommitId(any(), any(), anyShort())).thenAnswer(this::commitForPatchSet);
    when(diffOperations.listModifiedFiles(any(), any(), any(), any(DiffOptions.class)))
        .thenReturn(
            ImmutableMap.of(
                FILE,
                modifiedDiff(
                    FILE,
                    FILE,
                    ImmutableList.of(
                        // shift by two lines
                        TaggedEdit.create(Edit.create(0, 0, 0, 2), false)))));

    propagation.propagate(store, change, patchSet1, patchSet2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<ReviewedLine>> captor =
        (ArgumentCaptor<Collection<ReviewedLine>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Collection.class);
    verify(store).insertPropagatedTentativeReviews(eq(PS2), eq(ACCOUNT_ID), captor.capture());
    ReviewedLine propagated = captor.getValue().iterator().next();
    assertThat(propagated.startLine()).isEqualTo(12);
    assertThat(propagated.endLine()).isEqualTo(14);
    assertThat(propagated.startChar()).isEqualTo(0);
    assertThat(propagated.endChar()).isEqualTo(0);
    assertThat(propagated.lineNumber()).isEqualTo(14);
  }

  private static FileDiffOutput modifiedDiff(
      String oldPath, String newPath, ImmutableList<TaggedEdit> edits) {
    return modifiedDiff(Optional.of(oldPath), Optional.of(newPath), edits);
  }

  private static FileDiffOutput modifiedDiff(
      Optional<String> oldPath, Optional<String> newPath, ImmutableList<TaggedEdit> edits) {
    return FileDiffOutput.builder()
        .oldCommitId(OLD_COMMIT)
        .newCommitId(NEW_COMMIT)
        .comparisonType(ComparisonType.againstOtherPatchSet())
        .oldPath(oldPath)
        .newPath(newPath)
        .oldMode(Optional.of(Patch.FileMode.REGULAR_FILE))
        .newMode(Optional.of(Patch.FileMode.REGULAR_FILE))
        .oldSha(Optional.of(OLD_COMMIT))
        .newSha(Optional.of(NEW_COMMIT))
        .changeType(Patch.ChangeType.MODIFIED)
        .patchType(Optional.empty())
        .headerLines(ImmutableList.of())
        .edits(edits)
        .size(0L)
        .sizeDelta(0L)
        .negative(Optional.empty())
        .build();
  }
}
