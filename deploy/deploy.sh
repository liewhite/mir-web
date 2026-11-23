#!/bin/bash
# 线上已在 11-23 换成 Scala 多人版（#1148）。旧的 Node 单机版部署脚本停用：再跑会把线上切回单机版。
# 部署用 deploy/jvm/deploy.sh。确实要回滚到 Node 单机版：git show dce4a23:deploy/deploy.sh 取回旧脚本，
# 并把 deploy/mir176.service 装回 /etc/systemd/system/（Node 版存档 /var/lib/mir176/save.json 还在）。
echo "已停用：线上是 Scala 多人版，请用 deploy/jvm/deploy.sh" >&2
exit 1
