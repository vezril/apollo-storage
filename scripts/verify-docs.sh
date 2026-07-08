#!/usr/bin/env bash
# Documentation verification (documentation spec): referenced files exist, the
# README's internal links resolve, and every CI/CD badge points at a real
# workflow file (no misnamed-workflow 404s).
set -euo pipefail

fail() { echo "FAIL: $1"; exit 1; }

echo "==> required files present"
for f in README.md LICENSE docker-compose.yml ddl/create_tables_postgres.sql; do
  [[ -f "$f" ]] || fail "missing $f"
done

echo "==> README internal links resolve"
# Extract markdown link targets that look like repo-relative paths and check them.
grep -oE '\]\(([^)#]+)\)' README.md | sed -E 's/\]\(([^)]+)\)/\1/' | while read -r target; do
  case "$target" in
    http*|"") continue ;;
    *) [[ -e "$target" ]] || fail "README links to missing path: $target" ;;
  esac
done

echo "==> badge workflow files exist"
grep -oE 'actions/workflows/[a-zA-Z0-9_-]+\.yml' README.md | sort -u | while read -r ref; do
  wf=".github/workflows/$(basename "$ref")"
  [[ -f "$wf" ]] || fail "badge references non-existent workflow: $wf"
done

echo "==> LICENSE is MIT and referenced by README"
grep -q "MIT License" LICENSE || fail "LICENSE is not MIT"
grep -q "LICENSE" README.md || fail "README does not reference LICENSE"

echo "==> DOCS VERIFICATION PASSED"
