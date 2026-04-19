/**
 * @license
 * Copyright 2015 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import '../../plugins/gr-endpoint-decorator/gr-endpoint-decorator';
import '../../plugins/gr-endpoint-param/gr-endpoint-param';
import '../../../styles/gr-a11y-styles';
import '../../../styles/shared-styles';
import '../../shared/gr-button/gr-button';
import '../../shared/gr-dropdown/gr-dropdown';
import '../../shared/gr-dropdown-list/gr-dropdown-list';
import '../../shared/gr-icon/gr-icon';
import '../../shared/gr-weblink/gr-weblink';
import '../../shared/revision-info/revision-info';
import '../gr-apply-fix-dialog/gr-apply-fix-dialog';
import '../gr-diff-host/gr-diff-host';
import '../gr-diff-mode-selector/gr-diff-mode-selector';
import '../gr-diff-preferences-dialog/gr-diff-preferences-dialog';
import '../gr-patch-range-select/gr-patch-range-select';
import '../../change/gr-download-dialog/gr-download-dialog';
import {getAppContext} from '../../../services/app-context';
import {getParentIndex, isMergeParent} from '../../../utils/patch-set-util';
import {
  computeDisplayPath,
  computeTruncatedPath,
  isMagicPath,
} from '../../../utils/path-list-util';
import {changeBaseURL, changeIsOpen} from '../../../utils/change-util';
import {GrDiffHost} from '../../diff/gr-diff-host/gr-diff-host';
import {
  DropdownItem,
  GrDropdownList,
} from '../../shared/gr-dropdown-list/gr-dropdown-list';
import {CommentAnchorTapEventDetail} from '../../shared/gr-comment/gr-comment';
import {ChangeComments} from '../../diff/gr-comment-api/gr-comment-api';
import {
  AccountDetailInfo,
  AccountInfo,
  BasePatchSetNum,
  Comment,
  CommentMap,
  DropdownLink,
  EDIT,
  NumericChangeId,
  PARENT,
  PatchRange,
  PatchSetNumber,
  PreferencesInfo,
  RepoName,
  RevisionPatchSetNum,
} from '../../../types/common';
import {ReviewerState} from '../../../api/rest-api';
import {DiffInfo, DiffPreferencesInfo, WebLinkInfo} from '../../../types/diff';
import {
  DiffLayer,
  DiffLayerListener,
  ParsedChangeInfo,
} from '../../../types/types';
import {
  FilesWebLinks,
  PatchRangeChangeEvent,
} from '../gr-patch-range-select/gr-patch-range-select';
import {GrDiffCursor} from '../../../embed/diff/gr-diff-cursor/gr-diff-cursor';
import {CommentSide, DiffViewMode, Side} from '../../../constants/constants';
import {GrApplyFixDialog} from '../gr-apply-fix-dialog/gr-apply-fix-dialog';
import {OpenFixPreviewEvent, ValueChangedEvent} from '../../../types/events';
import {fire, fireAlert} from '../../../utils/event-util';
import {assertIsDefined} from '../../../utils/common-util';
import {whenVisible} from '../../../utils/dom-util';
import {CursorMoveResult} from '../../../api/core';
import {throttleWrap} from '../../../utils/async-util';
import {filter, map, switchMap, take} from 'rxjs/operators';
import {combineLatest} from 'rxjs';
import {
  Shortcut,
  ShortcutSection,
  shortcutsServiceToken,
} from '../../../services/shortcuts/shortcuts-service';
import {
  DisplayLine,
  FileRange,
  GrDiffLine,
  GrDiffLineType,
  LineSelectedEventDetail,
} from '../../../api/diff';
import {GrDownloadDialog} from '../../change/gr-download-dialog/gr-download-dialog';
import {commentsModelToken} from '../../../models/comments/comments-model';
import {changeModelToken} from '../../../models/change/change-model';
import {resolve} from '../../../models/dependency';
import {css, html, LitElement, nothing, PropertyValues} from 'lit';
import {ShortcutController} from '../../lit/shortcut-controller';
import {subscribe} from '../../lit/subscription-controller';
import {customElement, property, query, state} from 'lit/decorators.js';
import {a11yStyles} from '../../../styles/gr-a11y-styles';
import {sharedStyles} from '../../../styles/shared-styles';
import {ifDefined} from 'lit/directives/if-defined.js';
import {when} from 'lit/directives/when.js';
import {styleMap} from 'lit/directives/style-map.js';
import {
  ChangeChildView,
  changeViewModelToken,
} from '../../../models/views/change';
import {userModelToken} from '../../../models/user/user-model';
import {modalStyles} from '../../../styles/gr-modal-styles';
import {GrDiffPreferencesDialog} from '../gr-diff-preferences-dialog/gr-diff-preferences-dialog';
import {
  FileNameToNormalizedFileInfoMap,
  filesModelToken,
} from '../../../models/change/files-model';
import {
  LineReviewMarkerService,
  MockLineReviewMarkerService,
  RestLineReviewMarkerService,
} from '../gr-line-review-marker/line-review-marker-service';
import {LineReviewHistoryInfo} from '../../../services/gr-rest-api/gr-rest-api';
import {isImageDiff} from '../../../utils/diff-util';
import {formStyles} from '../../../styles/form-styles';
import {NormalizedFileInfo} from '../../change/gr-file-list/gr-file-list';
import {configModelToken} from '../../../models/config/config-model';
import {trimWithEllipsis} from '../../../utils/string-util';
import '@material/web/checkbox/checkbox';
import {MdCheckbox} from '@material/web/checkbox/checkbox';
import {materialStyles} from '../../../styles/gr-material-styles';

const LOADING_BLAME = 'Loading blame information. This may take a while ...';
const LOADED_BLAME = 'Blame loaded';

// Time in which pressing n key again after the toast navigates to next file
const NAVIGATE_TO_NEXT_FILE_TIMEOUT_MS = 5000;

// Files larger than this cannot be downloaded.
const FILE_DOWNLOAD_LIMIT_BYTES = 50 * 1000 * 1000;

// visible for testing
export interface Files {
  /** All file paths sorted by `specialFilePathCompare`. */
  sortedPaths: string[];
  changeFilesByPath: FileNameToNormalizedFileInfoMap;
}

type LineReadStatus = 'read' | 'tentative' | 'unread';

interface ReviewerHistoryEntry {
  id: string;
  name: string;
  color: string;
}

interface ReviewerDot {
  color: string;
  label: string;
}

const REVIEWER_HISTORY_COLORS = [
  '#4285f4',
  '#a142f4',
  '#ff6d01',
  '#0b8043',
  '#c5221f',
  '#00796b',
];

class LineReadStatusLayer implements DiffLayer {
  private listeners: DiffLayerListener[] = [];

  private markedLines = new Set<string>();

  private explicitlyUnreadLines = new Set<string>();

  private dragState?: {path: string; marked: boolean};

  constructor(
    private readonly getPath: () => string | undefined,
    private readonly onToggle: (path: string, lineNum: number, marked: boolean) => void,
    private readonly getReviewerDots: (path: string, lineNum: number) => ReviewerDot[],
    private readonly getReviewerSlotCount: () => number
  ) {}

  addListener(listener: DiffLayerListener) {
    this.listeners.push(listener);
  }

  removeListener(listener: DiffLayerListener) {
    this.listeners = this.listeners.filter(l => l !== listener);
  }

  annotate(
    _textEl: HTMLElement,
    lineNumberEl: HTMLElement,
    line: GrDiffLine,
    side: Side
  ) {
    if (side !== Side.RIGHT) return;
    const dataValue = lineNumberEl.getAttribute('data-value');
    const lineNum = dataValue ? Number(dataValue) : NaN;
    if (!Number.isInteger(lineNum) || lineNum <= 0) return;

    const status = this.computeStatus(lineNum, line);
    let indicator = lineNumberEl.querySelector<HTMLElement>(
      '.lineReadStatusIndicator'
    );
    let reviewerContainer = lineNumberEl.querySelector<HTMLElement>(
      '.lineReviewerDots'
    );
    if (!indicator) {
      indicator = document.createElement('button');
      indicator.className = 'lineReadStatusIndicator';
      indicator.setAttribute('aria-hidden', 'true');
      indicator.setAttribute('type', 'button');
      indicator.style.position = 'absolute';
      indicator.style.left = '4px';
      indicator.style.top = '50%';
      indicator.style.transform = 'translateY(-50%)';
      indicator.style.width = '8px';
      indicator.style.height = '8px';
      indicator.style.borderRadius = '50%';
      indicator.style.padding = '0';
      indicator.style.border = '0';
      indicator.style.outline = 'none';
      indicator.style.cursor = 'pointer';
      indicator.style.boxShadow = 'none';
      indicator.style.zIndex = '1';
      lineNumberEl.style.position = 'relative';
      lineNumberEl.append(indicator);
    }
    if (!reviewerContainer) {
      reviewerContainer = document.createElement('span');
      reviewerContainer.className = 'lineReviewerDots';
      reviewerContainer.style.position = 'absolute';
      reviewerContainer.style.left = '18px';
      reviewerContainer.style.top = '50%';
      reviewerContainer.style.transform = 'translateY(-50%)';
      reviewerContainer.style.display = 'inline-flex';
      reviewerContainer.style.alignItems = 'center';
      reviewerContainer.style.gap = '4px';
      reviewerContainer.style.pointerEvents = 'none';
      reviewerContainer.style.zIndex = '1';
      lineNumberEl.append(reviewerContainer);
    }
    const path = this.getPath();
    if (!path) return;
    const reviewerDots = this.getReviewerDots(path, lineNum);
    reviewerContainer.replaceChildren(
      ...reviewerDots.map(dot => {
        const reviewerDot = document.createElement('span');
        reviewerDot.style.width = '6px';
        reviewerDot.style.height = '6px';
        reviewerDot.style.borderRadius = '50%';
        reviewerDot.style.backgroundColor = dot.color;
        reviewerDot.title = dot.label;
        return reviewerDot;
      })
    );
    const reservedWidth = 18 + this.getReviewerSlotCount() * 10;
    lineNumberEl.style.paddingLeft = `${reservedWidth}px`;
    indicator.style.backgroundColor = this.getStatusColor(status);
    indicator.title = this.getStatusTitle(status);
    indicator.setAttribute('aria-label', this.getStatusTitle(status));
    indicator.onmousedown = event => {
      event.preventDefault();
      event.stopPropagation();
      const marked = status !== 'read';
      this.dragState = {path, marked};
      this.onToggle(path, lineNum, marked);
    };
    indicator.onmouseenter = event => {
      if ((event.buttons & 1) !== 1 || !this.dragState) return;
      event.preventDefault();
      event.stopPropagation();
      if (this.dragState.path !== (this.getPath() ?? '')) return;
      this.onToggle(this.dragState.path, lineNum, this.dragState.marked);
    };
    indicator.onclick = event => {
      event.preventDefault();
      event.stopPropagation();
    };
    window.onmouseup = () => {
      this.dragState = undefined;
    };
  }

