// AGGG4ks-author: t.me/aidvizh_hub · t.me/aidvizhenie
// Shared utilities for AGGG4ks scripts.
//
// Cross-platform: node:path / node:os / node:process only — no Bun.*, no shell.

import { existsSync, readdirSync, readFileSync, writeFileSync, mkdirSync, statSync, lstatSync, cpSync, rmSync, symlinkSync } from "node:fs"
import { join, resolve, dirname, relative, extname } from "node:path"
import { homedir, platform } from "node:os"
import { cwd, argv, env } from "node:process"
import { pathToFileURL, fileURLToPath } from "node:url"
import { spawnSync } from "node:child_process"
import { createHash } from "node:crypto"

export { env }

// Repo root (scripts/ → parent). Shared helper for sibling .mjs modules.
export const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), ".."))

// Project root markers — order matters (most specific first).
// Matches spec-locus skill: .git/, .kiro/, pyproject.toml, package.json, etc.
export const PROJECT_MARKERS = [
  ".git",
  ".kiro",
  "pyproject.toml",
  "package.json",
  "Cargo.toml",
  "go.mod",
  "pom.xml",
  "composer.json",
  "*.sln",
  "AGENTS.md", // AGGG4ks-specific: AGENTS.md in root = project root
]

// Maximum depth to walk up (prevent infinite loops on weird FS)
const MAX_WALK_DEPTH = 20

function hasProjectMarker(directory) {
  let entries
  try {
    entries = new Set(readdirSync(directory))
  } catch {
    return false
  }
  for (const marker of PROJECT_MARKERS) {
    if (marker.startsWith("*.")) {
      // Glob pattern (e.g., *.sln) — suffix match, no deps
      const suffix = marker.slice(1)
      for (const name of entries) {
        if (name.endsWith(suffix)) return true
      }
    } else if (entries.has(marker)) {
      return true
    }
  }
  return false
}

// Walk up from `start` (default: cwd) to find the nearest project root.
// Returns absolute path string, or null within MAX_WALK_DEPTH levels.
export function resolveProjectRoot(start = cwd()) {
  let current = resolve(start)
  for (let i = 0; i < MAX_WALK_DEPTH; i++) {
    if (hasProjectMarker(current)) return current
    const parent = resolve(join(current, ".."))
    if (parent === current) break // filesystem root
    current = parent
  }
  return null
}

export function projectLocalSkillsDir(projectRoot) {
  return join(projectRoot, ".agents", "skills")
}

export function projectLocalClaudeDir(projectRoot) {
  return join(projectRoot, ".claude", "skills")
}

// Config dir: XDG_CONFIG_HOME — на всех платформах (CI/тесты и переносимость), иначе ОС-дефолт:
// APPDATA (Windows), Application Support (macOS), ~/.config (Linux).
export function getConfigDir(appName) {
  const home = homedir()
  if (process.env.XDG_CONFIG_HOME) return join(process.env.XDG_CONFIG_HOME, appName)
  if (platform() === "win32") return join(process.env.APPDATA || home, appName)
  if (platform() === "darwin") return join(home, "Library", "Application Support", appName)
  return join(home, ".config", appName)
}

// Data dir: XDG_DATA_HOME — на всех платформах, иначе ОС-дефолт (LOCALAPPDATA / Application Support / ~/.local/share).
export function getDataDir(appName) {
  const home = homedir()
  if (process.env.XDG_DATA_HOME) return join(process.env.XDG_DATA_HOME, appName)
  if (platform() === "win32") return join(process.env.LOCALAPPDATA || home, appName, "data")
  if (platform() === "darwin") return join(home, "Library", "Application Support", appName)
  return join(home, ".local", "share", appName)
}

function exists(p) {
  try {
    return existsSync(p)
  } catch {
    return false
  }
}
export { exists }

// ---------- shared helpers for sibling .mjs modules (kebab-case family) ----------
// These complement the snake_case ports above; both families import from here.
// Nothing below changes behavior of resolveProjectRoot()/getConfigDir()/getDataDir().

// XDG config home (+ optional subpath): Linux ~/.config, respects XDG_CONFIG_HOME.
export function configDir(...parts) {
  return join(process.env.XDG_CONFIG_HOME || join(homedir(), ".config"), ...parts)
}

// Единый помощник видимости ошибок (quality-hardening FR-3): best-effort catch не молчит —
// контекст виден при AGGG4_DEBUG=1, по умолчанию не шумит.
export function catchLog(context, err) {
  if (process.env.AGGG4_DEBUG) {
    const msg = err && err.message ? err.message : String(err)
    console.error(`[debug] ${context}: ${msg}`)
  }
}

export function readJsonSafe(path) {
  try {
    return JSON.parse(readFileSync(path, "utf-8"))
  } catch {
    return null
  }
}

