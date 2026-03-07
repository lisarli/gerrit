# Verifying Line/Region Review Flags

This guide describes how to verify that the line-level and region-level reviewed flags implementation is correct and matches your expectations.

## 1. Run existing tests

Ensure you don’t break current behavior and that new code is exercised:

```bash
# Run REST API binding tests (includes GET/PUT/DELETE .../reviewed_lines)
bazel test //javatests/com/google/gerrit/acceptance/rest/binding:rest_bindings

# Run file-level reviewed-flag tests (regex: method names containing Reviewed)
bazel test //javatests/com/google/gerrit/acceptance/api/revision:RevisionIT --test_filter=".*Reviewed.*"

# Run account deletion test (clears both file and line reviewed flags)
bazel test //javatests/com/google/gerrit/acceptance/api/accounts:api_account --test_filter=".*deleteAccount.*Reviewed.*"

# Run line-level reviewed flags acceptance test (regex: method names containing LineReviewed)
bazel test //javatests/com/google/gerrit/acceptance/api/revision:RevisionIT --test_filter=".*LineReviewed.*"
```

## 2. Manual verification via REST API

Use `curl` (or any HTTP client) against a running Gerrit instance. Replace `GERRIT_URL`, `CHANGE_ID`, `REVISION`, `FILE_PATH`, and use an auth cookie or `-u user:http-password` as needed.

**How to test the PUT (mark line reviewed):**

1. **Start Gerrit**.

2. **Get a change that has at least one file.** If you have no projects/repos yet, do the following once:

   **a) Create a project** (pick one):

   - **REST:**  
     `curl -X PUT -u user:http_password -H "Content-Type: application/json" -d '{"create_empty_commit":true}' "http://localhost:8080/a/projects/demo"`
   - **SSH:**  
     `ssh -p 29418 user@localhost gerrit create-project --branch master --empty-commit demo`  
     (adjust port/host if your Gerrit uses different SSH settings.)

   **b) Clone the project and push a commit** (so the change has a real file):

   ```bash
   git clone http://localhost:8080/demo demo && cd demo
   # Optional: install commit-msg hook so commits get a Change-Id (see Gerrit docs)
   echo "hello" > readme.txt
   git add readme.txt && git commit -m "Add readme"
   git push origin HEAD:refs/for/master
   ```

   The push output will show the change number (e.g. `remote: (NEW) abc123 refs/changes/12/42/1` → change **42**). Use that number and the file name (e.g. `readme.txt`) in the curl below.

   If you already have a project and change, use any existing change number and a file path from that change.

3. **From your change, note:**
   - **CHANGE_ID**: change number or `I...` id (e.g. `42` or `Iabc123...`).
   - **REVISION**: use `current` for the latest patch set, or the commit SHA (e.g. `1` or `abc123...`).
   - **FILE_PATH**: a file in that patch (e.g. `readme.txt` or `foo/bar.txt`). For URL safety, encode slashes as `%2F` (e.g. `foo%2Fbar.txt`).

4. **Authenticate.** You must be logged in. Either:
   - **HTTP password:** `curl -u USER:HTTP_PASSWORD ...` (get the password from Gerrit: Profile → HTTP Password).
   - **Cookie:** log in in the browser, then use `curl ... -b 'cookie_name=cookie_value'` (inspect your browser cookies for the Gerrit host).

5. **Run the PUT** with real values (example: line 10, file `foo/bar.txt`, change 42, current revision):

   ```bash
   curl -X PUT \
     -u youruser:your_http_password \
     -H "Content-Type: application/json" \
     -d '{"line": 10}' \
     "http://localhost:8080/a/changes/42/revisions/current/files/foo%2Fbar.txt/reviewed_lines"
   ```

   Expect **200 OK** or **201 Created**.

6. **Verify with GET** (same URL without `-X PUT` and without `-d`):

   ```bash
   curl -u youruser:your_http_password \
     "http://localhost:8080/a/changes/42/revisions/current/files/foo%2Fbar.txt/reviewed_lines"
   ```

   Expect **200 OK** and a JSON array containing the reviewed line, e.g. `[{"line":10,"side":"REVISION"}]`.

**Mark line 10 as reviewed (current revision side):**

```bash
curl -X PUT \
  -H "Content-Type: application/json" \
  -d '{"line": 10}' \
  "https://GERRIT_URL/a/changes/CHANGE_ID/revisions/REVISION/files/FILE_PATH/reviewed_lines"
```

**Mark a region (e.g. lines 10–15):**

```bash
curl -X PUT \
  -H "Content-Type: application/json" \
  -d '{
    "line": 10,
    "range": {
      "startLine": 10,
      "startCharacter": 0,
      "endLine": 15,
      "endCharacter": 0
    },
    "side": "REVISION"
  }' \
  "https://GERRIT_URL/a/changes/CHANGE_ID/revisions/REVISION/files/FILE_PATH/reviewed_lines"
```

**List reviewed lines for the file:**

```bash
curl "https://GERRIT_URL/a/changes/CHANGE_ID/revisions/REVISION/files/FILE_PATH/reviewed_lines"
```

**Clear a line/region (same body as when marking):**

```bash
curl -X DELETE \
  -H "Content-Type: application/json" \
  -d '{"line": 10}' \
  "https://GERRIT_URL/a/changes/CHANGE_ID/revisions/REVISION/files/FILE_PATH/reviewed_lines"
```

Expect: `PUT` returns 200/201, `GET` returns 200 with a JSON array of `LineReviewedInfo`, `DELETE` returns 204.

## 3. Database verification

If using the default H2 or another JDBC store, confirm the table and data:

- **Table:** `account_patch_line_reviews`
- **Columns:** `account_id`, `change_id`, `patch_set_id`, `file_name`, `line_number`, `side`, `start_line`, `start_char`, `end_line`, `end_char`
- **Primary key:** `(change_id, patch_set_id, account_id, file_name, line_number, side, start_line, start_char, end_line, end_char)`

After marking a line or region, query the table and confirm a row exists for the expected user, change, patch set, file, line/range, and side.

## 4. Checklist: is this what you want?

- **Attributes:** Line/region flags use the same concepts as comments: 1-based `line`, optional `range` (start/end line and character), and `side` (PARENT vs REVISION). If you need different semantics, the API and store can be adjusted.
- **Storage:** Flags are stored in a dedicated table and can use the same DB as file-level flags (`accountPatchReviewDb`) or a separate one (`accountPatchLineReviewDb.url`). Confirm config and DB choice.
- **REST:** Endpoints are under the file resource: `GET/PUT/DELETE .../files/{path}/reviewed_lines`. PUT/DELETE require a JSON body with at least `line` (and optionally `range`, `side`). If you prefer a different URL shape or body format, that can be changed.
- **Cleanup:** Line-level flags are cleared when: the change is deleted, the account is deleted, the patch set is deleted (consistency checker), or (when configured) changes are abandoned. Confirm this matches your expectations.
- **UI:** The current change only adds backend storage and REST API. Polygerrit (or another UI) would need to call these endpoints and show line/region reviewed state; that is separate work.

## 5. Optional: add an acceptance test

An acceptance test can create a change, mark a line as reviewed via REST with a body, then assert via the store or GET that the flag is present, and clear it and assert it’s gone. See `RevisionIT.setUnsetReviewedFlag` for the file-level pattern; the line-level test would use `restSession.put(uri, lineReviewedInput)` and `restSession.get(uri)` for `.../reviewed_lines`.
