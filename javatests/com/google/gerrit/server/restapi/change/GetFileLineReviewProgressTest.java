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

package com.google.gerrit.server.restapi.change;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.FileLineReviewProgressInfo;
import com.google.gerrit.extensions.common.ReviewerLineReviewProgressInfo;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.PatchSetWithReviewedLines;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.ReviewedLine;
import com.google.gerrit.server.change.FileResource;
import com.google.gerrit.server.change.RevisionFileLineCounts;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.plugincontext.PluginContext.ExtensionImplFunction;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.gerrit.server.permissions.PermissionBackendException;
import java.io.IOException;
import java.time.Instant;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GetFileLineReviewProgressTest {
  private static final String FILE = "a.txt";
  private static final PatchSet.Id PS1 = PatchSet.id(Change.id(1), 1);
  private static final Account.Id ACCOUNT_1 = Account.id(1001);
  private static final Account.Id ACCOUNT_2 = Account.id(1002);

  @Mock private PluginItemContext<AccountPatchLineReviewStore> pluginItemContext;
  @Mock private RevisionFileLineCounts revisionFileLineCounts;
  @Mock private AccountLoader.Factory accountLoaderFactory;
  @Mock private AccountLoader accountLoader;
  @Mock private AccountPatchLineReviewStore store;
  @Mock private RevisionResource revisionResource;

  private GetFileLineReviewProgress getFileLineReviewProgress;
  private FileResource fileResource;

  @Before
  public void setUp() {
    getFileLineReviewProgress =
        new GetFileLineReviewProgress(
            pluginItemContext, revisionFileLineCounts, accountLoaderFactory);
    when(accountLoaderFactory.create(true)).thenReturn(accountLoader);
    when(accountLoader.get(ACCOUNT_1)).thenReturn(new AccountInfo(ACCOUNT_1.get()));
    when(accountLoader.get(ACCOUNT_2)).thenReturn(new AccountInfo(ACCOUNT_2.get()));

    when(pluginItemContext.call(any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ExtensionImplFunction<AccountPatchLineReviewStore, ?> fn = invocation.getArgument(0);
              return fn.call(store);
            });

    PatchSet patchSet =
        PatchSet.builder()
            .id(PS1)
            .commitId(ObjectId.fromString("1111111111111111111111111111111111111111"))
            .uploader(Account.id(1))
            .realUploader(Account.id(1))
            .createdOn(Instant.now())
            .build();
    when(revisionResource.getPatchSet()).thenReturn(patchSet);
    when(revisionResource.getProject()).thenReturn(Project.nameKey("test"));
    fileResource = new FileResource(revisionResource, FILE);
  }

  @Test
  public void apply_computesPerReviewerAndOverallPercentages() throws IOException, PermissionBackendException {
    when(revisionFileLineCounts.countLines(any(), any(), any())).thenReturn(10);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_2, ACCOUNT_1));

    when(store.findReviewedLines(PS1, ACCOUNT_1, FILE))
        .thenReturn(
            java.util.Optional.of(
                PatchSetWithReviewedLines.create(
                    PS1,
                    ImmutableList.of(
                        line(2, 2, ReviewStatus.READ),
                        line(3, 3, ReviewStatus.TENTATIVELY_READ)))));
    when(store.findReviewedLines(PS1, ACCOUNT_2, FILE))
        .thenReturn(
            java.util.Optional.of(
                PatchSetWithReviewedLines.create(
                    PS1, ImmutableList.of(line(3, 3, ReviewStatus.READ)))));

    FileLineReviewProgressInfo out = getFileLineReviewProgress.apply(fileResource).value();

    assertThat(out.totalLinesInFile).isEqualTo(10);
    assertThat(out.reviewers).hasSize(2);
    // Sorted by account ID.
    ReviewerLineReviewProgressInfo first = out.reviewers.get(0);
    ReviewerLineReviewProgressInfo second = out.reviewers.get(1);
    assertThat(first.account._accountId).isEqualTo(ACCOUNT_1.get());
    assertThat(first.percentRead).isWithin(0.001).of(10.0);
    assertThat(first.percentTentativelyRead).isWithin(0.001).of(10.0);
    assertThat(first.percentUnread).isWithin(0.001).of(80.0);

    assertThat(second.account._accountId).isEqualTo(ACCOUNT_2.get());
    assertThat(second.percentRead).isWithin(0.001).of(10.0);
    assertThat(second.percentTentativelyRead).isWithin(0.001).of(0.0);
    assertThat(second.percentUnread).isWithin(0.001).of(90.0);

    // Overall uses any-reviewer coverage and READ precedence on overlapping line 3.
    assertThat(out.percentRead).isWithin(0.001).of(20.0);
    assertThat(out.percentTentativelyRead).isWithin(0.001).of(0.0);
    assertThat(out.percentUnread).isWithin(0.001).of(80.0);
  }

  @Test
  public void apply_withZeroTotalLines_setsNullPercentages() throws IOException, PermissionBackendException {
    when(revisionFileLineCounts.countLines(any(), any(), any())).thenReturn(0);
    when(store.accountsWithLineReviews(PS1)).thenReturn(ImmutableSet.of(ACCOUNT_1));
    when(store.findReviewedLines(PS1, ACCOUNT_1, FILE))
        .thenReturn(
            java.util.Optional.of(
                PatchSetWithReviewedLines.create(
                    PS1, ImmutableList.of(line(1, 1, ReviewStatus.READ)))));

    FileLineReviewProgressInfo out = getFileLineReviewProgress.apply(fileResource).value();

    assertThat(out.totalLinesInFile).isEqualTo(0);
    assertThat(out.percentRead).isNull();
    assertThat(out.percentTentativelyRead).isNull();
    assertThat(out.percentUnread).isNull();
    assertThat(out.reviewers).hasSize(1);
    assertThat(out.reviewers.get(0).percentRead).isNull();
    assertThat(out.reviewers.get(0).percentTentativelyRead).isNull();
    assertThat(out.reviewers.get(0).percentUnread).isNull();
  }

  private static ReviewedLine line(int startLine, int endLine, ReviewStatus status) {
    return ReviewedLine.create(FILE, endLine, (short) 1, startLine, 0, endLine, 0, status);
  }
}