  setMarked(path: string, lineNum: number, marked: boolean) {
    const key = this.computeKey(path, lineNum);
    if (marked) {
      this.markedLines.add(key);
      this.explicitlyUnreadLines.delete(key);
    } else {
      this.markedLines.delete(key);
      // Unmarking should revert to the default status (e.g. tentative for BOTH),
      // not force the line to be explicitly unread.
      this.explicitlyUnreadLines.delete(key);
    }
    for (const listener of this.listeners) {
      listener(lineNum, lineNum, Side.RIGHT);
    }
  }

  clearMarks() {
    this.markedLines.clear();
    this.explicitlyUnreadLines.clear();
  }

  private computeStatus(lineNum: number, line: GrDiffLine): LineReadStatus {
    const key = this.computeKey(this.getPath() ?? '', lineNum);
    if (this.markedLines.has(key)) {
      return 'read';
    }
    if (this.explicitlyUnreadLines.has(key)) {
      return 'unread';
    }
    return line.type === GrDiffLineType.BOTH ? 'tentative' : 'unread';
  }

  private computeKey(path: string, lineNum: number) {
    return `${path}:${lineNum}`;
  }

  private getStatusColor(status: LineReadStatus) {
    if (status === 'read') return '#34a853';
    if (status === 'tentative') return '#fbbc04';
    return '#9aa0a6';
  }

  private getStatusTitle(status: LineReadStatus) {
    if (status === 'read') return 'Read';
    if (status === 'tentative') return 'Tentatively read';
    return 'Unread';
  }
}

@customElement('gr-diff-view')
export class GrDiffView extends LitElement {
  /**
   * Fired when user tries to navigate away while comments are pending save.
   *
   * @event show-alert
   */
  @query('#diffHost')
  diffHost?: GrDiffHost;

  @state()
  reviewed = false;

  @query('#downloadModal')
  downloadModal?: HTMLDialogElement;

  @query('#downloadDialog')
  downloadDialog?: GrDownloadDialog;

  @query('#dropdown')
  dropdown?: GrDropdownList;

  @query('#applyFixDialog')
  applyFixDialog?: GrApplyFixDialog;

  @query('#diffPreferencesDialog')
  diffPreferencesDialog?: GrDiffPreferencesDialog;

  @query('.sidebarAnchor')
  sidebarAnchor?: HTMLDivElement;

  @state() private sidebarHeight = 0;

  // Private but used in tests.
  @state()
  get patchRange(): PatchRange | undefined {
    if (!this.patchNum) return undefined;
    return {
      patchNum: this.patchNum,
      basePatchNum: this.basePatchNum,
    };
  }

  // Private but used in tests.
  @state()
  patchNum?: RevisionPatchSetNum;

  // Private but used in tests.
  @state()
  basePatchNum: BasePatchSetNum = PARENT;

  // Private but used in tests.
  @state()
  change?: ParsedChangeInfo;

  @state()
  latestPatchNum?: PatchSetNumber;

  // Private but used in tests.
  @state()
  changeComments?: ChangeComments;

  // Private but used in tests.
  @state()
  changeNum?: NumericChangeId;

  // Private but used in tests.
  @state()
  diff?: DiffInfo;

  // Private but used in tests.
  @state()
  files: Files = {sortedPaths: [], changeFilesByPath: {}};

  @state() path?: string;

  @state() file?: NormalizedFileInfo;

  @state() private shownSidebar?: string;

  /** Allows us to react when the user switches to the DIFF view. */
  // Private but used in tests.
  @state() isActiveChildView = false;

  // Whether to allow the "Show Blame button"
  @state()
  allowBlame = false;

  // Private but used in tests.
  @state()
  loggedIn = false;

  @property({type: Object})
  prefs?: DiffPreferencesInfo;

  // Private but used in tests.
  @state()
  userPrefs?: PreferencesInfo;

  @state()
  private editWeblinks?: WebLinkInfo[];

  @state()
  private filesWeblinks?: FilesWebLinks;

  // Private but used in tests.
  @state()
  isBlameLoaded?: boolean;

  @state()
  private isBlameLoading = false;

  /** Directly reflects the view model property `diffView.lineNum`. */
  // Private but used in tests.
  @state()
  focusLineNum?: number;

  /** Directly reflects the view model property `diffView.leftSide`. */
  @state()
  leftSide = false;

  @state()
  commentsForPath: Comment[] = [];

  @state()
  isShowingEntireFile = false;

  @state()
  private selectedDiffLine?: DisplayLine;

  @state()
  private reviewerHistory: ReviewerHistoryEntry[] = [];

  @state()
  private currentReviewer?: AccountDetailInfo;

  @state()
  private currentReviewerReviewedLines: string[] = [];

  @state()
  private reviewerReviewedLineKeys = new Map<string, Set<string>>();

  @state()
  private reviewHistoryMinimized = false;

  // visible for testing
  reviewedFiles = new Set<string>();

  private readonly lineReadStatusLayer = new LineReadStatusLayer(
    () => this.path,
    (path, lineNum, marked) => {
      this.lineReadStatusLayer.setMarked(path, lineNum, marked);
      this.updateCurrentReviewerLine(path, lineNum, marked);
      this.lineReviewMarkerService.saveLineRangeMarked({
        path,
        side: Side.RIGHT,
        startLine: lineNum,
        endLine: lineNum,
        marked,
      });
    },
    (path, lineNum) => this.getReviewerDots(path, lineNum),
    () => this.reviewerHistory.length
  );

  @state()
  private lineReviewMarkerService: LineReviewMarkerService =
    new MockLineReviewMarkerService();

  private readonly reporting = getAppContext().reportingService;

  private readonly getUserModel = resolve(this, userModelToken);

  private readonly getChangeModel = resolve(this, changeModelToken);

  private readonly getCommentsModel = resolve(this, commentsModelToken);

  private readonly getFilesModel = resolve(this, filesModelToken);

  private readonly getShortcutsService = resolve(this, shortcutsServiceToken);

  private readonly getViewModel = resolve(this, changeViewModelToken);

  private readonly getConfigModel = resolve(this, configModelToken);

  private throttledToggleFileReviewed?: (e: KeyboardEvent) => void;

  // Show the popup for navigating to next file keyboard shortcut only once
  private hasShownNavigateToFileToast = new Map<string, boolean>();

  private lineReviewHistoryRequestId = 0;

  @state()
  cursor?: GrDiffCursor;

  private readonly shortcutsController = new ShortcutController(this);

  constructor() {
    super();
    this.setupKeyboardShortcuts();
    this.setupSubscriptions();
    subscribe(
      this,
      () => this.getFilesModel().filesIncludingUnmodified$,
      files => {
        const filesByPath: FileNameToNormalizedFileInfoMap = {};
        for (const f of files) filesByPath[f.__path] = f;
        this.files = {
          sortedPaths: files.map(f => f.__path),
          changeFilesByPath: filesByPath,
        };
      }
    );
  }

  private setupKeyboardShortcuts() {
    const listen = (shortcut: Shortcut, fn: (e: KeyboardEvent) => void) => {
      this.shortcutsController.addAbstract(shortcut, fn);
    };
    listen(Shortcut.LEFT_PANE, _ => this.cursor?.moveLeft());
    listen(Shortcut.RIGHT_PANE, _ => this.cursor?.moveRight());
    listen(Shortcut.NEXT_LINE, _ => this.handleNextLine());
    listen(Shortcut.PREV_LINE, _ => this.handlePrevLine());
    listen(Shortcut.VISIBLE_LINE, _ => this.cursor?.moveToVisibleArea());
    listen(Shortcut.NEXT_FILE_WITH_COMMENTS, _ =>
      this.moveToFileWithComment(1)
    );
    listen(Shortcut.PREV_FILE_WITH_COMMENTS, _ =>
      this.moveToFileWithComment(-1)
    );
    listen(Shortcut.NEW_COMMENT, _ => this.handleNewComment());
    listen(Shortcut.SAVE_COMMENT, _ => {});
    listen(Shortcut.NEXT_FILE, _ => this.handleNextFile());
    listen(Shortcut.PREV_FILE, _ => this.handlePrevFile());
    listen(Shortcut.NEXT_CHUNK, _ => this.handleNextChunk());
    listen(Shortcut.PREV_CHUNK, _ => this.handlePrevChunk());
    listen(Shortcut.NEXT_COMMENT_THREAD, _ => this.handleNextCommentThread());
    listen(Shortcut.PREV_COMMENT_THREAD, _ => this.handlePrevCommentThread());
    listen(Shortcut.OPEN_REPLY_DIALOG, _ => this.handleOpenReplyDialog());
    listen(Shortcut.TOGGLE_LEFT_PANE, _ => this.handleToggleLeftPane());
    listen(Shortcut.OPEN_DOWNLOAD_DIALOG, _ => this.handleOpenDownloadDialog());
    listen(Shortcut.UP_TO_CHANGE, _ =>
      this.getChangeModel().navigateToChange()
    );
    listen(Shortcut.OPEN_DIFF_PREFS, _ => this.handleCommaKey());
    listen(Shortcut.TOGGLE_DIFF_MODE, _ => this.handleToggleDiffMode());
    listen(Shortcut.TOGGLE_FILE_REVIEWED, e => {
      if (this.throttledToggleFileReviewed) {
        this.throttledToggleFileReviewed(e);
      }
    });
    listen(Shortcut.TOGGLE_ALL_DIFF_CONTEXT, _ =>
      this.handleToggleAllDiffContext()
    );
    listen(Shortcut.TOGGLE_HIDE_CHECK_CODE_POINTERS, _ =>
      this.handleToggleHideCheckCodePointers()
    );
    listen(Shortcut.NEXT_UNREVIEWED_FILE, _ => this.handleNextUnreviewedFile());
    listen(Shortcut.TOGGLE_BLAME, _ => this.toggleBlame());
    listen(Shortcut.TOGGLE_HIDE_ALL_COMMENT_THREADS_AND_CODE_POINTERS, _ =>
      this.handleToggleHideAllCommentsAndCodePointers()
    );
    listen(Shortcut.OPEN_FILE_LIST, _ => this.handleOpenFileList());
    listen(Shortcut.DIFF_AGAINST_BASE, _ => this.handleDiffAgainstBase());
    listen(Shortcut.DIFF_AGAINST_LATEST, _ => this.handleDiffAgainstLatest());
    listen(Shortcut.DIFF_BASE_AGAINST_LEFT, _ =>
      this.handleDiffBaseAgainstLeft()
    );
    listen(Shortcut.DIFF_RIGHT_AGAINST_LATEST, _ =>
      this.handleDiffRightAgainstLatest()
    );
    listen(Shortcut.DIFF_BASE_AGAINST_LATEST, _ =>
      this.handleDiffBaseAgainstLatest()
    );
    listen(Shortcut.EXPAND_ALL_COMMENT_THREADS, _ => {}); // docOnly
    listen(Shortcut.COLLAPSE_ALL_COMMENT_THREADS, _ => {}); // docOnly
  }

