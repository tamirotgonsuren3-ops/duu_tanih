#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash git_exclude.sh [--dry-run] <file|folder|pattern> [more...]

Untracks files/folders that were committed or staged by mistake and adds
them to .gitignore so future git actions ignore them. The files stay on
your disk; the next commit (and push) removes them from the repository.

Examples:
  bash git_exclude.sh __pycache__ node_modules
  bash git_exclude.sh "*.pyc" --dry-run

Options:
  -n, --dry-run   Show what would be untracked without changing anything.
  -h, --help      Show this help.
EOF
}

DRY_RUN=0
ARGS=()

for arg in "$@"; do
  case "$arg" in
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; ARGS+=("$@"); break ;;
    -*) echo "Unknown option: $arg" >&2; usage; exit 1 ;;
    *) ARGS+=("$arg") ;;
  esac
done

if [[ "${#ARGS[@]}" -eq 0 ]]; then
  echo "Error: no file/folder/pattern given." >&2
  echo >&2
  usage >&2
  exit 1
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

echo "Repository: $repo_root"
echo "Patterns:   ${ARGS[*]}"
[[ "$DRY_RUN" -eq 1 ]] && echo "Mode:       DRY RUN (no changes will be made)"
echo

declare -a TARGETS=()

for pattern in "${ARGS[@]}"; do
  while IFS= read -r -d '' file; do
    [[ -n "$file" ]] && TARGETS+=("$file")
  done < <(git ls-files -z -- "$pattern")
done

if [[ "${#TARGETS[@]}" -eq 0 ]]; then
  echo "No tracked files match the given patterns. Nothing to untrack."
  echo "They may already be untracked; ensuring they are in .gitignore instead."
else
  mapfile -t TARGETS < <(printf '%s\n' "${TARGETS[@]}" | sort -u)

  echo "Tracked files matching patterns (${#TARGETS[@]}):"
  printf '  %s\n' "${TARGETS[@]}"
  echo

  if [[ "$DRY_RUN" -eq 0 ]]; then
    git rm -r --cached --quiet -- "${TARGETS[@]}"
    echo "Untracked ${#TARGETS[@]} file(s) from the git index (files kept on disk)."
  else
    echo "Would run: git rm -r --cached -- <listed files>"
  fi
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "Would update .gitignore with: ${ARGS[*]}"
  exit 0
fi

[[ -f .gitignore ]] || : > .gitignore
for pattern in "${ARGS[@]}"; do
  grep -qxF "$pattern" .gitignore || printf '%s\n' "$pattern" >> .gitignore
done
sort -u -o .gitignore .gitignore

echo "Updated .gitignore."
echo
echo "Finish by committing and pushing:"
echo "  git commit -m 'Stop tracking unnecessary files'"
echo "  git push"
