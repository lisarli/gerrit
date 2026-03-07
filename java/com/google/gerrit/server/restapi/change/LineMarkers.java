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
import com.google.gerrit.extensions.api.changes.LineMarkerInput;
import com.google.gerrit.extensions.common.LineMarkerInfo;
import com.google.gerrit.extensions.common.LineMarkersInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineMarker;
import com.google.gerrit.server.change.FileResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

public class LineMarkers {

  @Singleton
  public static class PutLineMarker implements RestModifyView<FileResource, LineMarkerInput> {
    private final AccountPatchLineReviewStore store;

    @Inject
    PutLineMarker(AccountPatchLineReviewStore store) {
      this.store = store;
    }

    @Override
    public Response<?> apply(FileResource resource, LineMarkerInput input)
        throws BadRequestException {
      if (input == null) {
        throw new BadRequestException("input is required");
      }
      if (input.side == null) {
        throw new BadRequestException("side is required");
      }
      if (input.line < 1) {
        throw new BadRequestException("line must be >= 1");
      }
      if (input.status == null) {
        throw new BadRequestException("status is required");
      }
      if (!input.status.equals("READ") && !input.status.equals("UNREAD")) {
        throw new BadRequestException("status must be READ or UNREAD");
      }

      if (input.status.equals("READ")) {
        store.markReviewedLine(
            resource.getPatchKey().patchSetId(),
            resource.getAccountId(),
            resource.getPatchKey().fileName(),
            input.side,
            input.line);
      } else {
        store.clearReviewedLine(
            resource.getPatchKey().patchSetId(),
            resource.getAccountId(),
            resource.getPatchKey().fileName(),
            input.side,
            input.line);
      }
      return Response.none();
    }
  }

  @Singleton
  public static class GetLineMarkers implements RestReadView<FileResource> {
    private final AccountPatchLineReviewStore store;

    @Inject
    GetLineMarkers(AccountPatchLineReviewStore store) {
      this.store = store;
    }

    @Override
    public Response<LineMarkersInfo> apply(FileResource resource) {
      ImmutableList<LineMarker> markers =
          store.listReviewedLines(
              resource.getPatchKey().patchSetId(),
              resource.getAccountId(),
              resource.getPatchKey().fileName());

      List<LineMarkerInfo> infoList = new ArrayList<>();
      for (LineMarker marker : markers) {
        LineMarkerInfo info = new LineMarkerInfo();
        info.side = marker.side();
        info.line = marker.line();
        info.status = "READ";
        infoList.add(info);
      }

      LineMarkersInfo result = new LineMarkersInfo();
      result.markers = infoList;
      return Response.ok(result);
    }
  }

  private LineMarkers() {}
}