  private setupSubscriptions() {
    subscribe(
      this,
      () => this.getUserModel().loggedIn$,
      loggedIn => {
        this.loggedIn = loggedIn;
      }
    );
    subscribe(
      this,
      () => this.getUserModel().account$,
      account => {
        this.currentReviewer = account;
        this.syncReviewerHistory();
        this.syncCurrentReviewerReviewedLines();
      }
    );
    subscribe(
      this,
      () => this.getCommentsModel().changeComments$,
      changeComments => {
        this.changeComments = changeComments;
      }
    );
    subscribe(
      this,
      () => this.getUserModel().preferences$,
      preferences => {
        this.userPrefs = preferences;
      }
    );
    subscribe(
      this,
      () => this.getUserModel().diffPreferences$,
      diffPreferences => {
        this.prefs = diffPreferences;
      }
    );
    subscribe(
      this,
      () => this.getChangeModel().change$,
      change => {
        // The diff view is tied to a specific change number, so don't update
        // change to undefined.
        if (change) {
          this.change = change;
          this.syncReviewerHistory();
        }
      }
    );
    subscribe(
      this,
      () => this.getChangeModel().latestPatchNum$,
      latestPatchNum => (this.latestPatchNum = latestPatchNum)
    );
    subscribe(
      this,
      () => this.getChangeModel().reviewedFiles$,
      reviewedFiles => {
        this.reviewedFiles = new Set(reviewedFiles) ?? new Set();
      }
    );
    subscribe(
      this,
      () => this.getViewModel().changeNum$,
      changeNum => {
        if (!changeNum || this.changeNum === changeNum) return;

        // We are only setting the changeNum of the diff view once.
        // Everything in the diff view is tied to the change. It seems better to
        // force the re-creation of the diff view when the change number changes.
        // The parent element will make sure that a new change view is created
        // when the change number changes (using the `keyed` directive).
        if (!this.changeNum) this.changeNum = changeNum;
      }
    );
    subscribe(
      this,
      () => this.getViewModel().childView$,
      childView => (this.isActiveChildView = childView === ChangeChildView.DIFF)
    );
    subscribe(
      this,
      () => this.getViewModel().diffPath$,
      path => (this.path = path)
    );
    subscribe(
      this,
      () => this.getFilesModel().file$(this.getViewModel().diffPath$),
      file => (this.file = file)
    );
    subscribe(
      this,
      () => this.getViewModel().diffLine$,
      line => (this.focusLineNum = line)
    );
    subscribe(
      this,
      () => this.getViewModel().diffLeftSide$,
      leftSide => (this.leftSide = leftSide)
    );
    subscribe(
      this,
      () => this.getViewModel().patchNum$,
      patchNum => (this.patchNum = patchNum)
    );
    subscribe(
      this,
      () => this.getViewModel().basePatchNum$,
      basePatchNum => (this.basePatchNum = basePatchNum ?? PARENT)
    );
    subscribe(
      this,
      () =>
        combineLatest([
          this.getViewModel().diffPath$,
          this.getChangeModel().reviewedFiles$,
        ]),
      ([path, files]) => {
        this.reviewed = !!path && !!files && files.includes(path);
      }
    );

    subscribe(
      this,
      () => this.getConfigModel().serverConfig$,
      serverConfig =>
        (this.allowBlame = serverConfig?.change.allow_blame ?? false)
    );

    // When user initially loads the diff view, we want to automatically mark
    // the file as reviewed if they have it enabled. We can't observe these
    // properties since the method will be called anytime a property updates
    // but we only want to call this on the initial load.
    subscribe(
      this,
      () =>
        this.getViewModel().diffPath$.pipe(
          filter(diffPath => !!diffPath),
          switchMap(() =>
            combineLatest([
              this.getChangeModel().patchNum$,
              this.getViewModel().childView$,
              this.getUserModel().diffPreferences$,
              this.getChangeModel().reviewedFiles$,
            ]).pipe(
              filter(
                ([patchNum, childView, diffPrefs, reviewedFiles]) =>
                  !!patchNum &&
                  childView === ChangeChildView.DIFF &&
                  !!diffPrefs &&
                  !!reviewedFiles
              ),
              take(1)
            )
          )
        ),
      ([patchNum, _routerView, diffPrefs]) => {
        // `patchNum` must be defined, because of the `!!patchNum` filter above.
        assertIsDefined(patchNum, 'patchNum');
        this.setReviewedStatus(patchNum, diffPrefs);
      }
    );
  }

  static override get styles() {
    return [
      formStyles,
      a11yStyles,
      sharedStyles,
      modalStyles,
      materialStyles,
      css`
        :host {
          display: block;
          background-color: var(--view-background-color);
          --sidebar-width: 300px;
        }
        .hidden {
          display: none;
        }
        .headerLeft {
          display: flex;
          align-items: center;
        }
        gr-patch-range-select {
          display: block;
        }
        gr-diff {
          border: none;
        }
        .stickyHeader {
          background-color: var(--view-background-color);
          position: sticky;
          top: var(--main-header-height);
          /* sidebar should outrank <footer> in GrAppElement */
          z-index: 110;
          box-shadow: var(--elevation-level-1);
          /* This is just for giving the box-shadow some space. */
          margin-bottom: 2px;
        }
        header,
        .subHeader {
          align-items: center;
          display: flex;
          justify-content: space-between;
        }
        header {
          padding: var(--spacing-s) var(--spacing-xl);
          border-bottom: 1px solid var(--border-color);
        }
        .changeNumberColon {
          color: transparent;
        }
        .headerSubject {
          margin-left: var(--spacing-s);
          margin-right: var(--spacing-m);
          font-weight: var(--font-weight-medium);
          white-space: nowrap;
          overflow: auto;
        }
        .patchRangeLeft {
          align-items: center;
          display: flex;
        }
        .navLink:not([href]) {
          color: var(--deemphasized-text-color);
        }
        .navLinks {
          align-items: center;
          display: flex;
          white-space: nowrap;
        }
        .navLink {
          padding: 0 var(--spacing-xs);
        }
        .reviewed {
          margin: 0 var(--spacing-xs);
          flex-shrink: 0;
        }
        .jumpToFileContainer {
          display: inline-block;
          word-break: break-all;
        }
        .mobile {
          display: none;
        }
        gr-button {
          padding: var(--spacing-s) 0;
          text-decoration: none;
        }
        .loading {
          color: var(--deemphasized-text-color);
          font-family: var(--header-font-family);
          font-size: var(--font-size-h1);
          font-weight: var(--font-weight-h1);
          line-height: var(--line-height-h1);
          height: 100%;
          padding: var(--spacing-l);
          text-align: center;
        }
        .subHeader {
          background-color: var(--background-color-secondary);
          flex-wrap: wrap;
          padding: 0 var(--spacing-l);
        }
        .prefsButton {
          text-align: right;
        }
        .editMode .hideOnEdit {
          display: none;
        }
        .fileNum {
          display: none;
        }
        .blameLoader,
        .fileNum.show,
        .download,
        .preferences,
        .rightControls {
          align-items: center;
          display: flex;
        }
        .diffModeSelector,
        .editButton {
          align-items: center;
          display: flex;
        }
        .diffModeSelector span,
        .editButton span {
          margin-right: var(--spacing-xs);
        }
        .diffModeSelector.hide,
        .separator.hide {
          display: none;
        }
        .editButtona a {
          text-decoration: none;
        }
        .checkboxDiv {
          display: flex;
        }
        gr-dropdown-list {
          --gr-dropdown-copy-clipboard-margin-left: var(--spacing-s);
        }
        @media screen and (max-width: 50em) {
          header {
            padding: var(--spacing-s) var(--spacing-l);
          }
          .dash {
            display: none;
          }
          .desktop {
            display: none;
          }
          .fileNav {
            align-items: flex-start;
            display: flex;
            margin: 0 var(--spacing-xs);
          }
          .fullFileName {
            display: block;
            font-style: italic;
            min-width: 50%;
            padding: 0 var(--spacing-xxs);
            text-align: center;
            width: 100%;
            word-wrap: break-word;
          }
          .mobileNavLink {
            color: var(--primary-text-color);
            font-family: var(--header-font-family);
            font-size: var(--font-size-h2);
            font-weight: var(--font-weight-h2);
            line-height: var(--line-height-h2);
            text-decoration: none;
          }
          .mobileNavLink:not([href]) {
            color: var(--deemphasized-text-color);
          }
          /* prettier formatter removes semi-colons after css mixins. */
          /* prettier-ignore */
          gr-dropdown-list {
            width: 100%;
          }
        }
        :host(.hideComments) {
          --gr-comment-thread-display: none;
        }
        :host(.hideCheckCodePointers) {
          --gr-check-code-pointers-display: none;
        }
        .diffBody {
          align-items: flex-start;
          display: flex;
          gap: var(--spacing-l);
        }
        .diffHostContainer {
          flex: 1;
          min-width: 0;
        }
        .diffContainer.sidebarOpen {
          margin-left: var(--sidebar-width);
        }
        .reviewHistoryPanel {
          background: var(--background-color-primary);
          border: 1px solid var(--border-color);
          border-radius: 16px;
          box-shadow: var(--elevation-level-1);
          flex: 0 0 280px;
          overflow: hidden;
          position: sticky;
          top: var(--spacing-xl);
        }
        .reviewHistoryPanel.minimized {
          flex: 0 0 auto;
          width: fit-content;
        }
        .reviewHistoryHeader {
          align-items: center;
          background: var(--background-color-secondary);
          border-bottom: 1px solid var(--border-color);
          display: flex;
          justify-content: space-between;
          padding: var(--spacing-l);
        }
        .reviewHistoryPanel.minimized .reviewHistoryHeader {
          border-bottom: 0;
        }
        .reviewHistoryHeaderContent {
          display: grid;
          gap: var(--spacing-xxs);
        }
        .reviewHistoryTitle {
          font-size: var(--font-size-h3);
          font-weight: var(--font-weight-bold);
        }
        .reviewHistorySubtitle {
          color: var(--deemphasized-text-color);
          font-size: var(--font-size-small);
        }
        .reviewHistoryToggle {
          background: transparent;
          border: 1px solid var(--border-color);
          border-radius: 999px;
          color: var(--link-color);
          cursor: pointer;
          font: inherit;
          padding: 4px var(--spacing-m);
          white-space: nowrap;
        }
        .reviewHistoryToggle:hover {
          background: var(--background-color-primary);
        }
        .reviewHistoryBody {
          display: grid;
          gap: var(--spacing-l);
          padding: var(--spacing-l);
        }
        .historySelection {
          background: var(--background-color-secondary);
          border-radius: 12px;
          padding: var(--spacing-m);
        }
        .historySelectionTitle {
          color: var(--deemphasized-text-color);
          font-size: var(--font-size-small);
          margin-bottom: var(--spacing-s);
        }
        .historySelectionNames {
          align-items: center;
          display: flex;
          flex-wrap: wrap;
          gap: var(--spacing-s);
        }
        .historySelectionEmpty {
          color: var(--deemphasized-text-color);
          font-size: var(--font-size-small);
        }
        .reviewerList {
          display: grid;
          gap: var(--spacing-m);
        }
        .reviewerRow {
          align-items: center;
          display: grid;
          gap: var(--spacing-m);
          grid-template-columns: auto 1fr auto;
        }
        .reviewerIdentity {
          align-items: center;
          display: inline-flex;
          gap: var(--spacing-s);
        }
        .reviewerSwatch {
          border-radius: 999px;
          display: inline-block;
          height: 10px;
          width: 10px;
        }
        .reviewerBadge {
          border: 1px solid var(--border-color);
          border-radius: 999px;
          color: var(--deemphasized-text-color);
          font-size: var(--font-size-small);
          padding: 2px var(--spacing-s);
        }
        .reviewerCount {
          color: var(--deemphasized-text-color);
          font-size: var(--font-size-small);
        }
        .historyLegend {
          color: var(--deemphasized-text-color);
          display: flex;
          flex-wrap: wrap;
          gap: var(--spacing-m);
          font-size: var(--font-size-small);
        }
        .historyLegendItem {
          align-items: center;
          display: inline-flex;
          gap: var(--spacing-s);
        }
        .sidebarTriggerContainer {
          display: inline-block;
          margin-right: var(--spacing-m);
        }
        .sidebarAnchor {
          height: 0;
          width: 0;
          overflow: visible;
        }
        .sidebarContents {
          background: var(--background-color-secondary);
          width: var(--sidebar-width);
          border: var(--spacing-xxs) solid var(--border-color);
          border-left: 0;
          overflow: auto;
        }
        md-checkbox {
          --md-checkbox-container-size: 15px;
          --md-checkbox-icon-size: 15px;
        }
        @media screen and (max-width: 50em) {
          .diffBody {
            flex-direction: column;
          }
          .reviewHistoryPanel {
            position: static;
            width: 100%;
          }
        }
      `,
    ];
  }