export function writeJsonPretty(path, obj) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, JSON.stringify(obj, null, 2) + "\n", "utf-8")
}

// Minimal text patch: */mcps/<Server> → <root>/mcps/<Server> (mirror of firmware.mjs fixPathsInText).
// BUG-01: root нормализуется в POSIX-форму (Windows `C:\AGGG4` ломал JSON: `\A` — невалидный
// escape) и JSON-экранируется; результат валидируется, если исходник был валидным JSON.
export function fixMcpPaths(text, root) {
  const raw = String(text)
  const posixRoot = String(root).replace(/\\/g, "/").replace(/\/+$/, "")
  const safeRoot = JSON.stringify(`${posixRoot}/mcps/`).slice(1, -1)
  const patched = raw.replace(
    /"(?:[^"\n]*?)\/mcps\/(BrowserMCP|SearchMCP|MemoryMCP)"/g,
    (_m, server) => `"${safeRoot}${server}"`
  )
  let rawIsJson = false
  try { JSON.parse(raw); rawIsJson = true } catch { /* не JSON — патчим как текст */ }
  if (rawIsJson) {
    try {
      JSON.parse(patched)
    } catch {
      throw new Error("fixMcpPaths: результат не валиден как JSON — запись отменена")
    }
  }
  return patched
}

function pad2(n) {
  return String(n).padStart(2, "0")
}

// Backup before overwrite: <file>.bak-YYYYMMDD-HHMMSS next to the file.
export function backupBeforeWrite(path) {
  if (!exists(path)) return null
  const d = new Date()
  const backup = `${path}.bak-${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}-${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`
  writeFileSync(backup, readFileSync(path))
  return backup
}

// Run a command, capture output. Returns { exit, output } — never throws.
export function runCommand(cmd, args = [], { cwd: runCwd, timeoutMs = 120000 } = {}) {
  try {
    const proc = spawnSync(cmd, args, { cwd: runCwd ? String(runCwd) : undefined, encoding: "utf-8", timeout: timeoutMs })
    const out = (proc.stdout || "") + (proc.stderr || "") + (proc.error ? `\n[${proc.error.message}]` : "")
    return { exit: proc.error && proc.status === null ? 2 : (proc.status ?? 2), output: out }
  } catch (e) {
    return { exit: 2, output: String((e && e.message) || e) }
  }
}

// Symlink (dir) → Windows junction → copy fallback. Returns mode string.
export function createLinkOrCopy(src, dst, useCopy = false) {
  if (exists(dst)) return "exists"
  try {
    if (lstatSync(dst).isSymbolicLink()) return "exists"
  } catch { /* not a symlink */ }
  try {
    if (useCopy) {
      cpSync(src, dst, { recursive: true })
      return "copy"
    }
    if (platform() === "win32") {
      symlinkSync(src, dst, "junction")
      return "junction"
    }
    symlinkSync(src, dst, "dir")
    return "symlink"
  } catch {
    try {
      cpSync(src, dst, { recursive: true })
      return "copy"
    } catch (e) {
      return `error: ${e.message}`
    }
  }
}

export function removeLinkOrCopy(path) {
  try {
    if (lstatSync(path).isSymbolicLink()) { rmSync(path, { force: true }); return "removed symlink" }
    if (statSync(path).isDirectory()) { rmSync(path, { recursive: true, force: true }); return "removed copy" }
  } catch { /* fallthrough */ }
  return "not found"
}

// Re-export statSync/lstatSync for consumers
export { statSync, lstatSync, readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync }

export function copyDirRecursive(src, dst) {
  mkdirSync(dst, { recursive: true })
  cpSync(src, dst, { recursive: true })
}

export function rmDirRecursive(path) {
  rmSync(path, { recursive: true, force: true })
}

// Lazy crypto import for sha256 functions
let _crypto = null
async function getCrypto() {
  if (!_crypto) _crypto = await import("node:crypto")
  return _crypto
}

/**
 * Compute SHA-256 hex digest of a file.
 */
export async function sha256File(filePath) {
  const crypto = await getCrypto()
  const hash = crypto.createHash("sha256")
  hash.update(readFileSync(filePath))
  return hash.digest("hex")
}

/**
 * Compute SHA-256 hex digest of a Buffer.
 */
export async function sha256Buffer(buf) {
  const crypto = await getCrypto()
  return crypto.createHash("sha256").update(buf).digest("hex")
}

/**
 * Compute SHA-256 hex digest of a file, returning an empty string when it
 * cannot be read.
 */
export function hashFile(filePath) {
  try {
    return createHash("sha256").update(readFileSync(filePath)).digest("hex")
  } catch {
    return ""
  }
}

/**
 * Compute the hash used for a skill's canonical SKILL.md.
 */
