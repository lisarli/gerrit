---
title: "Design Doc - Granular Reviewer Read Markers - Solution - Shared Region-Based Review State"
sidebar: gerritdoc_sidebar
permalink: design-doc-granular-reviewer-read-markers-solution-1.html
hide_sidebar: true
hide_navtoggle: true
toc: false
folder: design-docs/granular-reviewer-read-markers
---

# Solution - Shared Region-Based Review State

## <a id="overview"> Overview

Replace the private file-level reviewed flag with a region-based review
state model that stores normalized read ranges per reviewer, file, and
patch set. Gerrit exposes two views over the same data:

* a private caller-focused view for fast "what is left for me?"
* a shared reviewer-progress view for understanding team coverage

File-level reviewed status becomes a compatibility projection: a file is
considered reviewed for a reviewer when all changed regions in that file
are marked read for the selected patch set.

On patch set upload, Gerrit maps prior read ranges onto the new patch
set using diff segment mapping. Regions that correspond to unchanged
content are preserved. Regions touching changed content are dropped.

## <a id="detailed-design"> Detailed Design

### Data model

Introduce a new store interface, for example `AccountPatchReadStore`,
that supersedes the current file-level tuple with range-aware records:

* `change_id`
* `patch_set_id`
* `file_name`
* `account_id`
* `start_line`
* `end_line`
* `state`
* `updated`

`state` starts with a single value, `READ`, but remains explicit so the
model can later support richer progress states without another schema
reset.

Ranges are inclusive, 1-based, and refer to the right side of the diff
for the selected patch set. For deleted-only regions and pure rename
cases, Gerrit stores synthetic anchors against the diff segment ids
rather than old-side line numbers. This avoids ambiguity when a region
no longer has stable right-side line numbers.

The store normalizes overlapping or adjacent ranges for the same user,
file, patch set, and state at write time. That keeps reads cheap and
prevents unbounded row growth from repeated small interactions.

### API surface

Add new REST entities and endpoints rather than overloading the current
file-level ones:

* `GET /changes/{change-id}/revisions/{revision-id}/read-progress`
* `PUT /changes/{change-id}/revisions/{revision-id}/files/{file-id}/read`
* `DELETE /changes/{change-id}/revisions/{revision-id}/files/{file-id}/read`

`PUT` accepts a body containing one or more ranges. `DELETE` clears
matching ranges or an entire file's read state for the caller depending
on the request body.

`GET read-progress` returns:

* caller read ranges by file
* per-file aggregate coverage for visible reviewers
* optional reviewer detail keyed by account id
* a compatibility field indicating whether the caller's file is fully
  read

The existing `.../files?reviewed` and `.../files/{file-id}/reviewed`
endpoints remain for compatibility. Their implementation becomes a
projection over region data:

* listing reviewed files returns files whose changed regions are fully
  marked read by the caller
* setting reviewed on a file marks all changed regions in the file as
  read for the caller
* deleting reviewed clears all caller read ranges for the file

### Visibility model

Existing change visibility rules continue to gate all access.

Shared progress is visible only to users who can already view the
change. Anonymous users do not get reviewer-level read details.

To reduce privacy concerns, sites can choose one of three modes:

* `PRIVATE_ONLY`: only the caller sees read markers
* `REVIEWERS_ONLY`: reviewers and the owner see reviewer identities
* `SHARED`: anyone with change visibility sees reviewer identities

The default is `REVIEWERS_ONLY`.

### Carry-forward across patch sets

When Gerrit serves or computes read progress for patch set `N`, it
looks for stored markers on patch set `N`. If none exist for the caller,
it lazily derives an initial view from the nearest earlier patch set
with read markers.

Carry-forward uses Gerrit's existing diff machinery:

* compute the file-level mapping from patch set `N-1` to `N`
* project old read ranges through unchanged diff segments
* discard any portion intersecting inserted, deleted, or modified
  segments
* normalize the surviving ranges and persist them for patch set `N`

The same algorithm supports a background migration job and avoids
blocking page render on repeated remapping for the same user and patch
set.

### UI behavior

PolyGerrit adds:

* line and region-level actions in the diff gutter
* read overlays that indicate my read state and other reviewers' read
  state
* file-list badges showing per-file progress and reviewer coverage
* navigation to the next unread region

The existing file checkbox remains, but now operates as a shorthand for
"mark all unread changed regions in this file as read".

To keep the UI readable, the shared overlay is collapsed by default into
aggregate coverage. Hover or an expandable drawer reveals reviewer-level
detail.

### Migration

Migration proceeds in three phases:

1. Introduce the new storage and compatibility projection while
   preserving existing file-level endpoints.
2. Backfill region data for existing reviewed files as a single range
   spanning all changed regions in each reviewed file.
3. Switch PolyGerrit to the new `read-progress` endpoint and retain
   legacy endpoints until external clients have migrated.

### Scalability

Write amplification is controlled by range normalization and by allowing
the client to batch multiple ranges in one request.

Read amplification is controlled by:

* file-scoped fetching in the diff view
* compact aggregate responses for the file list
* lazy carry-forward persisted on first access instead of recomputed on
  every load

The storage footprint grows with the number of marked regions rather
than raw user interactions. For large changes with many reviewers,
aggregate coverage should be materialized from normalized ranges instead
of expanding into per-line booleans.

In multi-primary deployments the new store has the same replication
requirements as the current account patch review store, but higher write
frequency. The store therefore should support idempotent upserts and
coarse conflict resolution based on normalized ranges and latest update
timestamp.

## <a id="alternatives-considered"> Alternatives Considered

Keeping file-level reviewed flags and only sharing them is not
sufficient. It improves visibility but does not solve partial review or
unchanged-region carry-forward.

Storing a boolean per line is simpler conceptually, but it creates far
more rows, makes renumbering across patch sets more expensive, and
encourages wasteful whole-file payloads.

Encoding read state only in browser-local storage avoids backend work
but fails the shared progress use case and loses cross-device state.

## <a id="pros-and-cons"> Pros and Cons

Pros:

* directly matches reviewer workflows on large diffs
* preserves current file-level behavior through compatibility
* makes review coverage visible without conflating it with approval
* limits carry-forward to unchanged content, which matches reviewer
  expectations

Cons:

* significantly expands the reviewed-state data model and UI complexity
* introduces new privacy and policy questions for shared reviewer
  progress
* requires careful diff mapping to avoid surprising carry-forward on
  complex edits, renames, and deleted lines

## <a id="implementation-plan"> Implementation Plan

This proposal should be implemented in staged changes:

* backend store and REST entities
* carry-forward and compatibility projection
* PolyGerrit file-list and diff integration
* migration and documentation

Mentor support would be useful for the carry-forward algorithm and API
shape, since both affect long-term compatibility.

## <a id="time-estimation"> Time Estimation

Backend storage, REST API, and compatibility projection: 2 to 3 weeks.

Carry-forward mapping, caching, and migration tooling: 2 weeks.

PolyGerrit diff/file-list UX and tests: 2 to 3 weeks.

Documentation, rollout controls, and cleanup: 1 week.

Estimated total: 7 to 9 weeks.