  override connectedCallback() {
    super.connectedCallback();
    this.throttledToggleFileReviewed = throttleWrap(_ =>
      this.handleToggleFileReviewed()
    );
    this.addEventListener('open-fix-preview', e => this.onOpenFixPreview(e));
    this.cursor = new GrDiffCursor();
    if (this.diffHost) this.reInitCursor();
    window.addEventListener('scroll', this.updateSidebarHeight);
    window.addEventListener('resize', this.updateSidebarHeight);
    this.getUserModel()
      .preferences$.pipe(
        map(p => p.diff_page_sidebar),
        take(1)
      )
      .toPromise()
      .then(initialSidebar => {
        if (initialSidebar === 'NONE' || initialSidebar === undefined) {
          this.shownSidebar = undefined;
        } else {
          this.shownSidebar = initialSidebar.substring('plugin-'.length);
        }
      });
  }

  override disconnectedCallback() {
    this.cursor?.dispose();
    window.removeEventListener('scroll', this.updateSidebarHeight);
    window.removeEventListener('resize', this.updateSidebarHeight);
    super.disconnectedCallback();
  }

  private reInitCursor() {
    if (!this.diffHost) return;
    this.cursor?.replaceDiffs([this.diffHost]);
    this.cursor?.reInitCursor();
  }

  private readonly updateSidebarHeight = () => {
    if (this.sidebarAnchor) {
      this.sidebarHeight =
        window.innerHeight - this.sidebarAnchor.getBoundingClientRect().bottom;
    }
  };

  protected override updated(changedProperties: PropertyValues): void {
    super.updated(changedProperties);
    if (
      changedProperties.has('change') ||
      changedProperties.has('path') ||
      changedProperties.has('patchNum') ||
      changedProperties.has('basePatchNum')
    ) {
      this.reloadDiff();
      this.lineReadStatusLayer.clearMarks();
      this.reviewerReviewedLineKeys = new Map();
      this.currentReviewerReviewedLines = [];
      if (this.changeNum && this.patchNum) {
        this.lineReviewMarkerService = new RestLineReviewMarkerService(
          getAppContext().restApiService,
          this.changeNum,
          this.patchNum
        );
        this.loadLineMarkers();
      }
    } else if (
      changedProperties.has('isActiveChildView') &&
      this.isActiveChildView
    ) {
      this.initializePositions();
    }
    if (
      changedProperties.has('focusLineNum') ||
      changedProperties.has('leftSide')
    ) {
      this.initCursor();
    }
    if (
      changedProperties.has('change') ||
      changedProperties.has('changeComments') ||
      changedProperties.has('path') ||
      changedProperties.has('patchNum') ||
      changedProperties.has('basePatchNum') ||
      changedProperties.has('files')
    ) {
      if (this.change && this.changeComments && this.path && this.patchRange) {
        assertIsDefined(this.diffHost, 'diffHost');
        const file = this.files?.changeFilesByPath?.[this.path];
        this.diffHost.updateComplete.then(() => {
          assertIsDefined(this.path);
          assertIsDefined(this.patchRange);
          assertIsDefined(this.diffHost);
          assertIsDefined(this.changeComments);
          this.diffHost.threads = this.changeComments.getThreadsBySideForFile(
            {path: this.path, basePath: file?.old_path},
            this.patchRange
          );
        });
      }
    }
    if (
      (changedProperties.has('change') ||
        changedProperties.has('changeComments') ||
        changedProperties.has('path') ||
        changedProperties.has('patchRange')) &&
      this.changeComments !== undefined &&
      this.path !== undefined &&
      this.patchRange !== undefined
    ) {
      this.commentsForPath = this.changeComments.getCommentsForPath(
        this.path,
        this.patchRange
      );
    }
    this.updateSidebarHeight();
  }

  override render() {
    if (!this.isActiveChildView) return nothing;
    if (!this.patchNum || !this.changeNum || !this.change || !this.path) {
      return html`<div class="loading">Loading...</div>`;
    }
    const file = this.getFileRange();
    return html`
      ${this.renderStickyHeader()}
      <h2 class="assistive-tech-only">Diff view</h2>
      <div class="diffContainer ${this.shownSidebar && 'sidebarOpen'}">
        <div class="diffBody">
          <div class="diffHostContainer">
            <gr-diff-host
              id="diffHost"
              .changeNum=${this.changeNum}
              .change=${this.change}
              .patchRange=${this.patchRange}
              .file=${file}
              .lineOfInterest=${this.getLineOfInterest()}
              .path=${this.path}
              .projectName=${this.change?.project}
              .extraLayers=${[this.lineReadStatusLayer]}
              @is-blame-loaded-changed=${this.onIsBlameLoadedChanged}
              @comment-anchor-tap=${this.onCommentAnchorTap}
              @line-selected=${this.onLineSelected}
              @diff-changed=${this.onDiffChanged}
              @edit-weblinks-changed=${this.onEditWeblinksChanged}
              @files-weblinks-changed=${this.onFilesWeblinksChanged}
              @render=${this.reInitCursor}
            >
            </gr-diff-host>
          </div>
          ${this.renderReviewHistoryPanel()}
        </div>
      </div>
      ${this.renderDialogs()}
    `;
  }

  private renderStickyHeader() {
    return html` <div
      class="stickyHeader ${this.patchNum === EDIT ? 'editMode' : ''}"
    >
      <h1 class="assistive-tech-only">
        Diff of ${this.path ? computeTruncatedPath(this.path) : ''}
      </h1>
      <header>${this.renderHeader()}</header>
      <div class="subHeader">
        ${this.renderPatchRangeLeft()} ${this.renderRightControls()}
      </div>
      <div class="fileNav mobile">
        <a class="mobileNavLink" href=${ifDefined(this.computeNavLinkURL(-1))}
          >&lt;</a
        >
        <div class="fullFileName mobile">${computeDisplayPath(this.path)}</div>
        <a class="mobileNavLink" href=${ifDefined(this.computeNavLinkURL(1))}
          >&gt;</a
        >
      </div>
      ${this.renderSidebarContent()}
    </div>`;
  }

