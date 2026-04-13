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

package com.google.gerrit.extensions.client;

/**
 * Review status of a line or region for a patch set.
 *
 * <p>Clients submit {@link #READ} when marking a region; {@link
 * #TENTATIVELY_READ} is default for lines/regions that are unchanged since previous commit, otherwise default is {@link #UNREAD}.
 */
public enum ReviewStatus {
  /** The reviewer explicitly marked this region as read on this patch set. */
  READ,

  /**
   * The server carried progress forward from an earlier patch set because the content in this
   * region is unchanged; the reviewer should confirm before treating it as fully read.
   */
  TENTATIVELY_READ,

  /** The region is not read for this reviewer on this patch set. */
  UNREAD,
}