export function hashSkill(skillPath) {
  return hashFile(join(skillPath, "SKILL.md"))
}

/**
 * Resolve install registry placeholders against an optional environment map.
 * Supported placeholders match the AGGG4 installer contract.
 */
export function resolvePath(template, envObj = undefined) {
  const home = (envObj && envObj.HOME) || homedir()
  const config = process.env.XDG_CONFIG_HOME || join(home, ".config")
  const ompDir = process.env.PI_CODING_AGENT_DIR || join(home, ".omp", "agent")
  const hermesHome = process.env.HERMES_HOME || join(home, ".hermes")
  const varsMap = { home, config, omp_dir: ompDir, hermes_home: hermesHome }
  const out = String(template).replace(/\{\{([a-z_]+)(?::([^}]+))?\}\}/g, (match, token, envKey) => {
    if (token in varsMap) return varsMap[token]
    if (envKey !== undefined) return process.env[envKey] || ""
    return match
  })
  if (/\{\{/.test(out)) throw new Error(`unresolved placeholder in ${JSON.stringify(template)}`)
  return resolve(out.replace(/^~(?=$|[/\\\\])/, home))
}

/**
 * Lines of a file as array.
 */
export function readLines(filePath) {
  return readFileSync(filePath, "utf-8").split("\n")
}

// ---------- Phase 2 helpers ----------

/**
 * Parse YAML frontmatter from markdown content.
 * Returns { name, description, ... } or null if no frontmatter.
 * Deliberately simple: no YAML library, just key: value lines.
 */
export function parseFrontmatter(text) {
  const m = text.match(/^---\s*\n([\s\S]*?)\n---\s*\n/)
  if (!m) return null
  const fm = {}
  let currentKey = null
  let currentValue = []
  for (const line of m[1].split("\n")) {
    // Top-level key: value
    const kvMatch = line.match(/^([a-zA-Z_][a-zA-Z0-9_-]*)\s*:\s*(.*)$/)
    if (kvMatch && !line.startsWith("  ")) {
      if (currentKey) {
        fm[currentKey] = currentValue.join("\n").trim()
      }
      currentKey = kvMatch[1]
      currentValue = [kvMatch[2]]
    } else if (currentKey) {
      currentValue.push(line)
    }
  }
  if (currentKey) {
    fm[currentKey] = currentValue.join("\n").trim()
  }
  // Strip quotes from values
  for (const key of Object.keys(fm)) {
    const v = fm[key]
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      fm[key] = v.slice(1, -1)
    }
  }
  return fm
}

/**
 * Recursively walk a directory and yield files matching optional extension.
 * Respects skip dirs (.git, node_modules, __pycache__, etc.).
 */
export function* walkFiles(dir, { ext, skipDirs = new Set([".git", "node_modules", "__pycache__", ".pytest_cache", ".ruff_cache", "dist", ".kiro", ".venv", ".pi"]) } = {}) {
  let entries
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const entry of entries) {
    const fullPath = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (skipDirs.has(entry.name)) continue
      yield* walkFiles(fullPath, { ext, skipDirs })
    } else if (entry.isFile()) {
      if (!ext || extname(entry.name) === ext) {
        yield fullPath
      }
    }
  }
}

/**
 * Execute a Node.js script and return { exit, stdout, stderr }.
 * Never throws on non-zero exit.
 */
export function execScript(scriptPath, args = [], opts = {}) {
  const proc = spawnSync(process.execPath, [scriptPath, ...args], {
    encoding: "utf-8",
    timeout: opts.timeoutMs || 120_000,
    cwd: opts.cwd || undefined,
    env: opts.env || undefined,
  })
  return {
    exit: proc.error && proc.status === null ? 2 : (proc.status ?? 2),
    stdout: proc.stdout || "",
    stderr: proc.stderr || "",
  }
}

/**
 * Check if this module is the main entry point (replaces .includes() guards).
 * Safe against test files that import this module (e.g. test_clean_check.mjs).
 */
// ВНИМАНИЕ: сравнивает URL самого common.mjs — для вызывающих модулей всегда false.
// В entry-скриптах используй inline-guard (см. skills_router_eval.mjs), не эту функцию.
export function isMainModule() {
  try {
    return import.meta.url === pathToFileURL(argv[1] ?? "").href
  } catch {
    return false
  }
}

// Quick diagnostic: `node scripts/common.mjs`
if (isMainModule()) {
  const root = resolveProjectRoot()
  if (root) {
    console.log(`✓ Project root detected: ${root}`)
    console.log(`  .agents/skills/: ${projectLocalSkillsDir(root)}`)
    console.log(`  .claude/skills/: ${projectLocalClaudeDir(root)}`)
  } else {
    console.log("✗ No project root detected from CWD")
  }
}