  private renderReviewHistoryPanel() {
    const selectedLineNum =
      typeof this.selectedDiffLine?.lineNum === 'number'
        ? this.selectedDiffLine.lineNum
        : undefined;
    const reviewersForSelection =
      selectedLineNum && this.path
        ? this.reviewerHistory.filter(reviewer =>
            this.hasReviewerReviewedLine(reviewer.id, this.path!, selectedLineNum)
          )
        : [];
    return html`
      <aside
        class="reviewHistoryPanel ${this.reviewHistoryMinimized
          ? 'minimized'
          : ''}"
      >
        <div class="reviewHistoryHeader">
          <div class="reviewHistoryHeaderContent">
            <div class="reviewHistoryTitle">History</div>
            ${this.reviewHistoryMinimized
              ? nothing
              : html`
                  <div class="reviewHistorySubtitle">
                    Reviewer presence updates as lines are marked read.
                  </div>
                `}
          </div>
          <button
            class="reviewHistoryToggle"
            type="button"
            aria-expanded=${String(!this.reviewHistoryMinimized)}
            @click=${this.onReviewHistoryToggle}
          >
            ${this.reviewHistoryMinimized ? 'Expand' : 'Minimize'}
          </button>
        </div>
        ${this.reviewHistoryMinimized
          ? nothing
          : html`
              <div class="reviewHistoryBody">
                <div class="historySelection">
                  <div class="historySelectionTitle">
                    ${selectedLineNum
                      ? `Line ${selectedLineNum}`
                      : 'Select a line'}
                  </div>
                  ${reviewersForSelection.length
                    ? html`
                        <div class="historySelectionNames">
                          ${reviewersForSelection.map(
                            reviewer => html`
                              <span class="reviewerIdentity">
                                <span
                                  class="reviewerSwatch"
                                  style=${styleMap({
                                    backgroundColor: reviewer.color,
                                  })}
                                ></span>
                                <span>${reviewer.name}</span>
                              </span>
                            `
                          )}
                        </div>
                      `
                    : html`
                        <div class="historySelectionEmpty">
                          No reviewer history for the current selection yet.
                        </div>
                      `}
                </div>
                <div class="reviewerList">
                  ${this.reviewerHistory.length
                    ? this.reviewerHistory.map(
                        reviewer => html`
                          <div class="reviewerRow">
                            <div class="reviewerIdentity">
                              <span
                                class="reviewerSwatch"
                                style=${styleMap({
                                  backgroundColor: reviewer.color,
                                })}
                              ></span>
                              <span>${reviewer.name}</span>
                            </div>
                            ${this.isCurrentReviewer(reviewer.id)
                              ? html`<span class="reviewerBadge">Live</span>`
                              : html`<span></span>`}
                            <span class="reviewerCount">
                              ${this.getReviewerSummaryLabel(reviewer.id)}
                            </span>
                          </div>
                        `
                      )
                    : html`
                        <div class="historySelectionEmpty">
                          No reviewers are assigned to this patch set yet.
                        </div>
                      `}
                </div>
                <div class="historyLegend">
                  <span class="historyLegendItem">
                    <span
                      class="reviewerSwatch"
                      style=${styleMap({backgroundColor: '#34a853'})}
                    ></span>
                    <span>Read</span>
                  </span>
                  <span class="historyLegendItem">
                    <span
                      class="reviewerSwatch"
                      style=${styleMap({backgroundColor: '#fbbc04'})}
                    ></span>
                    <span>Tentative</span>
                  </span>
                  <span class="historyLegendItem">
                    <span
                      class="reviewerSwatch"
                      style=${styleMap({backgroundColor: '#9aa0a6'})}
                    ></span>
                    <span>Unread</span>
                  </span>
                </div>
              </div>
            `}
      </aside>
    `;
  }

  private renderHeader() {
    const formattedFiles = this.formatFilesForDropdown();
    const fileNum = this.computeFileNum(formattedFiles);
    const fileNumClass = this.computeFileNumClass(fileNum, formattedFiles);
    return html` <div class="headerLeft">
        <div>
          <a href=${ifDefined(this.getChangeModel().changeUrl())}
            >${this.changeNum}</a
          ><span class="changeNumberColon">:</span>
        </div>
        <div>
          <span class="headerSubject"
            >${trimWithEllipsis(this.change?.subject, 80)}</span
          >
        </div>
        <div class="checkboxDiv">
          <md-checkbox
            id="reviewed"
            class="reviewed hideOnEdit"
            ?hidden=${!this.loggedIn}
            title="Toggle reviewed status of file"
            aria-label="file reviewed"
            ?checked=${this.reviewed}
            @change=${this.handleReviewedChange}
          ></md-checkbox>
        </div>
        <div class="jumpToFileContainer">
          <gr-dropdown-list
            id="dropdown"
            .value=${this.path ?? ''}
            .items=${formattedFiles}
            show-copy-for-trigger-text
            @value-change=${this.handleFileChange}
          ></gr-dropdown-list>
        </div>
      </div>
      <div class="navLinks desktop">
        <span class="fileNum ${ifDefined(fileNumClass)}">
          File ${fileNum} of ${formattedFiles.length}
          <span class="separator"></span>
        </span>
        <a
          class="navLink"
          title=${this.createTitle(
            Shortcut.PREV_FILE,
            ShortcutSection.NAVIGATION
          )}
          href=${ifDefined(this.computeNavLinkURL(-1))}
          >Prev</a
        >
        <span class="separator"></span>
        <a
          class="navLink"
          title=${this.createTitle(
            Shortcut.UP_TO_CHANGE,
            ShortcutSection.NAVIGATION
          )}
          href=${ifDefined(this.getChangeModel().changeUrl())}
          >Up</a
        >
        <span class="separator"></span>
        <a
          class="navLink"
          title=${this.createTitle(
            Shortcut.NEXT_FILE,
            ShortcutSection.NAVIGATION
          )}
          href=${ifDefined(this.computeNavLinkURL(1))}
          >Next</a
        >
      </div>`;
  }

  private renderSidebarTriggers() {
    return html`
      <div class="sidebarTriggerContainer">
        <gr-endpoint-decorator name="sidebarTrigger">
          <gr-endpoint-param
            name="onTrigger"
            .value=${(pluginName: string) => {
              const closeSidebar = this.shownSidebar === pluginName;
              this.shownSidebar = closeSidebar ? undefined : pluginName;
              this.getUserModel().updatePreferences({
                diff_page_sidebar: closeSidebar
                  ? 'NONE'
                  : `plugin-${pluginName}`,
              });
            }}
          ></gr-endpoint-param>
          <!-- params cannot start falsy, so the value must be wrapped -->
          <gr-endpoint-param
            name="openSidebar"
            .value=${{name: this.shownSidebar}}
          ></gr-endpoint-param>
        </gr-endpoint-decorator>
      </div>
    `;
  }

  private renderSidebarContent() {
    // Always renders the 0x0px .sidebarAnchor div for scroll measurements.
    return html`
      <div class="sidebarAnchor">
        ${when(
          this.shownSidebar !== undefined,
          () => html`
            <div
              class="sidebarContents"
              style=${styleMap({height: `${this.sidebarHeight}px`})}
            >
              <gr-endpoint-decorator
                name=${`sidebarContent-${this.shownSidebar}`}
              >
                <gr-endpoint-param
                  name="change"
                  .value=${this.change}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="path"
                  .value=${this.path}
                ></gr-endpoint-param>
                <!-- current diff path and, in case of rename, previous path -->
                <gr-endpoint-param
                  name="fileRange"
                  .value=${this.getFileRange()}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="basePatchNum"
                  .value=${this.basePatchNum}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="patchNum"
                  .value=${this.patchNum}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="content"
                  .value=${this.diff}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="cursor"
                  .value=${this.cursor}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="diff"
                  .value=${this.diffHost?.diffElement}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="comments"
                  .value=${this.commentsForPath}
                ></gr-endpoint-param>
                <gr-endpoint-param
                  name="onClose"
                  .value=${(pluginName: string) => {
                    // Only close the sidebar if that particular sidebar is
                    // still open. An async onClose callback should not close a
                    // different sidebar.
                    if (this.shownSidebar !== pluginName) return;
                    this.shownSidebar = undefined;
                    this.getUserModel().updatePreferences({
                      diff_page_sidebar: 'NONE',
                    });
                  }}
                >
                </gr-endpoint-param>
              </gr-endpoint-decorator>
            </div>
          `
        )}
      </div>
    `;
  }

  private renderPatchRangeLeft() {
    return html` <div class="patchRangeLeft">
      <gr-patch-range-select
        id="rangeSelect"
        .filesWeblinks=${this.filesWeblinks}
        .path=${this.path}
        @patch-range-change=${this.handlePatchChange}
      >
      </gr-patch-range-select>
      <span class="download desktop">
        <span class="separator"></span>
        <gr-dropdown
          link=""
          down-arrow=""
          .items=${this.computeDownloadDropdownLinks()}
          .disabledIds=${this.isTooLargeForDownload()
            ? ['left-content', 'right-content']
            : []}
          horizontal-align="left"
        >
          <span class="downloadTitle"> Download </span>
        </gr-dropdown>
      </span>
    </div>`;
  }

  private renderRightControls() {
    const diffModeSelectorClass = !this.diff || this.diff.binary ? 'hide' : '';
    return html` <div class="rightControls">
      ${this.renderSidebarTriggers()} ${this.renderShowEntireFileButton()}
      ${this.renderBlameButton()}
      ${when(
        this.computeCanEdit(),
        () => html`
          <span class="separator"></span>
          <span class="editButton">
            <gr-button
              link=""
              title="Edit current file"
              @click=${this.goToEditFile}
              >Edit</gr-button
            >
          </span>
        `
      )}
      ${when(
        this.computeShowEditLinks(),
        () => html`
          <span class="separator"></span>
          ${this.editWeblinks!.map(
            weblink => html`<gr-weblink .info=${weblink}></gr-weblink>`
          )}
        `
      )}
      ${when(
        this.loggedIn && this.prefs,
        () => html`
          <span class="separator"></span>
          <div class="diffModeSelector ${diffModeSelectorClass}">
            <span>Diff view:</span>
            <gr-diff-mode-selector
              id="modeSelect"
              .saveOnChange=${this.loggedIn}
              show-tooltip-below
            ></gr-diff-mode-selector>
          </div>
          <span id="diffPrefsContainer">
            <span class="preferences desktop">
              <gr-tooltip-content
                has-tooltip=""
                position-below=""
                title="Diff preferences"
              >
                <gr-button
                  link=""
                  class="prefsButton"
                  @click=${(e: Event) => this.handlePrefsTap(e)}
                  ><gr-icon icon="settings" filled></gr-icon
                ></gr-button>
              </gr-tooltip-content>
            </span>
          </span>
        `
      )}
      <gr-endpoint-decorator name="annotation-toggler">
        <span hidden="" id="annotation-span">
          <label id="annotation-label" for="annotation-checkbox"></label>
          <md-checkbox
            id="annotation-checkbox"
            touch-target="none"
            disabled
            aria-labelledby="annotation-label"
          ></md-checkbox>
        </span>
      </gr-endpoint-decorator>
    </div>`;
  }

  private renderShowEntireFileButton() {
    if (isImageDiff(this.diff) || isMagicPath(this.path)) return;
    return html`<gr-button
      link=""
      id="toggleEntireFile"
      title=${this.createTitle(
        Shortcut.TOGGLE_ALL_DIFF_CONTEXT,
        ShortcutSection.DIFFS
      )}
      @click=${this.handleToggleAllDiffContext}
      >${this.isShowingEntireFile
        ? 'Hide Unchanged Lines'
        : 'Show Entire File'}</gr-button
    >`;
  }

  private renderBlameButton() {
    if (!this.allowBlame) return;
    const showBlameLoader = !isMagicPath(this.path) && !isImageDiff(this.diff);
    if (!showBlameLoader) return;
    let blameToggleLabel = 'Loading blame ...';
    if (!this.isBlameLoading) {
      blameToggleLabel = this.isBlameLoaded ? 'Hide Blame' : 'Show Blame';
    }
    return html`<span class="separator"></span
      ><span class="blameLoader">
        <gr-button
          link=""
          id="toggleBlame"
          title=${this.createTitle(
            Shortcut.TOGGLE_BLAME,
            ShortcutSection.DIFFS
          )}
          ?disabled=${this.isBlameLoading}
          @click=${this.toggleBlame}
          >${blameToggleLabel}</gr-button
        >
      </span>`;
  }

