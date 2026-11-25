#!/bin/bash
# 全服公告（#1243）：在本机跑，ssh 到 kaojiang 让游戏服务给所有在线玩家的聊天框发一条「〖公告〗内容」。
#
#   deploy/jvm/announce.sh "5 分钟后重启更新，请找安全的地方下线"
#
# 内容走标准输入传过去（不拼进远程命令行，引号、空格、特殊字符都原样）；最长 200 字。
# 服务器上等价于：echo "内容" | /opt/mir176/bin/mir176ctl announce
# 成功时打印「已发给 N 个连接，游戏里在线 M 人：角色名…」，服务没在跑时报错退出。
set -euo pipefail
[[ $# -ge 1 && -n "$*" ]] || { echo "用法：$0 \"公告内容\""; exit 2; }
printf '%s' "$*" | ssh -o ConnectTimeout=10 "${MIR_DEPLOY_HOST:-kaojiang}" /opt/mir176/bin/mir176ctl announce
