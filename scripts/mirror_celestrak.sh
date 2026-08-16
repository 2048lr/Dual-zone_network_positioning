#!/usr/bin/env bash
# 把 CelesTrak 四个 TLE 源原样镜像到 hamkit.click 静态目录，作为客户端加速源。
#
# 客户端（app/src/main/java/com/example/hamkit/data/satellite/SatelliteDataSource.kt）
# 的四个 URL 常量与这里的输出路径一一对应，修改任一处须同步另一处。
#
# 部署（hamkit.click 服务器，Ubuntu 22.04 + nginx）：
#   - 脚本输出到 /var/www/hamkit/tle/，nginx root 即 /var/www/hamkit
#   - 由 /usr/local/bin/sync_hamkit.sh 末尾调用，每次同步后兜底刷新
#   - 内部用时间戳控制最小刷新间隔（默认 2 小时），避免高频拉取 CelesTrak
#   - 也可独立 cron：0 */2 * * * /usr/local/bin/mirror_celestrak.sh
#
# 容错：任一源下载失败则保留旧文件（curl --fail + 原子 mv + 非空校验），
#       保证镜像始终可用。CelesTrak 不可达时不覆盖已有镜像。
#       核心源（satnogs/amateur/iss）任一成功即更新时间戳，active 为可选补全，
#       失败不阻塞限频。

set -euo pipefail

TLE_DIR="${TLE_DIR:-/var/www/hamkit/tle}"
MIN_INTERVAL_SECONDS="${MIN_INTERVAL_SECONDS:-7200}"  # 2 小时
STAMP_FILE="${TLE_DIR}/.last_refresh"
CELESTRAK_BASE="https://celestrak.org/NORAD/elements/gp.php"

mkdir -p "$TLE_DIR"

# 频率控制：距上次成功刷新不足间隔则跳过（被 sync_hamkit.sh 高频调用时生效）
if [ -f "$STAMP_FILE" ]; then
  last=$(stat -c %Y "$STAMP_FILE" 2>/dev/null || echo 0)
  now=$(date +%s)
  if [ $((now - last)) -lt "$MIN_INTERVAL_SECONDS" ]; then
    exit 0
  fi
fi

# 源定义：名称 | CelesTrak query | 输出文件名 | 是否核心源
# 顺序与 SatelliteDataSource.kt 的常量保持一致。
SOURCES=(
  "satnogs|GROUP=satnogs&FORMAT=3le|satnogs.3le|1"
  "amateur|GROUP=amateur&FORMAT=3le|amateur.3le|1"
  "active|GROUP=active&FORMAT=csv|active.csv|0"
  "iss|CATNR=25544&FORMAT=3le|iss.3le|1"
)

fetch_one() {
  local name="$1" query="$2" out="$3"
  local url="${CELESTRAK_BASE}?${query}"
  local tmp="${out}.tmp.$$"

  echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] 镜像 ${name} ← ${url}"

  # --fail：HTTP 非 2xx 视为失败；--retry：短暂网络抖动重试；-m：总超时 60s
  # -A：带 UA，避免部分端点对默认 UA 返回 403
  if curl --fail --retry 3 --retry-delay 5 -m 60 -sS \
        -A "HamKit-TLE-Mirror/1.0" -o "$tmp" "$url"; then
    # 非空校验，避免空文件覆盖旧镜像
    if [ -s "$tmp" ]; then
      mv -f "$tmp" "$out"
      chown www-data:www-data "$out" 2>/dev/null || true
      chmod 644 "$out"
      echo "  → 成功，$(wc -c < "$out") 字节"
      return 0
    else
      echo "  → 失败：响应为空，保留旧文件"
      rm -f "$tmp"
      return 1
    fi
  else
    echo "  → 失败：curl 退出码 $?，保留旧文件"
    rm -f "$tmp"
    return 1
  fi
}

core_ok=0
fail=0
for entry in "${SOURCES[@]}"; do
  IFS='|' read -r name query out is_core <<< "$entry"
  if fetch_one "$name" "$query" "${TLE_DIR}/${out}"; then
    if [ "$is_core" = "1" ]; then
      core_ok=1
    fi
  else
    fail=1
  fi
done

# 核心源（satnogs/amateur/iss）任一成功即更新时间戳，保证下次按间隔跳过。
# active 为可选补全，失败不阻塞限频。
if [ "$core_ok" -eq 1 ]; then
  touch "$STAMP_FILE"
  chown www-data:www-data "$STAMP_FILE" 2>/dev/null || true
  if [ "$fail" -eq 1 ]; then
    echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] 核心源成功，可选源部分失败，已更新时间戳"
  fi
else
  echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] 全部核心源失败，不更新时间戳"
fi
exit 0