  private renderDialogs() {
    return html`
      <gr-apply-fix-dialog id="applyFixDialog"></gr-apply-fix-dialog>
      <gr-diff-preferences-dialog id="diffPreferencesDialog">
      </gr-diff-preferences-dialog>
      <dialog id="downloadModal" tabindex="-1">
        <gr-download-dialog
          id="downloadDialog"
          @close=${this.handleDownloadDialogClose}
        ></gr-download-dialog>
      </dialog>
    `;
  }

  /**
   * Set initial review status of the file.
   * automatically mark the file as reviewed if manual review is not set.
   */
  setReviewedStatus(
    patchNum: RevisionPatchSetNum,
    diffPrefs: DiffPreferencesInfo
  ) {
    if (!this.loggedIn) return;
    if (!diffPrefs.manual_review) {
      this.setReviewed(true, patchNum);
    }
  }

  /**
   * Returns the current file path and, if it was renamed in this change, the
   * previous file path.
   */
  private getFileRange() {
    if (!this.files || !this.path) return;
    const fileInfo = this.files.changeFilesByPath[this.path];
    const fileRange: FileRange = {path: this.path};
    if (fileInfo?.old_path) {
      fileRange.basePath = fileInfo.old_path;
    }
    return fileRange;
  }

  private handleReviewedChange(e: Event) {
    const input = e.target as MdCheckbox;
    this.setReviewed(input.checked ?? false);
  }

  // Private but used in tests.
  setReviewed(
    reviewed: boolean,
    patchNum: RevisionPatchSetNum | undefined = this.patchNum
  ) {
    if (this.patchNum === EDIT) return;
    if (!patchNum || !this.path || !this.changeNum) return;
    // if file is already reviewed then do not make a saveReview request
    if (this.reviewedFiles.has(this.path) && reviewed) return;
    // optimistic update
    this.reviewed = reviewed;
    this.getChangeModel().setReviewedFilesStatus(
      this.changeNum,
      patchNum,
      this.path,
      reviewed
    );
  }

  // Private but used in tests.
  handleToggleFileReviewed() {
    this.setReviewed(!this.reviewed);
  }

  private handlePrevLine() {
    assertIsDefined(this.diffHost, 'diffHost');
    this.cursor?.moveUp();
  }

  private onOpenFixPreview(e: OpenFixPreviewEvent) {
    assertIsDefined(this.applyFixDialog, 'applyFixDialog');
    this.applyFixDialog.open(e);
  }

  private onIsBlameLoadedChanged(e: ValueChangedEvent<boolean>) {
    this.isBlameLoaded = e.detail.value;
  }

  private onDiffChanged(e: ValueChangedEvent<DiffInfo>) {
    this.diff = e.detail.value;
  }

  private onEditWeblinksChanged(
    e: ValueChangedEvent<WebLinkInfo[] | undefined>
  ) {
    this.editWeblinks = e.detail.value;
  }

  private onFilesWeblinksChanged(
    e: ValueChangedEvent<FilesWebLinks | undefined>
  ) {
    this.filesWeblinks = e.detail.value;
  }

  private handleNextLine() {
    assertIsDefined(this.diffHost, 'diffHost');
    this.cursor?.moveDown();
  }

  // Private but used in tests.
  moveToFileWithComment(direction: -1 | 1) {
    const path = this.findFileWithComment(direction);
    if (!path) {
      this.getChangeModel().navigateToChange();
    } else {
      this.getChangeModel().navigateToDiff({path});
    }
  }

  private handleNewComment() {
    this.classList.remove('hideComments');
    this.classList.remove('hideCheckCodePointers');
    this.cursor?.createCommentInPlace();
  }

  private handlePrevFile() {
    if (!this.path) return;
    if (!this.files?.sortedPaths) return;
    this.navToFile(this.files.sortedPaths, -1);
  }

  private handleNextFile() {
    if (!this.path) return;
    if (!this.files?.sortedPaths) return;
    this.navToFile(this.files.sortedPaths, 1);
  }

  private handleNextChunk() {
    const result = this.cursor?.moveToNextChunk();
    if (result === CursorMoveResult.CLIPPED && this.cursor?.isAtEnd()) {
      this.showToastAndNavigateFile('next', 'n');
    }
  }

  private handleNextCommentThread() {
    const result = this.cursor?.moveToNextCommentThread();
    if (result === CursorMoveResult.CLIPPED) {
      this.navigateToNextFileWithCommentThread();
    }
  }

  private lastDisplayedNavigateToFileToast: Map<string, number> = new Map();

  private showToastAndNavigateFile(direction: string, shortcut: string) {
    /*
     * If user presses p/n on the first/last diff chunk, show a toast informing
     * user that pressing it again will navigate them to previous/next
     * unreviewedfile if click happens within the time limit
     */
    if (
      this.lastDisplayedNavigateToFileToast.get(direction) &&
      Date.now() - this.lastDisplayedNavigateToFileToast.get(direction)! <=
        NAVIGATE_TO_NEXT_FILE_TIMEOUT_MS
    ) {
      // reset for next file
      this.lastDisplayedNavigateToFileToast.delete(direction);
      this.navigateToUnreviewedFile(direction);
    } else {
      this.lastDisplayedNavigateToFileToast.set(direction, Date.now());
      if (this.hasShownNavigateToFileToast.get(direction)) {
        // Popup for "direction" has already been shown once
        return;
      }
      fireAlert(
        this,
        `Press ${shortcut} again to navigate to ${direction} unreviewed file`
      );
      this.hasShownNavigateToFileToast.set(direction, true);
    }
  }

  private navigateToUnreviewedFile(direction: string) {
    if (!this.path) return;
    if (!this.files?.sortedPaths) return;
    if (!this.reviewedFiles) return;
    // Ensure that the currently viewed file always appears in unreviewedFiles
    // so we resolve the right "next" file.
    const unreviewedFiles = this.files.sortedPaths.filter(
      file => file === this.path || !this.reviewedFiles.has(file)
    );

    this.navToFile(unreviewedFiles, direction === 'next' ? 1 : -1);
  }

  private handlePrevChunk() {
    this.cursor?.moveToPreviousChunk();
    if (this.cursor?.isAtStart()) {
      this.showToastAndNavigateFile('previous', 'p');
    }
  }

  private handlePrevCommentThread() {
    this.cursor?.moveToPreviousCommentThread();
  }

  // Similar to gr-change-view.handleOpenReplyDialog
  private handleOpenReplyDialog() {
    if (!this.loggedIn) {
      fire(this, 'show-auth-required', {});
      return;
    }
    this.getChangeModel().navigateToChange(true);
  }

  private handleToggleLeftPane() {
    assertIsDefined(this.diffHost, 'diffHost');
    this.diffHost.toggleLeftDiff();
  }

  private handleOpenDownloadDialog() {
    assertIsDefined(this.downloadModal, 'downloadModal');
    this.downloadModal.showModal();
    whenVisible(this.downloadModal, () => {
      assertIsDefined(this.downloadModal, 'downloadModal');
      assertIsDefined(this.downloadDialog, 'downloadDialog');
      this.downloadDialog.focus();
    });
  }

  private handleDownloadDialogClose() {
    assertIsDefined(this.downloadModal, 'downloadModal');
    this.downloadModal.close();
  }

  private handleCommaKey() {
    if (!this.loggedIn) return;
    assertIsDefined(this.diffPreferencesDialog, 'diffPreferencesDialog');
    this.diffPreferencesDialog.open();
  }

  // Private but used in tests.
  handleToggleDiffMode() {
    if (!this.userPrefs) return;
    if (this.userPrefs.diff_view === DiffViewMode.SIDE_BY_SIDE) {
      this.getUserModel().updatePreferences({diff_view: DiffViewMode.UNIFIED});
    } else {
      this.getUserModel().updatePreferences({
        diff_view: DiffViewMode.SIDE_BY_SIDE,
      });
    }
  }

  // Private but used in tests.
  navToFile(
    fileList: string[],
    direction: -1 | 1,
    navigateToFirstComment?: boolean
  ) {
    const newPath = this.getNavLinkPath(fileList, direction);
    if (!newPath) return;
    if (!this.patchRange) return;

    if (newPath.up) {
      this.getChangeModel().navigateToChange();
      return;
    }

    if (!newPath.path) return;
    let lineNum;
    if (navigateToFirstComment)
      lineNum = this.changeComments?.getCommentsForPath(
        newPath.path,
        this.patchRange
      )?.[0].line;
    this.getChangeModel().navigateToDiff({path: newPath.path, lineNum});
  }

  /**
   * @param direction Either 1 (next file) or -1 (prev file).
   * @return The next URL when proceeding in the specified
   * direction.
   */
  private computeNavLinkURL(direction?: -1 | 1) {
    if (!this.change) return;
    if (!this.path) return;
    if (!this.files?.sortedPaths) return;
    if (!direction) return;

    const newPath = this.getNavLinkPath(this.files.sortedPaths, direction);
    if (!newPath) return;
    if (newPath.up) return this.getChangeModel().changeUrl();
    if (!newPath.path) return;
    if (!this.patchNum) return;
    return this.getViewModel().diffUrl({
      diffView: {path: newPath.path},
      patchNum: this.patchNum,
    });
  }

  private goToEditFile() {
    assertIsDefined(this.path, 'path');

    const lineNumber = this.cursor?.getTargetLineNumber();
    const lineNum = typeof lineNumber === 'number' ? lineNumber : undefined;
    this.getChangeModel().navigateToEdit({path: this.path, lineNum});
  }

  /**
   * Gives an object representing the target of navigating either left or
   * right through the change. The resulting object will have one of the
   * following forms:
   * * {path: "<target file path>"} - When another file path should be the
   * result of the navigation.
   * * {up: true} - When the result of navigating should go back to the
   * change view.
   * * null - When no navigation is possible for the given direction.
   *
   * @param path The path of the current file being shown.
   * @param fileList The list of files in this change and
   * patch range.
   * @param direction Either 1 (next file) or -1 (prev file).
   */
  private getNavLinkPath(fileList: string[], direction: -1 | 1) {
    if (!this.path || !fileList || fileList.length === 0) {
      return null;
    }
    let idx = fileList.indexOf(this.path);
    if (idx === -1) {
      const file = direction > 0 ? fileList[0] : fileList[fileList.length - 1];
      return {path: file};
    }

    idx += direction;
    // Redirect to the change view if noUp isn't truthy and idx falls
    // outside the bounds of [0, fileList.length).
    if (idx < 0 || idx > fileList.length - 1) {
      return {up: true};
    }

    return {path: fileList[idx]};
  }

