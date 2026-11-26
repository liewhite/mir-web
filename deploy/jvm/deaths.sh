#!/bin/bash
# 最近谁在哪死了、被谁杀的（#1338）：在本机跑，ssh 到 kaojiang 翻游戏服务的日志（11-26 起的版本才记）。
#
#   deploy/jvm/deaths.sh              最近 2 天全部玩家死亡记录
#   deploy/jvm/deaths.sh 角色名        只看这个角色
#   deploy/jvm/deaths.sh -d 7 [角色名] 最近 7 天
#
# 一行例：玩家死亡：乙（账号 bob） 35 级 牛魔寺庙大厅(D2079) (10,12)，被甲的宝宝「神兽」杀死，算谋杀（凶手 PK 值涨），死时 PK 值 0
# 行的写法在 scala/server/.../game/ChronicleLog.scala，改了那边的行首字样要跟着改这里。
set -euo pipefail
days=2
while getopts "d:" o; do
  case "$o" in
    d) days="$OPTARG" ;;
    *) echo "用法：$0 [-d 天数] [角色名]"; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[[ "$days" =~ ^[0-9]+$ ]] || { echo "天数要是整数：$days"; exit 2; }
name="${1:-}"
if [[ -n "$name" ]]; then
  filter="grep -F -e $(printf %q "玩家死亡：${name} ") -e $(printf %q "玩家死亡：${name}（")"
else
  filter=cat
fi
ssh -o ConnectTimeout=10 "${MIR_DEPLOY_HOST:-kaojiang}" \
  "out=\$(sudo journalctl -u mir176 --since -${days}d --no-pager -o short-iso | grep -F '玩家死亡：' | $filter); if [ -n \"\$out\" ]; then echo \"\$out\"; else echo '最近 $days 天没有${name:+ ${name} 的}玩家死亡记录（11-26 #1338 部署后才开始记）'; fi"
