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

package com.google.gerrit.extensions.common;

import com.google.gerrit.extensions.client.Side;

/** Represents a single line-level read marker for a file in a patch set. */
public class LineMarkerInfo {
  /** Which side of the diff this marker applies to (REVISION or PARENT). */
  public Side side;

  /** 1-indexed line number. */
  public int line;

  /** Marker status. Always "READ" in v1 (only READ rows are stored). */
  public String status;
}
