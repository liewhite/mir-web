#!/usr/bin/env bash
# 用 TS 版规则重算 ts-golden.json（Scala 版规则测试的对照值）。在仓库任意位置运行。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
esbuild="$repo/game/node_modules/.bin/esbuild"
[ -x "$esbuild" ] || { echo "缺 esbuild：先在 game/ 里 npm i" >&2; exit 1; }
tmp="$(mktemp -d)"
"$esbuild" "$here/gen-golden.ts" --bundle --platform=node --format=esm --outfile="$tmp/gen.mjs" --log-level=warning
node "$tmp/gen.mjs" "$repo/public/assets/data/game.json" "$here/ts-golden.json"
rm -rf "$tmp"
echo "已写 $here/ts-golden.json"
