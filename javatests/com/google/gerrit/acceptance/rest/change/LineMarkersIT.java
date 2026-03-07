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

package com.google.gerrit.acceptance.rest.change;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.extensions.api.changes.LineMarkerInput;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.common.LineMarkersInfo;
import org.junit.Test;

public class LineMarkersIT extends AbstractDaemonTest {

  private String fileUrl(String changeId, String fileName) {
    return String.format("/changes/%s/revisions/current/files/%s/line-markers", changeId, fileName);
  }

  private LineMarkerInput readInput(Side side, int line) {
    LineMarkerInput input = new LineMarkerInput();
    input.side = side;
    input.line = line;
    input.status = "READ";
    return input;
  }

  private LineMarkerInput unreadInput(Side side, int line) {
    LineMarkerInput input = new LineMarkerInput();
    input.side = side;
    input.line = line;
    input.status = "UNREAD";
    return input;
  }

  @Test
  public void putRead_returnsNoContent() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    RestResponse response = adminRestSession.put(url, readInput(Side.REVISION, 5));
    response.assertNoContent();
  }

  @Test
  public void getAfterPutRead_includesMarker() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    adminRestSession.put(url, readInput(Side.REVISION, 5)).assertNoContent();

    RestResponse getResp = adminRestSession.get(url);
    getResp.assertOK();
    LineMarkersInfo info = newGson().fromJson(getResp.getReader(), LineMarkersInfo.class);
    assertThat(info.markers).hasSize(1);
    assertThat(info.markers.get(0).side).isEqualTo(Side.REVISION);
    assertThat(info.markers.get(0).line).isEqualTo(5);
    assertThat(info.markers.get(0).status).isEqualTo("READ");
  }

  @Test
  public void putUnread_removesMarker() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    adminRestSession.put(url, readInput(Side.REVISION, 5)).assertNoContent();
    adminRestSession.put(url, unreadInput(Side.REVISION, 5)).assertNoContent();

    RestResponse getResp = adminRestSession.get(url);
    getResp.assertOK();
    LineMarkersInfo info = newGson().fromJson(getResp.getReader(), LineMarkersInfo.class);
    assertThat(info.markers).isEmpty();
  }

  @Test
  public void getWithNoMarkers_returnsEmptyList() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    RestResponse getResp = adminRestSession.get(url);
    getResp.assertOK();
    LineMarkersInfo info = newGson().fromJson(getResp.getReader(), LineMarkersInfo.class);
    assertThat(info.markers).isEmpty();
  }

  @Test
  public void putRead_isIdempotent() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    adminRestSession.put(url, readInput(Side.REVISION, 5)).assertNoContent();
    adminRestSession.put(url, readInput(Side.REVISION, 5)).assertNoContent(); // no error

    RestResponse getResp = adminRestSession.get(url);
    getResp.assertOK();
    LineMarkersInfo info = newGson().fromJson(getResp.getReader(), LineMarkersInfo.class);
    assertThat(info.markers).hasSize(1);
  }

  @Test
  public void multipleLines_allVisible() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    adminRestSession.put(url, readInput(Side.REVISION, 3)).assertNoContent();
    adminRestSession.put(url, readInput(Side.REVISION, 7)).assertNoContent();
    adminRestSession.put(url, readInput(Side.PARENT, 3)).assertNoContent();

    RestResponse getResp = adminRestSession.get(url);
    getResp.assertOK();
    LineMarkersInfo info = newGson().fromJson(getResp.getReader(), LineMarkersInfo.class);
    assertThat(info.markers).hasSize(3);
  }

  @Test
  public void markersArePrivate_otherUserSeesEmpty() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    // Admin marks a line.
    adminRestSession.put(url, readInput(Side.REVISION, 5)).assertNoContent();

    // Another user (user) sees no markers (private scope).
    RestResponse getResp = userRestSession.get(url);
    getResp.assertOK();
    LineMarkersInfo info = newGson().fromJson(getResp.getReader(), LineMarkersInfo.class);
    assertThat(info.markers).isEmpty();
  }

  @Test
  public void unauthenticatedPut_isForbidden() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    anonymousRestSession.put(url, readInput(Side.REVISION, 5)).assertForbidden();
  }

  @Test
  public void invalidInput_lineBelowOne_returnsBadRequest() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    LineMarkerInput input = new LineMarkerInput();
    input.side = Side.REVISION;
    input.line = 0;
    input.status = "READ";

    adminRestSession.put(url, input).assertBadRequest();
  }

  @Test
  public void invalidInput_missingSide_returnsBadRequest() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    LineMarkerInput input = new LineMarkerInput();
    input.side = null;
    input.line = 5;
    input.status = "READ";

    adminRestSession.put(url, input).assertBadRequest();
  }

  @Test
  public void invalidInput_invalidStatus_returnsBadRequest() throws Exception {
    PushOneCommit.Result r = createChange();
    String url = fileUrl(r.getChangeId(), PushOneCommit.FILE_NAME);

    LineMarkerInput input = new LineMarkerInput();
    input.side = Side.REVISION;
    input.line = 5;
    input.status = "INVALID";

    adminRestSession.put(url, input).assertBadRequest();
  }
}
