---
title: "Design Doc - Granular Reviewer Read Markers - Use Cases"
sidebar: gerritdoc_sidebar
permalink: design-doc-granular-reviewer-read-markers-use-cases.html
hide_sidebar: true
hide_navtoggle: true
toc: false
folder: design-docs/granular-reviewer-read-markers
---

# Use Cases

Primary use case:

A reviewer wants to track exactly which parts of a file have been read,
instead of only marking an entire file as reviewed. The reviewer marks
one or more line ranges in the diff as read and later returns to finish
the remaining unread regions.

Another reviewer wants to understand review coverage on a change without
guessing from comments or votes. Gerrit shows which files and regions
have already been read by each reviewer and summarizes the shared review
progress for the current patch set.

An author uploads a new patch set. Reviewers expect read state to carry
forward for unchanged regions so they can focus on newly modified code
instead of repeating work on content that has already been reviewed.

Secondary use cases:

Project owners want a lightweight notion of review progress that is more
precise than file-level reviewed flags, but cheaper and less opinionated
than mandatory checklist workflows.

Reviewers want to filter the file list by unread content and jump to the
next unread region instead of the next unread file.

Non-goals:

This design does not attempt to infer semantic understanding of code.
It only records that a reviewer marked a region as read.

This design does not replace labels, comments, attention set updates, or
submit requirements. Read markers are progress metadata, not approval.

This design does not define audit or compliance policies about what
"read" means for a given organization.

## <a id="acceptance-criteria"> Acceptance Criteria

Reviewers can mark and unmark read state for a contiguous region within
a file of a patch set.

Gerrit exposes both private "my read markers" and shared "reviewer read
markers" views with normal change visibility checks. Users never learn
about markers on changes or patch sets they cannot read.

The file list and diff view display per-file and per-region progress for
the current patch set, including which reviewers have marked a region as
read.

When a new patch set is uploaded, read markers from an older patch set
are carried forward only for unchanged regions. Changed lines become
unread by default.

The current file-level reviewed flag behavior remains available during
migration and can be derived from region-level state for compatibility.

The additional storage and API traffic scale to large changes with many
reviewers without requiring a full diff recomputation on every page
load.

## <a id="background"> Background

Today Gerrit stores reviewed state as a private tuple of patch set,
file, and account. The current REST API only supports:

* listing files marked reviewed by the calling user
* setting or deleting the calling user's reviewed flag for a file

This state is implemented by `AccountPatchReviewStore`, exposed through
`Files` and `Reviewed` REST endpoints, and consumed by PolyGerrit as a
list of file paths. Gerrit already copies file-level reviewed state to a
new patch set when a file is unchanged, but that copy is private and
coarse-grained.

The current model breaks down for larger reviews:

* reviewers often read only part of a large file
* teams cannot see review coverage across reviewers
* a one-line change resets the reviewed state for an entire file from a
  usability perspective

The proposed feature extends reviewed flags into shared, region-level
read tracking while preserving Gerrit's existing review model and
permission boundaries.
