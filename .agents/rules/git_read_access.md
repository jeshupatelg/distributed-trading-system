# Git Read Access

The agent is granted pre-approved access to run all git read-only commands directly without requiring prior user permission or approval.

This includes all querying, inspecting, and listing operations such as:
- `git status`
- `git log` / `git shortlog` / `git reflog`
- `git diff` / `git diff-tree` / `git diff-index`
- `git show`
- `git grep`
- `git ls-files` / `git ls-tree`
- `git blame`
- `git check-ignore`
- `git branch` (inspecting/listing)
- `git tag` (inspecting/listing)
- `git remote` (e.g. `git remote -v`, `git remote show`)
- `git rev-parse`
- `git describe`
- `git cat-file`
- `git merge-base`
- `git config --get` / `git config --list` / `git config -l`
