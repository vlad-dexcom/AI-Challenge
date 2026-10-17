#!/usr/bin/env bash
# Day 32: creates or updates the single AI-review comment of a pull request (found by its hidden marker).
#
#   GH_TOKEN=... REPO=owner/name PR_NUMBER=12 scripts/post-review-comment.sh review.md
#
# Only comments written by a bot are updated, so a user who copies the marker cannot hijack the lookup.
set -euo pipefail

file="${1:?usage: post-review-comment.sh <review.md>}"
: "${REPO:?REPO is required}" "${PR_NUMBER:?PR_NUMBER is required}"
marker='<!-- ai-review -->'

[ -s "$file" ] || { echo "No review to post ($file is missing or empty)."; exit 0; }

ids=$(gh api --paginate "repos/$REPO/issues/$PR_NUMBER/comments" \
  --jq ".[] | select(.user.type == \"Bot\" and (.body | contains(\"$marker\"))) | .id")
id="${ids%%$'\n'*}"

if [ -n "$id" ]; then
  gh api --method PATCH "repos/$REPO/issues/comments/$id" -F "body=@$file" >/dev/null
  echo "Updated review comment $id."
else
  gh api --method POST "repos/$REPO/issues/$PR_NUMBER/comments" -F "body=@$file" >/dev/null
  echo "Posted a new review comment."
fi
