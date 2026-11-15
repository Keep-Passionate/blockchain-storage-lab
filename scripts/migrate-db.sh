#!/usr/bin/env bash
# 文件功能：在既有项目数据库中增量创建四层业务表；不删除旧数据、不重建Docker卷。
set -euo pipefail
source "$(dirname "$0")/lab-env.sh"
# 通过环境传递密码，不放入命令行或输出；禁用shell xtrace，避免显示凭据。
docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" bsl-mysql \
  mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" \
  < "$LAB_ROOT/infrastructure/mysql/migrations/002_provenance.sql"
echo '四层业务数据库迁移完成（CREATE IF NOT EXISTS）。'