  private updateUrlToDiffUrl(lineNum?: number, leftSide?: boolean) {
    if (!this.path || !this.patchNum) return;
    const url = this.getViewModel().diffUrl({
      diffView: {
        path: this.path,
        lineNum,
        leftSide,
      },
      patchNum: this.patchNum,
    });
    history.replaceState(null, '', url);
  }

  async reloadDiff() {
    if (!this.diffHost) return;
    await this.diffHost.reload(true);
    this.reporting.diffViewDisplayed();
    if (this.isBlameLoaded) this.loadBlame();
    this.isShowingEntireFile = this.diffHost?.isShowFullContext() ?? false;
  }

  /**
   * (Re-initialize) the diff view without actually reloading the diff. The
   * typical user journey is that the user comes back from the change page.
   */
  initializePositions() {
    // The diff view is kept in the background once created. If the user
    // scrolls in the change page, the scrolling is reflected in the diff view
    // as well, which means the diff is scrolled to a random position based
    // on how much the change view was scrolled.
    // Hence, reset the scroll position here.
    document.documentElement.scrollTop = 0;
    this.initCursor();
    this.reInitCursor();
    this.diffHost?.initLayers();
    this.classList.remove('hideComments');
    this.classList.remove('hideCheckCodePointers');
  }

  /**
   * If the params specify a diff address then configure the diff cursor.
   * Private but used in tests.
   */
  initCursor() {
    if (!this.focusLineNum) return;
    if (!this.cursor) return;
    this.cursor.side = this.leftSide ? Side.LEFT : Side.RIGHT;
    this.cursor.initialLineNumber = this.focusLineNum;
  }

  // Private but used in tests.
  getLineOfInterest(): DisplayLine | undefined {
    // If there is a line number specified, pass it along to the diff so that
    // it will not get collapsed.
    if (!this.focusLineNum) return undefined;

    return {
      lineNum: this.focusLineNum,
      side: this.leftSide ? Side.LEFT : Side.RIGHT,
    };
  }

  // Private but used in tests
  formatFilesForDropdown(): DropdownItem[] {
    if (!this.files) return [];
    if (!this.patchRange) return [];
    if (!this.changeComments) return [];

    const dropdownContent: DropdownItem[] = [];
    for (const path of this.files.sortedPaths) {
      const file = this.files.changeFilesByPath[path];
      dropdownContent.push({
        text: computeDisplayPath(path),
        mobileText: computeTruncatedPath(path),
        value: path,
        bottomText: this.changeComments.computeCommentsString(
          this.patchRange,
          path,
          file,
          /* includeUnmodified= */ true
        ),
        file,
      });
    }
    return dropdownContent;
  }

  // Private but used in tests.
  handleFileChange(e: ValueChangedEvent<string>) {
    const path: string = e.detail.value;
    if (path === this.path) return;
    this.getChangeModel().navigateToDiff({path});
  }

  // Private but used in tests.
  handlePatchChange(e: PatchRangeChangeEvent) {
    if (!this.path) return;
    if (!this.patchNum) return;

    const {basePatchNum, patchNum} = e.detail;
    if (basePatchNum === this.basePatchNum && patchNum === this.patchNum) {
      return;
    }
    this.getChangeModel().navigateToDiff(
      {path: this.path},
      patchNum,
      basePatchNum
    );
  }

  // Private but used in tests.
  handlePrefsTap(e: Event) {
    e.preventDefault();
    assertIsDefined(this.diffPreferencesDialog, 'diffPreferencesDialog');
    this.diffPreferencesDialog.open();
  }

  // Private but used in tests.
  onCommentAnchorTap(e: CustomEvent<CommentAnchorTapEventDetail>) {
    const lineNumber = e.detail.number;
    if (!Number.isInteger(lineNumber)) return;
    this.updateUrlToDiffUrl(
      lineNumber as number,
      e.detail.side === CommentSide.PARENT
    );
  }

  // Private but used in tests.
  onLineSelected(e: CustomEvent<LineSelectedEventDetail>) {
    const lineNumber = e.detail.number;
    if (!Number.isInteger(lineNumber)) return;
    this.selectedDiffLine = {
      lineNum: lineNumber as number,
      side: e.detail.side,
    };
    this.updateUrlToDiffUrl(lineNumber as number, e.detail.side === Side.LEFT);
  }

  private computeLineKey(path: string, lineNum: number) {
    return `${path}:${lineNum}`;
  }

  private getLineNumberFromKey(path: string, lineKey: string) {
    const prefix = `${path}:`;
    if (!lineKey.startsWith(prefix)) return;
    const lineNum = Number(lineKey.slice(prefix.length));
    if (!Number.isInteger(lineNum) || lineNum < 1) return;
    return lineNum;
  }

  private onReviewHistoryToggle() {
    this.reviewHistoryMinimized = !this.reviewHistoryMinimized;
  }

  private syncReviewerHistory() {
    this.reviewerHistory = this.buildReviewerHistoryEntries(
      this.change?.reviewers?.[ReviewerState.REVIEWER] ?? []
    );
  }

  private buildReviewerHistoryEntries(reviewers: AccountInfo[]) {
    const seenReviewerIds = new Set<string>();
    const entries: ReviewerHistoryEntry[] = [];
    for (const reviewer of reviewers) {
      const reviewerId = this.getReviewerId(reviewer);
      if (!reviewerId || seenReviewerIds.has(reviewerId)) continue;
      seenReviewerIds.add(reviewerId);
      entries.push({
        id: reviewerId,
        name: this.getReviewerName(reviewer),
        color:
          REVIEWER_HISTORY_COLORS[
            entries.length % REVIEWER_HISTORY_COLORS.length
          ],
      });
    }
    return entries;
  }

  private getReviewerId(account?: AccountInfo) {
    if (account?._account_id !== undefined) return String(account._account_id);
    return account?.username ?? account?.email ?? account?.name;
  }

  private getReviewerName(account: AccountInfo) {
    return (
      account.display_name ??
      account.name ??
      account.username ??
      account.email ??
      (account._account_id !== undefined
        ? `Account ${account._account_id}`
        : 'Unknown reviewer')
    );
  }

  private isCurrentReviewer(reviewerId: string) {
    return reviewerId === this.getReviewerId(this.currentReviewer);
  }

  private getLineNumbersFromHistoryEntry(entry: LineReviewHistoryInfo) {
    const startLine =
      entry.range?.start_line ?? entry.range?.startLine ?? entry.line;
    const endLine = entry.range?.end_line ?? entry.range?.endLine ?? entry.line;
    if (!Number.isInteger(startLine) || !Number.isInteger(endLine)) return [];
    const lineNumbers: number[] = [];
    for (let lineNum = startLine; lineNum <= endLine; lineNum++) {
      lineNumbers.push(lineNum);
    }
    return lineNumbers;
  }

  private buildReviewerReviewedLineKeys(
    historyEntries: LineReviewHistoryInfo[],
    path: string,
    patchNum: RevisionPatchSetNum
  ) {
    const reviewerLineKeys = new Map<string, Set<string>>();
    const currentPatchNum = Number(patchNum);
    if (!Number.isInteger(currentPatchNum)) return reviewerLineKeys;
    for (const entry of historyEntries) {
      if (entry.file !== path) continue;
      const patchSetId = entry.patch_set_id ?? entry.patchSetId;
      if (
        patchSetId !== undefined &&
        patchSetId !== currentPatchNum
      ) {
        continue;
      }
      if ((entry.side ?? CommentSide.REVISION) !== CommentSide.REVISION) continue;

      const reviewerAccountId = entry.account_id ?? entry.accountId;
      if (reviewerAccountId === undefined) continue;
      const reviewerId = String(reviewerAccountId);
      const currentReviewerLineKeys = new Set(
        reviewerLineKeys.get(reviewerId) ?? []
      );
      for (const lineNum of this.getLineNumbersFromHistoryEntry(entry)) {
        const lineKey = this.computeLineKey(path, lineNum);
        if (entry.action === 'UNMARKED') {
          currentReviewerLineKeys.delete(lineKey);
        } else {
          currentReviewerLineKeys.add(lineKey);
        }
      }
      if (currentReviewerLineKeys.size > 0) {
        reviewerLineKeys.set(reviewerId, currentReviewerLineKeys);
      } else {
        reviewerLineKeys.delete(reviewerId);
      }
    }
    return reviewerLineKeys;
  }

  private syncCurrentReviewerReviewedLines(path = this.path) {
    const currentReviewerId = this.getReviewerId(this.currentReviewer);
    const reviewerLineKeys = currentReviewerId
      ? this.reviewerReviewedLineKeys.get(currentReviewerId)
      : undefined;
    this.currentReviewerReviewedLines = [...(reviewerLineKeys ?? [])].sort();

    this.lineReadStatusLayer.clearMarks();
    if (!path) return;
    for (const lineKey of this.currentReviewerReviewedLines) {
      const lineNum = this.getLineNumberFromKey(path, lineKey);
      if (!lineNum) continue;
      this.lineReadStatusLayer.setMarked(path, lineNum, true);
    }
  }

  private updateCurrentReviewerLine(path: string, lineNum: number, marked: boolean) {
    const lineKey = this.computeLineKey(path, lineNum);
    const reviewerId = this.getReviewerId(this.currentReviewer);
    const currentReviewerLines = new Set(this.currentReviewerReviewedLines);
    if (marked) currentReviewerLines.add(lineKey);
    else currentReviewerLines.delete(lineKey);
    this.currentReviewerReviewedLines = [...currentReviewerLines].sort();

    if (!reviewerId) return;
    const reviewerReviewedLineKeys = new Map(this.reviewerReviewedLineKeys);
    if (currentReviewerLines.size > 0) {
      reviewerReviewedLineKeys.set(reviewerId, currentReviewerLines);
    } else {
      reviewerReviewedLineKeys.delete(reviewerId);
    }
    this.reviewerReviewedLineKeys = reviewerReviewedLineKeys;
  }

  private hasReviewerReviewedLine(
    reviewerId: string,
    path: string,
    lineNum: number
  ) {
    return this.reviewerReviewedLineKeys
      .get(reviewerId)
      ?.has(this.computeLineKey(path, lineNum)) ?? false;
  }

  private getReviewerDots(path: string, lineNum: number): ReviewerDot[] {
    return this.reviewerHistory
      .filter(reviewer => this.hasReviewerReviewedLine(reviewer.id, path, lineNum))
      .map(reviewer => ({
        color: reviewer.color,
        label: `${reviewer.name} reviewed line ${lineNum}`,
      }));
  }

  private getReviewerReviewedCount(reviewerId: string) {
    return this.reviewerReviewedLineKeys.get(reviewerId)?.size ?? 0;
  }

