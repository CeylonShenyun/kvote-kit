#!/bin/bash
# 主动刷新一次：让 Claude Code 用最便宜的模型回一个字，顺手把这一轮带回来的用量存下来。
# 适合没有 bridge、也不常开终端的人，丢进 cron 每隔一阵跑一次。
# 注意：每跑一次都会真的用掉一点点额度（Haiku 回一个字）；不加载你的 hooks / MCP，免得白白多花。
#   */30 * * * * bash /路径/kvote-kit/capture/refresh.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"
claude -p "只回一个字：好" --model haiku --output-format stream-json --verbose \
  --setting-sources local --strict-mcp-config < /dev/null \
  | python3 "$HERE/stream_tap.py" > /dev/null
