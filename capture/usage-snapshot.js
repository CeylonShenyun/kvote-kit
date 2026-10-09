// 给 Node 写的 bridge 用：收到 Claude Code stream-json 的每个事件时调一次 recordUsage(event)。
// rate_limit_event 里是各个额度窗口的用量（0~1）和重置时间（Unix 秒），存进 kvote 的数据文件；
// 别的事件直接忽略。不碰登录凭证、不多发请求；写失败只返回 false，绝不抛。
//
//   import { recordUsage } from "./usage-snapshot.js";
//   child.stdout 每解析出一行 JSON：recordUsage(event);
//
// 数据文件默认 ~/.claude/kvote-usage.json，环境变量 KVOTE_FILE 改（跟 server.py 读的是同一个）。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export const KVOTE_FILE = process.env.KVOTE_FILE || path.join(os.homedir(), ".claude", "kvote-usage.json");

function freshWindows(info) {
  if (info?.unifiedWindows && typeof info.unifiedWindows === "object") return info.unifiedWindows;
  // 老一点的格式只有一个窗口
  if (info?.rateLimitType && info.utilization !== undefined) {
    return { [info.rateLimitType]: { utilization: info.utilization, resetsAt: info.resetsAt } };
  }
  return null;
}

/** 上一份 + 这一次 → 新的一份。这次没带的窗口（比如按模型分的那个）没过重置时间就留着。 */
export function mergeUsage(prev, info, nowSec) {
  const fresh = freshWindows(info);
  if (!fresh) return null;
  const windows = {};
  for (const [key, w] of Object.entries(prev?.windows || {})) {
    if (Number(w?.resetsAt) > nowSec) windows[key] = w;
  }
  let got = 0;
  for (const [key, w] of Object.entries(fresh)) {
    const utilization = Number(w?.utilization);
    const resetsAt = Number(w?.resetsAt);
    if (!Number.isFinite(utilization) || !Number.isFinite(resetsAt)) continue;
    windows[key] = { utilization, resetsAt };
    got++;
  }
  if (!got) return null;
  return { updated: nowSec, status: info.status || null, windows };
}

export function recordUsage(event, file = KVOTE_FILE, nowSec = Math.floor(Date.now() / 1000)) {
  if (event?.type !== "rate_limit_event") return false;
  try {
    let prev = null;
    try { prev = JSON.parse(fs.readFileSync(file, "utf8")); } catch {}
    const next = mergeUsage(prev, event.rate_limit_info || event.rateLimitInfo, nowSec);
    if (!next) return false;
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(next));
    fs.renameSync(tmp, file);
    return true;
  } catch {
    return false;
  }
}
