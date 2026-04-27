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

package com.google.gerrit.testing;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.changes.LineReviewedInput;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineReviewAction;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineReviewHistoryEntry;
import com.google.inject.Singleton;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory implementation of {@link AccountPatchLineReviewStore} for tests.
 */
@Singleton
public class FakeAccountPatchLineReviewStore
    implements AccountPatchLineReviewStore, LifecycleListener {

  private final Set<LineEntity> store = new HashSet<>();
  private final List<LineReviewHistoryEntry> history = new ArrayList<>();

  @Override
  public void start() {}

  @Override
  public void stop() {}

  public static class FakeAccountPatchLineReviewStoreModule extends LifecycleModule {
    @Override
    protected void configure() {
      DynamicItem.bind(binder(), AccountPatchLineReviewStore.class)
          .to(FakeAccountPatchLineReviewStore.class);
      listener().to(FakeAccountPatchLineReviewStore.class);
    }
  }

  @AutoValue
  abstract static class LineEntity {
    abstract PatchSet.Id psId();

    abstract Account.Id accountId();

    abstract String path();

    abstract int lineNumber();

    abstract short side();

    abstract int startLine();

    abstract int startChar();

    abstract int endLine();

    abstract int endChar();

    static LineEntity create(
        PatchSet.Id psId,
        Account.Id accountId,
        String path,
        int lineNumber,
        short side,
        int startLine,
        int startChar,
        int endLine,
        int endChar) {
      return new AutoValue_FakeAccountPatchLineReviewStore_LineEntity(
          psId, accountId, path, lineNumber, side, startLine, startChar, endLine, endChar);
    }
  }

  private static void normalize(
      LineReviewedInput input,
      int[] lineNumber,
      int[] startLine,
      int[] startChar,
      int[] endLine,
      int[] endChar) {
    int line = input.line != null && input.line > 0 ? input.line : 1;
    lineNumber[0] = line;
    if (input.range != null
        && input.range.startLine > 0
        && input.range.endLine > 0
        && input.range.startLine <= input.range.endLine) {
      startLine[0] = input.range.startLine;
      startChar[0] = Math.max(0, input.range.startCharacter);
      endLine[0] = input.range.endLine;
      endChar[0] = Math.max(0, input.range.endCharacter);
    } else {
      startLine[0] = line;
      startChar[0] = 0;
      endLine[0] = line;
      endChar[0] = 0;
    }
  }

  @Override
  public boolean markLineReviewed(
      PatchSet.Id psId, Account.Id accountId, String path, LineReviewedInput input) {
    Side side = input.side != null ? input.side : Side.REVISION;
    short sideShort = side == Side.PARENT ? (short) 0 : (short) 1;
    int[] lineNumber = new int[1];
    int[] startLine = new int[1], startChar = new int[1], endLine = new int[1], endChar = new int[1];
    normalize(input, lineNumber, startLine, startChar, endLine, endChar);

    LineEntity entity =
        LineEntity.create(
            psId,
            accountId,
            path,
            lineNumber[0],
            sideShort,
            startLine[0],
            startChar[0],
            endLine[0],
            endChar[0]);
    boolean added;
    synchronized (store) {
      added = store.add(entity);
    }
    if (added) {
      synchronized (history) {
        history.add(
            LineReviewHistoryEntry.create(
                psId, accountId, path, lineNumber[0], sideShort,
                startLine[0], startChar[0], endLine[0], endChar[0],
                LineReviewAction.MARKED, new Timestamp(System.currentTimeMillis())));
      }
    }
    return added;
  }

  @Override
  public void markLineReviewed(
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      Collection<LineReviewedInput> inputs) {
    inputs.forEach(
        input -> {
          var unused = markLineReviewed(psId, accountId, path, input);
        });
  }

  @Override
  public void clearLineReviewed(
      PatchSet.Id psId, Account.Id accountId, String path, LineReviewedInput input) {
    Side side = input.side != null ? input.side : Side.REVISION;
    short sideShort = side == Side.PARENT ? (short) 0 : (short) 1;
    int[] lineNumber = new int[1];
    int[] startLine = new int[1], startChar = new int[1], endLine = new int[1], endChar = new int[1];
    normalize(input, lineNumber, startLine, startChar, endLine, endChar);

    LineEntity entity =
        LineEntity.create(
            psId, accountId, path, lineNumber[0], sideShort,
            startLine[0], startChar[0], endLine[0], endChar[0]);
    boolean removed;
    synchronized (store) {
      removed = store.remove(entity);
    }
    if (removed) {
      synchronized (history) {
        history.add(
            LineReviewHistoryEntry.create(
                psId, accountId, path, lineNumber[0], sideShort,
                startLine[0], startChar[0], endLine[0], endChar[0],
                LineReviewAction.UNMARKED, new Timestamp(System.currentTimeMillis())));
      }
    }
  }

  @Override
  public void clearLineReviewed(PatchSet.Id psId) {
    synchronized (store) {
      List<LineEntity> toRemove = new ArrayList<>();
      for (LineEntity entity : store) {
        if (entity.psId().equals(psId)) {
          toRemove.add(entity);
        }
      }
      store.removeAll(toRemove);
    }
  }

  @Override
  public void clearLineReviewed(Change.Id changeId) {
    synchronized (store) {
      List<LineEntity> toRemove = new ArrayList<>();
      for (LineEntity entity : store) {
        if (entity.psId().changeId().equals(changeId)) {
          toRemove.add(entity);
        }
      }
      store.removeAll(toRemove);
    }
  }

  @Override
  public void clearLineReviewedBy(Account.Id accountId) {
    synchronized (store) {
      List<LineEntity> toRemove = new ArrayList<>();
      for (LineEntity entity : store) {
        if (entity.accountId().equals(accountId)) {
          toRemove.add(entity);
        }
      }
      store.removeAll(toRemove);
    }
  }

  @Override
  public ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> findAllReviewedLines(
      PatchSet.Id psId, String path) {
    synchronized (store) {
      Map<Account.Id, ImmutableList.Builder<ReviewedLine>> builders = new LinkedHashMap<>();
      for (LineEntity entity : store) {
        if (!entity.psId().equals(psId) || !entity.path().equals(path)) {
          continue;
        }
        builders
            .computeIfAbsent(entity.accountId(), k -> ImmutableList.builder())
            .add(
                ReviewedLine.create(
                    entity.path(),
                    entity.lineNumber(),
                    entity.side(),
                    entity.startLine(),
                    entity.startChar(),
                    entity.endLine(),
                    entity.endChar()));
      }
      ImmutableMap.Builder<Account.Id, ImmutableList<ReviewedLine>> result =
          ImmutableMap.builder();
      builders.forEach((accountId, builder) -> result.put(accountId, builder.build()));
      return result.build();
    }
  }

  @Override
  public Optional<PatchSetWithReviewedLines> findReviewedLines(
      PatchSet.Id psId, Account.Id accountId, String path) {
    synchronized (store) {
      ImmutableList.Builder<ReviewedLine> builder = ImmutableList.builder();
      for (LineEntity entity : store) {
        if (!entity.accountId().equals(accountId) || !entity.psId().equals(psId)) {
          continue;
        }
        if (path != null && !path.equals(entity.path())) {
          continue;
        }
        builder.add(
            ReviewedLine.create(
                entity.path(),
                entity.lineNumber(),
                entity.side(),
                entity.startLine(),
                entity.startChar(),
                entity.endLine(),
                entity.endChar()));
      }
      ImmutableList<ReviewedLine> lines = builder.build();
      if (lines.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(PatchSetWithReviewedLines.create(psId, lines));
    }
  }

  @Override
  public void logLineReviewAction(
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      LineReviewedInput input,
      LineReviewAction action) {
    Side side = input.side != null ? input.side : Side.REVISION;
    short sideShort = side == Side.PARENT ? (short) 0 : (short) 1;
    int[] lineNumber = new int[1];
    int[] startLine = new int[1], startChar = new int[1], endLine = new int[1], endChar = new int[1];
    normalize(input, lineNumber, startLine, startChar, endLine, endChar);
    synchronized (history) {
      history.add(
          LineReviewHistoryEntry.create(
              psId, accountId, path, lineNumber[0], sideShort,
              startLine[0], startChar[0], endLine[0], endChar[0],
              action, new Timestamp(System.currentTimeMillis())));
    }
  }

  @Override
  public ImmutableList<LineReviewHistoryEntry> findLineReviewHistory(Change.Id changeId) {
    synchronized (history) {
      ImmutableList.Builder<LineReviewHistoryEntry> builder = ImmutableList.builder();
      for (LineReviewHistoryEntry entry : history) {
        if (entry.patchSetId().changeId().equals(changeId)) {
          builder.add(entry);
        }
      }
      return builder.build();
    }
  }
}
