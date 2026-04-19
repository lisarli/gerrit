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

package com.google.gerrit.server.restapi.change;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.client.Comment.Range;
import com.google.gerrit.extensions.common.LineReviewHistoryInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineReviewHistoryEntry;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class GetLineReviewHistory implements RestReadView<ChangeResource> {
  private final PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore;

  @Inject
  GetLineReviewHistory(
      PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore) {
    this.accountPatchLineReviewStore = accountPatchLineReviewStore;
  }

  @Override
  public Response<List<LineReviewHistoryInfo>> apply(ChangeResource rsrc) throws AuthException {
    if (!rsrc.getUser().isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    Change.Id changeId = rsrc.getChange().getId();

    ImmutableList<LineReviewHistoryEntry> entries =
        accountPatchLineReviewStore.call(s -> s.findLineReviewHistory(changeId));

    List<LineReviewHistoryInfo> result = new ArrayList<>();
    for (LineReviewHistoryEntry entry : entries) {
      LineReviewHistoryInfo info = new LineReviewHistoryInfo();
      info.accountId = entry.accountId().get();
      info.patchSetId = entry.patchSetId().get();
      info.file = entry.path();
      info.line = entry.lineNumber();
      info.side = entry.getSide();
      if (entry.startLine() != entry.endLine()
          || entry.startChar() != 0
          || entry.endChar() != 0) {
        info.range = new Range();
        info.range.startLine = entry.startLine();
        info.range.startCharacter = entry.startChar();
        info.range.endLine = entry.endLine();
        info.range.endCharacter = entry.endChar();
      }
      info.action = entry.action().name();
      info.timestamp = entry.createdOn();
      result.add(info);
    }
    return Response.ok(result);
  }
}
