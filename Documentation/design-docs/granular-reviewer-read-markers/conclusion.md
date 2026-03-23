---
title: "Design Doc - Granular Reviewer Read Markers - Conclusion"
sidebar: gerritdoc_sidebar
permalink: design-doc-granular-reviewer-read-markers-conclusion.html
hide_sidebar: true
hide_navtoggle: true
toc: false
folder: design-docs/granular-reviewer-read-markers
---

# Conclusion

The preferred direction is to evolve reviewed flags into shared,
region-based read state while preserving the existing file-level API as
a compatibility layer.

This direction is preferred because it is the only one that satisfies
all three core goals at once:

* granular progress tracking within large files
* shared visibility into reviewer coverage
* safe carry-forward of read state for unchanged content across patch
  sets

The main cost is increased backend and UI complexity, especially around
privacy controls and patch set remapping. Those costs are acceptable
because the feature is explicitly about making review progress more
useful than the current private file-level marker can support.

## <a id="implementation-plan"> Implementation Plan

Implementation should start with backend storage and compatibility
projection so existing clients keep working. PolyGerrit can then adopt
the new read-progress API incrementally behind a feature flag, followed
by migration of legacy reviewed-file state.