  private getReviewerSummaryLabel(reviewerId: string) {
    return `${this.getReviewerReviewedPercent(reviewerId)}% reviewed`;
  }

  private getReviewerReviewedPercent(reviewerId: string) {
    const totalLines = this.getTotalReviewableLines();
    if (totalLines < 1) return 0;
    return Math.round((this.getReviewerReviewedCount(reviewerId) / totalLines) * 100);
  }

  private getTotalReviewableLines() {
    const metaLines = this.diff?.meta_b?.lines ?? this.diff?.meta_a?.lines;
    if (typeof metaLines === 'number' && metaLines > 0) return metaLines;
    if (!this.diff?.content) return 0;
    return this.diff.content.reduce((total, chunk) => {
      if (chunk.ab) return total + chunk.ab.length;
      if (chunk.b) return total + chunk.b.length;
      if (chunk.skip) {
        return total + (typeof chunk.skip === 'number' ? chunk.skip : chunk.skip.right);
      }
      return total;
    }, 0);
  }

  private async loadLineMarkers() {
    if (!this.changeNum || !this.patchNum || !this.path || !this.loggedIn) {
      this.reviewerReviewedLineKeys = new Map();
      this.currentReviewerReviewedLines = [];
      this.lineReadStatusLayer.clearMarks();
      return;
    }
    const requestId = ++this.lineReviewHistoryRequestId;
    const path = this.path;
    const patchNum = this.patchNum;
    const historyEntries =
      await getAppContext().restApiService.getReviewedLineHistory(this.changeNum);
    if (requestId !== this.lineReviewHistoryRequestId) return;

    this.reviewerReviewedLineKeys = this.buildReviewerReviewedLineKeys(
      historyEntries ?? [],
      path,
      patchNum
    );
    this.syncCurrentReviewerReviewedLines(path);
  }

  private isTooLargeForDownload() {
    return (this.file?.size ?? 0) > FILE_DOWNLOAD_LIMIT_BYTES;
  }

  // Private but used in tests.
  computeDownloadDropdownLinks(): DropdownLink[] {
    if (!this.change?.project) return [];
    if (!this.changeNum) return [];
    if (!this.patchRange) return [];
    if (!this.path) return [];

    const links: DropdownLink[] = [
      {
        url: this.computeDownloadPatchLink(
          this.change.project,
          this.changeNum,
          this.patchRange,
          this.path
        ),
        name: 'Patch',
      },
    ];

    if (this.isTooLargeForDownload()) {
      links.push({
        id: 'left-content',
        name: 'Left Content (Too Large)',
      });
      links.push({
        id: 'right-content',
        name: 'Right Content (Too Large)',
      });
    } else {
      if (this.diff && this.diff.meta_a) {
        let leftPath = this.path;
        if (this.diff.change_type === 'RENAMED') {
          leftPath = this.diff.meta_a.name;
        }
        links.push({
          url: this.computeDownloadFileLink(
            this.change.project,
            this.changeNum,
            this.patchRange,
            leftPath,
            true
          ),
          name: 'Left Content',
        });
      }

      if (this.diff && this.diff.meta_b) {
        links.push({
          url: this.computeDownloadFileLink(
            this.change.project,
            this.changeNum,
            this.patchRange,
            this.path,
            false
          ),
          name: 'Right Content',
        });
      }
    }

    return links;
  }

  // TODO: Move to view-model or router.
  // Private but used in tests.
  computeDownloadFileLink(
    repo: RepoName,
    changeNum: NumericChangeId,
    patchRange: PatchRange,
    path: string,
    isBase?: boolean
  ) {
    let patchNum = patchRange.patchNum;
    let parent: number | undefined = undefined;

    if (isBase) {
      if (isMergeParent(patchRange.basePatchNum)) {
        parent = getParentIndex(patchRange.basePatchNum);
      } else if (patchRange.basePatchNum === PARENT) {
        parent = 1;
      } else {
        patchNum = patchRange.basePatchNum as PatchSetNumber;
      }
    }
    let url =
      changeBaseURL(repo, changeNum, patchNum) +
      `/files/${encodeURIComponent(path)}/download`;
    if (parent) url += `?parent=${parent}`;

    return url;
  }

  // TODO: Move to view-model or router.
  // Private but used in tests.
  computeDownloadPatchLink(
    repo: RepoName,
    changeNum: NumericChangeId,
    patchRange: PatchRange,
    path: string
  ) {
    let url = changeBaseURL(repo, changeNum, patchRange.patchNum);
    url += '/patch?zip&path=' + encodeURIComponent(path);
    return url;
  }

  // Private but used in tests.
  findFileWithComment(direction: -1 | 1): string | undefined {
    const fileList = this.files?.sortedPaths;
    const commentMap: CommentMap =
      this.changeComments?.getPaths(this.patchRange) ?? {};
    if (!fileList || fileList.length === 0) return undefined;
    if (!this.path) return undefined;

    const pathIndex = fileList.indexOf(this.path);
    const stopIndex = direction === 1 ? fileList.length : -1;
    for (let i = pathIndex + direction; i !== stopIndex; i += direction) {
      if (commentMap[fileList[i]]) return fileList[i];
    }
    return undefined;
  }

  // Private but used in tests.
  loadBlame() {
    this.isBlameLoading = true;
    fireAlert(this, LOADING_BLAME);
    assertIsDefined(this.diffHost, 'diffHost');
    this.diffHost
      .loadBlame()
      .then(() => {
        this.isBlameLoading = false;
        fireAlert(this, LOADED_BLAME);
      })
      .catch(() => {
        this.isBlameLoading = false;
      });
  }

  /**
   * Load and display blame information if it has not already been loaded.
   * Otherwise hide it.
   */
  private toggleBlame() {
    if (!this.allowBlame) return;
    assertIsDefined(this.diffHost, 'diffHost');
    if (this.isBlameLoaded) {
      this.diffHost.clearBlame();
    } else {
      this.loadBlame();
    }
  }

  // Private but used in tests.
  handleToggleHideAllCommentsAndCodePointers() {
    const hideComments = this.classList.toggle('hideComments');
    /* Hide the check results along with the comments
       when the user presses the keyboard shortcut 'h'. */
    if (hideComments) {
      this.classList.add('hideCheckCodePointers');
    } else {
      this.classList.remove('hideCheckCodePointers');
    }
  }

  // Private but used in tests.
  handleToggleHideCheckCodePointers() {
    this.classList.toggle('hideCheckCodePointers');
  }

  private handleOpenFileList() {
    assertIsDefined(this.dropdown, 'dropdown');
    this.dropdown.open();
  }

  // Private but used in tests.
  handleDiffAgainstBase() {
    if (!this.isActiveChildView) return;
    assertIsDefined(this.path, 'path');
    assertIsDefined(this.patchNum, 'patchNum');

    if (this.basePatchNum === PARENT) {
      fireAlert(this, 'Base is already selected.');
      return;
    }
    this.getChangeModel().navigateToDiff(
      {path: this.path},
      this.patchNum,
      PARENT
    );
  }

  // Private but used in tests.
  handleDiffBaseAgainstLeft() {
    if (!this.isActiveChildView) return;
    assertIsDefined(this.path, 'path');
    assertIsDefined(this.patchNum, 'patchNum');

    if (this.basePatchNum === PARENT) {
      fireAlert(this, 'Left is already base.');
      return;
    }
    this.getChangeModel().navigateToDiff(
      {path: this.path},
      this.basePatchNum as RevisionPatchSetNum,
      PARENT
    );
  }

  // Private but used in tests.
  handleDiffAgainstLatest() {
    if (!this.isActiveChildView) return;
    assertIsDefined(this.path, 'path');
    assertIsDefined(this.patchNum, 'patchNum');

    if (this.patchNum === this.latestPatchNum) {
      fireAlert(this, 'Latest is already selected.');
      return;
    }

    this.getChangeModel().navigateToDiff(
      {path: this.path},
      this.latestPatchNum,
      this.basePatchNum
    );
  }

  // Private but used in tests.
  handleDiffRightAgainstLatest() {
    if (!this.isActiveChildView) return;
    assertIsDefined(this.path, 'path');
    assertIsDefined(this.patchNum, 'patchNum');

    if (this.patchNum === this.latestPatchNum) {
      fireAlert(this, 'Right is already latest.');
      return;
    }

    this.getChangeModel().navigateToDiff(
      {path: this.path},
      this.latestPatchNum,
      this.patchNum as BasePatchSetNum
    );
  }

  // Private but used in tests.
  handleDiffBaseAgainstLatest() {
    if (!this.isActiveChildView) return;
    assertIsDefined(this.path, 'path');
    assertIsDefined(this.patchNum, 'patchNum');

    if (this.patchNum === this.latestPatchNum && this.basePatchNum === PARENT) {
      fireAlert(this, 'Already diffing base against latest.');
      return;
    }

    this.getChangeModel().navigateToDiff(
      {path: this.path},
      this.latestPatchNum,
      PARENT
    );
  }

  // Private but used in tests.
  computeFileNum(files: DropdownItem[]) {
    if (!this.path || !files) return undefined;

    return files.findIndex(({value}) => value === this.path) + 1;
  }

  // Private but used in tests.
  computeFileNumClass(fileNum?: number, files?: DropdownItem[]) {
    if (files && fileNum && fileNum > 0) {
      return 'show';
    }
    return '';
  }

  private handleToggleAllDiffContext() {
    assertIsDefined(this.diffHost, 'diffHost');
    this.isShowingEntireFile = !this.isShowingEntireFile;
    this.diffHost.toggleAllContext();
  }

  private handleNextUnreviewedFile() {
    this.setReviewed(true);
    this.navigateToUnreviewedFile('next');
  }

  private navigateToNextFileWithCommentThread() {
    if (!this.path) return;
    if (!this.files?.sortedPaths) return;
    const range = this.patchRange;
    if (!range) return;
    if (!this.change) return;
    const hasComment = (path: string) =>
      this.changeComments?.getCommentsForPath(path, range)?.length ?? 0 > 0;
    const filesWithComments = this.files.sortedPaths.filter(
      file => file === this.path || hasComment(file)
    );
    this.navToFile(filesWithComments, 1, true);
  }

  private computeCanEdit() {
    return (
      !!this.change &&
      !!this.loggedIn &&
      changeIsOpen(this.change) &&
      !this.computeShowEditLinks()
    );
  }

  private computeShowEditLinks() {
    return !!this.editWeblinks && this.editWeblinks.length > 0;
  }

  createTitle(shortcutName: Shortcut, section: ShortcutSection) {
    return this.getShortcutsService().createTitle(shortcutName, section);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'gr-diff-view': GrDiffView;
  }
}
