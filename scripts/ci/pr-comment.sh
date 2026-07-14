#!/usr/bin/env bash
# Upsert a sticky PR comment. Usage: <body on stdin> | pr-comment.sh <slug>
#
# Each workflow posts its results under a stable slug; re-runs PATCH the same comment instead of
# stacking new ones, so the PR always shows the latest numbers without opening the Actions logs.
# Env: GH_TOKEN (github.token), PR_NUMBER, GITHUB_REPOSITORY.
set -euo pipefail

slug="$1"
marker="<!-- ci-comment:$slug -->"
body="$marker
$(cat)"

pr="${PR_NUMBER:?PR_NUMBER is required}"
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

existing=$(gh api "repos/$repo/issues/$pr/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$marker\")) | .id" | head -n1)

if [ -n "$existing" ]; then
  gh api -X PATCH "repos/$repo/issues/comments/$existing" -f body="$body" > /dev/null
  echo "pr-comment: updated $slug (comment $existing)"
else
  gh api -X POST "repos/$repo/issues/$pr/comments" -f body="$body" > /dev/null
  echo "pr-comment: created $slug"
fi
