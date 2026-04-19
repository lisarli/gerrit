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

import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.patch.ComparisonType;
import com.google.gerrit.server.patch.Text;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Line counts for files at a revision, using the same text model as diff and content APIs. */
@Singleton
public class RevisionFileLineCounts {
  private final GitRepositoryManager repoManager;

  @Inject
  RevisionFileLineCounts(GitRepositoryManager repoManager) {
    this.repoManager = repoManager;
  }

  /**
   * Returns the number of lines in the file at the given commit. Magic paths use generated text;
   * missing paths and non-blob entries return 0.
   */
  public int countLines(Project.NameKey project, ObjectId commitId, String path)
      throws IOException {
    if (Patch.PATCHSET_LEVEL.equals(path)) {
      return 0;
    }
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      RevCommit commit = rw.parseCommit(commitId);
      if (Patch.COMMIT_MSG.equals(path)) {
        return Text.forCommit(rw.getObjectReader(), commit).size();
      }
      if (Patch.MERGE_LIST.equals(path)) {
        return Text.forMergeList(
                ComparisonType.againstAutoMerge(), rw.getObjectReader(), commit)
            .size();
      }
      try (TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(), path, commit.getTree())) {
        if (tw == null) {
          return 0;
        }
        if (tw.getFileMode(0).getObjectType() == Constants.OBJ_TREE) {
          return 0;
        }
        if (tw.getFileMode(0) == org.eclipse.jgit.lib.FileMode.GITLINK) {
          return 0;
        }
        ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
        try {
          return new Text(Text.asByteArray(loader)).size();
        } catch (LargeObjectException e) {
          return 0;
        }
      }
    }
  }
}
