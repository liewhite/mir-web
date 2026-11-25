#!/bin/bash
# 各 boss 现在活着还是几点刷（#1277）：在本机跑，ssh 到 kaojiang 问正在跑的游戏服务。
#
#   deploy/jvm/bosses.sh          现状：每个 boss 刷怪点一行（活着的坐标和血量，死了的几点刷，没人进过的图进图即刷）
#   deploy/jvm/bosses.sh log      最近 2 天的 boss 死亡、刷出记录（谁拿的归属、最后一下是谁、下次刷新时刻）
set -euo pipefail
case "${1:-}" in
  "")  ssh -o ConnectTimeout=10 "${MIR_DEPLOY_HOST:-kaojiang}" /opt/mir176/bin/mir176ctl bosses ;;
  log) ssh -o ConnectTimeout=10 "${MIR_DEPLOY_HOST:-kaojiang}" "sudo journalctl -u mir176 --since -2d --no-pager -o short-iso | grep -E 'boss (刷出|死亡)' || echo '最近 2 天没有 boss 记录'" ;;
  *)   echo "用法：$0 [log]"; exit 2 ;;
esac
