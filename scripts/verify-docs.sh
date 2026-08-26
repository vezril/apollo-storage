#!/usr/bin/env bash
# Documentation verification (documentation spec): referenced files exist, the
# README's internal links resolve, and every CI/CD badge points at a real
# workflow file (no misnamed-workflow 404s).
set -euo pipefail

fail() { echo "FAIL: $1"; exit 1; }

echo "==> required files present"
for f in README.md LICENSE docker-compose.yml server/src/main/resources/ddl/create_tables_postgres.sql; do
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

echo "==> agent handoff + API tooling present"
for f in AGENTS.md docs/rest-api.openapi.yaml insomnia/apollostorage.insomnia_collection.json insomnia/README.md; do
  [[ -f "$f" ]] || fail "missing $f"
done

echo "==> AGENTS.md internal links resolve"
grep -oE '\]\(([^)#]+)\)' AGENTS.md | sed -E 's/\]\(([^)]+)\)/\1/' | while read -r target; do
  case "$target" in
    http*|"") continue ;;
    *) [[ -e "$target" ]] || fail "AGENTS.md links to missing path: $target" ;;
  esac
done

echo "==> Insomnia collection is valid JSON"
python3 -c 'import json,sys; json.load(open("insomnia/apollostorage.insomnia_collection.json"))' \
  || fail "insomnia collection is not valid JSON"

# The collection fell a release behind the API once. Every documented path must be exercised by at
# least one request, checked without a YAML parser so this runs anywhere.
echo "==> Insomnia collection covers every documented path"
collection="insomnia/apollostorage.insomnia_collection.json"
while read -r path; do
  # Documented placeholders map to the collection's environment variables.
  request_url=$(printf '%s' "$path" \
    | sed -e 's/{bucket}/{{ _.bucket }}/g' -e 's/{object}/{{ _.object }}/g')
  # The trailing quote anchors the match so /v1/buckets cannot satisfy /v1/buckets/{bucket}.
  grep -qF "{{ _.base_url }}${request_url}\"" "$collection" \
    || fail "documented path not exercised by the Insomnia collection: $path"
done < <(grep -oE '^  /[^:]*' docs/rest-api.openapi.yaml | sed 's/^  //')

echo "==> DOCS VERIFICATION PASSED"
