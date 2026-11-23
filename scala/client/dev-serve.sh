#!/usr/bin/env bash
# 本机看客户端（不要服务端）：把 web/ 和素材 public/assets 拼到一个临时目录，用 http.server 提供。
# 打开 http://127.0.0.1:<端口>/?local 就是在浏览器里跑本地服务端（mir.client.net.LocalServer）。
# 先 `sbt client/fastLinkJS` 生成 web/js/main.js。只监听 127.0.0.1（素材不对外）。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
port="${1:-18190}"
root="$(mktemp -d /tmp/mir-client.XXXXXX)"
trap 'rm -rf "$root"' EXIT
for f in "$here"/web/*; do ln -s "$f" "$root/$(basename "$f")"; done
ln -s "$(cd "$here/../../public/assets" && pwd -P)" "$root/assets"
cd "$root"
# /usr/bin/python3：Apple 签名，本机端口收连接不会被本地网络权限拦
exec /usr/bin/python3 -m http.server "$port" --bind 127.0.0.1
