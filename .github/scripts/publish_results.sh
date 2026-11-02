#!/usr/bin/env bash
# Pushes CI outputs to an orphan branch so they can be inspected without downloading artifacts.
set -u
BRANCH=$1; STATUS=$2; shift 2
DIR=$(mktemp -d)
for f in "$@"; do [ -e "$f" ] && cp -r "$f" "$DIR/"; done
# Include JUnit XML results (small) for the build job.
if [ "$BRANCH" = "ci-build" ]; then
  mkdir -p "$DIR/test-results"
  find . -path '*/build/test-results/*' -name '*.xml' -not -path "$DIR/*" -exec cp {} "$DIR/test-results/" \; 2>/dev/null || true
fi
cat > "$DIR/README.md" <<MD
CI output for commit ${GITHUB_SHA} — run ${GITHUB_RUN_NUMBER} — job status: ${STATUS}
MD
cd "$DIR"
git init -q -b "$BRANCH"
git config user.name "github-actions"
git config user.email "github-actions@users.noreply.github.com"
git add -A
git commit -qm "CI ${BRANCH} run ${GITHUB_RUN_NUMBER} (${STATUS}) for ${GITHUB_SHA}"
git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "$BRANCH:$BRANCH" && echo "Published $BRANCH"
