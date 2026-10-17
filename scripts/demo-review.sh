#!/usr/bin/env bash
# Day 32 demo: plays a pull request with seeded problems through the AI review workflow.
#
#   scripts/demo-review.sh prepare   create branch demo-review from origin/main with the seeded problems (local commit only)
#   scripts/demo-review.sh open      push it and open the pull request (not a draft: the workflow skips drafts)
#   scripts/demo-review.sh fix       commit the fixes and push: the review comment is updated, not duplicated
#   scripts/demo-review.sh cleanup   close the PR, delete the branch (local and remote), return to the previous branch
#
# Needs a clean working tree (tracked files), `gh` logged in, and the AI review workflow + GEMINI_API_KEY secret on the base branch.
# Base branch: DEMO_BASE (default main). The seeded files live in scripts/demo-review/{bad,good} (as .txt, so they are not indexed as code).
set -euo pipefail

# Switching branches may remove this script (and its templates) from the working tree: run from a temporary copy.
if [ -z "${DEMO_REEXEC:-}" ]; then
  src="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  tmp="$(mktemp -d)"
  cp -R "$src/demo-review" "$tmp/templates"
  cp "${BASH_SOURCE[0]}" "$tmp/demo-review.sh"
  DEMO_REEXEC=1 DEMO_TEMPLATES="$tmp/templates" exec bash "$tmp/demo-review.sh" "$@"
fi

templates="$DEMO_TEMPLATES"
branch="${DEMO_BRANCH:-demo-review}"
base="${DEMO_BASE:-main}"
state="$(git rev-parse --git-dir)/demo-review-previous-branch"

place() { # place <bad|good>
  local agent_dir=agent/src/main/kotlin/com/example/geminichat/agent/workout app_dir=app/src/main/java/com/example/geminichat/ui/quick
  mkdir -p "$agent_dir" "$app_dir"
  cp "$templates/$1/WorkoutExport.kt.txt" "$agent_dir/WorkoutExport.kt"
  cp "$templates/$1/QuickAskViewModel.kt.txt" "$app_dir/QuickAskViewModel.kt"
}

require_clean() {
  git diff --quiet && git diff --cached --quiet || { echo "Commit or stash your tracked changes first."; exit 1; }
}

case "${1:-}" in
  prepare)
    require_clean
    git rev-parse --verify -q "refs/heads/$branch" >/dev/null && { echo "Branch $branch already exists: run cleanup first."; exit 1; }
    git branch --show-current > "$state"
    git fetch -q origin "$base"
    git checkout -q -b "$branch" "origin/$base"
    [ -f .github/workflows/ai-review.yml ] || echo "Warning: $base has no .github/workflows/ai-review.yml yet: merge the Day 32 pull request first."
    place bad
    git add -A agent app
    git commit -q -m "Add workout export and quick ask screen" -m "Demo commit with seeded problems for the AI review."
    echo "Branch $branch is ready (not pushed). Next: scripts/demo-review.sh open"
    ;;
  open)
    git rev-parse --verify -q "refs/heads/$branch" >/dev/null || { echo "Run prepare first."; exit 1; }
    git push -q -u origin "$branch"
    gh pr create --base "$base" --head "$branch" --title "Demo: workout export and quick ask" \
      --body "Demo pull request for the AI review (seeded problems). Not meant to be merged."
    ;;
  fix)
    [ "$(git branch --show-current)" = "$branch" ] || { echo "Check out $branch first (run prepare)."; exit 1; }
    place good
    git add -A agent app
    git commit -q -m "Fix review findings" -m "Safe export and average, no java.io in :agent, ViewModel goes through Agent."
    git push -q -u origin "$branch"
    echo "Fixes pushed: the AI review comment will be updated."
    ;;
  cleanup)
    gh pr close "$branch" --delete-branch 2>/dev/null || true
    git push -q origin --delete "$branch" 2>/dev/null || true
    prev="$(cat "$state" 2>/dev/null || true)"
    [ -z "$prev" ] || git checkout -q "$prev"
    git branch -D "$branch" -q 2>/dev/null || true
    rm -f "$state"
    echo "Cleaned up. Back on ${prev:-the previous branch}."
    ;;
  *)
    sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
