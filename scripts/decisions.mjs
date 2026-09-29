#!/usr/bin/env node
// AGGG4ks-author: t.me/aidvizh_hub · t.me/aidvizhenie
// decisions.mjs — CLI для журнала архитектурных решений (port of decisions.py).

import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync as _readdirSync, statSync } from "node:fs"
import { basename, join, relative } from "node:path"
import { parseArgs } from "node:util"
import { createInterface } from "node:readline"
import { ROOT } from "./common.mjs"

const DECISIONS = join(ROOT, ".kiro", "decisions")
const SPECS = join(ROOT, ".kiro", "specs")
const STATUSES = new Set(["proposed", "accepted", "superseded"])
const SECTIONS = ["Status", "Context", "Decision", "Consequences", "Links"]
const SEC_RE = /^##\s+(Status|Context|Decision|Consequences|Links)\s*$/gm
const STATUS_RE = /^##\s+Status\s*\n+(proposed|accepted|superseded)\b/m
const TITLE_RE = /^#\s+\d{4}\.\s+(.+)$/m
const ARCH_PATTERNS = /(выбрали|решили|отвергли|альтернатив|выбор|архитектурн|стек|формат)/i
const FILLER_RE = /^(x|TODO|FIXME|—|-)$/i

function nextNumber() {
  const existing = _readdirSync(DECISIONS).filter(f => /^\d{4}-/.test(f)).sort()
  if (!existing.length) return 1
  let max = 0
  for (const f of existing) {
    const m = f.match(/^(\d{4})-/)
    if (m) max = Math.max(max, Number(m[1]))
  }
  return max + 1
}

function readDirSafe(dir) {
  try {
    return _readdirSync(dir)
  } catch {
    return []
  }
}

function parseDecision(path) {
  const text = readFileSync(path, "utf-8")
  const titleM = text.match(TITLE_RE)
  const statusM = text.match(STATUS_RE)
  const filename = basename(path)
  const numMatch = filename.match(/^(\d{4})/)
  return {
    number: numMatch ? Number(numMatch[1]) : 0,
    slug: filename.includes("-") ? filename.split("-").slice(1).join("-").replace(/\.md$/, "") : filename.replace(/\.md$/, ""),
    title: titleM ? titleM[1].trim() : filename.replace(/\.md$/, ""),
    status: statusM ? statusM[1].trim() : "unknown",
    path,
  }
}

function findDecisionFile(numberStr) {
  const files = readDirSafe(DECISIONS).filter(f => f.startsWith(numberStr + "-")).sort()
  return files.length ? join(DECISIONS, files[0]) : null
}

function updateIndex() {
  try {
    const files = readDirSafe(DECISIONS).filter(f => /^\d{4}-.*\.md$/.test(f)).sort()
    const lines = [
      "# Decision Log\n",
      "\n",
      "Журнал архитектурно значимых решений воркспейса (канон: ADR-light).\n",
      "\n",
      "| NNNN | Заголовок | Статус |\n",
      "|---|---|---|\n",
    ]
    for (const f of files) {
      const d = parseDecision(join(DECISIONS, f))
      lines.push(`| ${String(d.number).padStart(4, "0")} | ${d.title} | ${d.status} |\n`)
    }
    writeFileSync(join(DECISIONS, "README.md"), lines.join(""), "utf-8")
  } catch {}
}

function extractSection(text, name) {
  const lines = text.split("\n")
  const start = lines.findIndex(l => l.trim() === `## ${name}`)
  if (start < 0) return ""
  const rest = lines.slice(start + 1)
  const end = rest.findIndex(l => l.startsWith("## "))
  return (end < 0 ? rest : rest.slice(0, end)).join("\n").trim()
}

function multilineInput(prompt) {
  return new Promise(resolve => {
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    console.log(`${prompt} (завершить пустой строкой):`)
    const lines = []
    rl.on("line", line => {
      if (!line) { rl.close(); resolve(lines.join("\n")); return }
      lines.push(line)
    })
    rl.on("close", () => resolve(lines.join("\n")))
  })
}

// ── Commands ──────────────────────────────────────────────────────────────

function cmdScan() {
  if (!existsSync(SPECS)) { console.log("Нет каталога .kiro/specs/"); return }
  const found = []
  try {
    for (const specDir of readDirSafe(SPECS).sort()) {
      const specPath = join(SPECS, specDir)
      try { if (!statSync(specPath).isDirectory()) continue } catch { continue }
      for (const docName of ["requirements.md", "design.md"]) {
        const doc = join(specPath, docName)
        try {
          const text = readFileSync(doc, "utf-8")
          if (!ARCH_PATTERNS.test(text)) continue
          if (/\.kiro\/decisions\/\d{4}-/.test(text)) continue
          const hits = text.split("\n").map((line, i) => [i + 1, line.trim()]).filter(([, line]) => ARCH_PATTERNS.test(line))
          found.push([relative(ROOT, doc), hits])
        } catch {}
      }
    }
  } catch {}

  if (!found.length) { console.log("✅ Все архитектурные выборы уже зафиксированы"); return }
  console.log(`Найдено ${found.length} спек с архитектурными выборами без записей:\n`)
  for (const [rel, hits] of found) {
    console.log(`📄 ${rel}`)
    for (const [lineno, line] of hits.slice(0, 3)) {
      console.log(`   L${lineno}: ${line.slice(0, 80)}`)
    }
    if (hits.length > 3) console.log(`   ... и ещё ${hits.length - 3}`)
    console.log()
  }
  console.log("Следующий шаг: node scripts/decisions.mjs add")
}

async function cmdAdd(args) {
  mkdirSync(DECISIONS, { recursive: true })
  const num = nextNumber()
  const slug = args.slug || (await new Promise(r => { const rl = createInterface({ input: process.stdin, output: process.stdout }); rl.question("Заголовок (slug): ", a => { rl.close(); r(a.trim()) }) }))
  const status = args.status || (await new Promise(r => { const rl = createInterface({ input: process.stdin, output: process.stdout }); rl.question("Статус [proposed/accepted/superseded]: ", a => { rl.close(); r(a.trim()) }) }))
  if (!STATUSES.has(status)) { console.error(`Ошибка: статус должен быть из [${[...STATUSES].join(", ")}]`); process.exit(1) }

  // Неинтерактивный путь (core-hygiene): флаги заменяют промпты.
  const context = args.context !== undefined ? String(args.context) : await multilineInput("Контекст")
  const decision = args["decision-text"] !== undefined ? String(args["decision-text"]) : await multilineInput("Решение")
  const consequences = args.consequences !== undefined ? String(args.consequences) : await multilineInput("Последствия")
  const linksRaw = args.links !== undefined ? String(args.links)
    : await new Promise(r => { const rl = createInterface({ input: process.stdin, output: process.stdout }); rl.question("Ссылки (через запятую): ", a => { rl.close(); r(a.trim()) }) })
  const links = linksRaw.split(",").map(l => l.trim()).filter(Boolean)

  const filename = `${String(num).padStart(4, "0")}-${slug}.md`
  const filepath = join(DECISIONS, filename)
  const content = `# ${String(num).padStart(4, "0")}. ${slug.replace(/-/g, " ").replace(/\b\w/g, c => c.toUpperCase())}\n\n## Status\n${status}\n\n## Context\n${context}\n\n## Decision\n${decision}\n\n## Consequences\n${consequences}\n\n## Links\n${links.length ? links.map(l => `- ${l}`).join("\n") : "- (нет ссылок)"}\n`

  writeFileSync(filepath, content, "utf-8")
  updateIndex()
  console.log(`✅ Запись создана: ${filepath}`)
  if (status === "proposed") console.log(`📋 Следующий шаг: node scripts/decisions.mjs review ${String(num).padStart(4, "0")}`)
}

function cmdReview(args) {
  const filepath = findDecisionFile(args.number)
  if (!filepath) { console.error(`Ошибка: решение ${args.number} не найдено`); process.exit(1) }

  const text = readFileSync(filepath, "utf-8")
  const issues = []
  let score = 0

  const foundSections = new Set((text.match(SEC_RE) || []).map(s => s.replace(/^##\s+/, "").trim()))
  for (const sec of SECTIONS) {
    if (!foundSections.has(sec)) issues.push(`❌ Нет секции '${sec}'`)
  }

  const statusM = text.match(STATUS_RE)
  if (!statusM) issues.push("❌ Статус не из {proposed, accepted, superseded}")
  else score++

  const context = extractSection(text, "Context")
  if (context.length < 20) issues.push(`⚠️  Контекст слишком короткий (${context.length} символов, нужно ≥20)`)
  else if (FILLER_RE.test(context)) issues.push("⚠️  Контекст — заглушка")
  else score++

  const decision = extractSection(text, "Decision")
  if (decision.length < 10) issues.push(`⚠️  Решение слишком короткое (${decision.length} символов, нужно ≥10)`)
  else if (FILLER_RE.test(decision)) issues.push("⚠️  Решение — заглушка")
  else score++

  const cons = extractSection(text, "Consequences")
  if (cons.length < 10) issues.push(`⚠️  Последствия слишком короткие (${cons.length} символов)`)
  else if (FILLER_RE.test(cons)) issues.push("⚠️  Последствия — заглушка")
  else score++

  const combined = context + " " + cons
  if (/(альтернатив|отвергл|вместо|но|однако|плюс|минус)/i.test(combined)) score++
  else issues.push("⚠️  Нет упоминания альтернатив/плюсов/минусов")

  const linksSection = extractSection(text, "Links")
  if (/\.kiro\/specs\//.test(linksSection)) score++
  else issues.push("⚠️  Нет ссылки на спеку в секции Links")

  const maxScore = 6
  console.log(`📝 Review: ${filepath.split("/").pop()} (${score}/${maxScore})\n`)
  if (issues.length) { for (const issue of issues) console.log(`  ${issue}`); console.log() }
  if (score >= 6) {
    console.log(`✅ Качество хорошее (${score}/${maxScore})`)
    if (statusM && statusM[1] === "proposed") console.log(`📋 Готово к approve: node scripts/decisions.mjs approve ${args.number}`)
  } else if (score >= 4) {
    console.log(`⚠️  Качество приемлемое (${score}/${maxScore}), но есть замечания`)
  } else {
    console.log(`❌ Качество недостаточное (${score}/${maxScore}), доработай перед approve`)
    process.exit(2)
  }
}

function cmdApprove(args) {
  const filepath = findDecisionFile(args.number)
  if (!filepath) { console.error(`Ошибка: решение ${args.number} не найдено`); process.exit(1) }

  const text = readFileSync(filepath, "utf-8")
  const statusM = text.match(STATUS_RE)
  if (!statusM) { console.error("Ошибка: не удалось найти статус"); process.exit(1) }

  const current = statusM[1]
  if (current === "accepted") { updateIndex(); console.log(`✅ Запись ${args.number} уже accepted`); return }
  if (current === "superseded") { console.error(`⚠️  Запись ${args.number} уже superseded, нельзя approve`); process.exit(1) }

  writeFileSync(filepath, text.replace(`## Status\n${current}`, "## Status\naccepted", 1), "utf-8")
  updateIndex()
  console.log(`✅ Запись ${args.number} → accepted`)
}

function cmdList(args) {
  try {
    const files = readDirSafe(DECISIONS).filter(f => /^\d{4}-.*\.md$/.test(f)).sort()
    if (!files.length) { console.log("Журнал решений пуст"); return }
    console.log("| NNNN | Заголовок | Статус |")
    console.log("|---|---|---|")
    for (const f of files) {
      const d = parseDecision(join(DECISIONS, f))
      if (args.status && d.status !== args.status) continue
      console.log(`| ${String(d.number).padStart(4, "0")} | ${d.title} | ${d.status} |`)
    }
  } catch {
    console.log("Журнал решений пуст (нет каталога .kiro/decisions/)")
  }
}

function cmdCheck() {
  const warnings = []

  if (existsSync(SPECS)) {
    try {
      for (const specDir of readDirSafe(SPECS).sort()) {
        const specPath = join(SPECS, specDir)
        try { if (!statSync(specPath).isDirectory()) continue } catch { continue }
        for (const docName of ["requirements.md", "design.md"]) {
          const doc = join(specPath, docName)
          try {
            const text = readFileSync(doc, "utf-8")
            if (ARCH_PATTERNS.test(text) && !/\.kiro\/decisions\/\d{4}-/.test(text)) {
              warnings.push(`⚠️  ${relative(ROOT, doc)}: архитектурный выбор без решения`)
            }
          } catch {}
        }
      }
    } catch {}
  }

  try {
    for (const f of readDirSafe(DECISIONS).filter(f => /^\d{4}-/.test(f)).sort()) {
      const text = readFileSync(join(DECISIONS, f), "utf-8")
      const linksMatch = text.match(/## Links\n(.+?)(?=\n##|\Z)/s)
      if (linksMatch && !/\.kiro\/specs\//.test(linksMatch[1])) {
        warnings.push(`⚠️  ${relative(ROOT, join(DECISIONS, f))}: решение без ссылки на спеку`)
      }
    }
  } catch {}

  if (warnings.length) {
    console.log("Найдены проблемы связности:")
    for (const w of warnings) console.log(`  ${w}`)
    process.exit(2)
  } else {
    console.log("✅ Все связано корректно")
  }
}

function cmdLink(args) {
  const decFile = findDecisionFile(args.decision)
  if (!decFile) { console.error(`Ошибка: решение ${args.decision} не найдено`); process.exit(1) }

  const text = readFileSync(decFile, "utf-8")
  if (text.includes(args.spec)) { console.log(`Ссылка на ${args.spec} уже существует`); return }

  const newText = text.includes("## Links")
    ? text.replace("## Links\n", `## Links\n- ${args.spec}\n`)
    : text + `\n## Links\n- ${args.spec}\n`

  writeFileSync(decFile, newText, "utf-8")
  console.log(`✅ Ссылка добавлена: ${decFile} → ${args.spec}`)
}

// ── Main ──────────────────────────────────────────────────────────────────

function main() {
  const { values, positionals } = parseArgs({
    options: {
      slug: { type: "string" },
      status: { type: "string" },
      number: { type: "string" },
      decision: { type: "string" },
      "decision-text": { type: "string" },
      context: { type: "string" },
      consequences: { type: "string" },
      links: { type: "string" },
      spec: { type: "string" },
      json: { type: "boolean", default: false },
    },
    allowPositionals: true,
    strict: false,
  })

  const [command, ...rest] = positionals

  if (!command) {
    console.log("Usage: node decisions.mjs <scan|add|review|approve|list|check|link|reindex> [args]")
    console.log('add flags: --slug <slug> --status <proposed|accepted> [--context T] [--decision-text T] [--consequences T] [--links "a, b"]')
    return 1
  }

  switch (command) {
    case "scan": cmdScan(); return 0
    case "add": return cmdAdd(values) // async
    case "reindex": updateIndex(); console.log("✅ README.md пересобран из .kiro/decisions/"); return 0
    case "review": return cmdReview({ number: rest[0], ...values })
    case "approve": return cmdApprove({ number: rest[0] })
    case "list": return cmdList(values)
    case "check": return cmdCheck()
    case "link": return cmdLink({ decision: values.decision, spec: values.spec })
    default:
      console.error(`Unknown command: ${command}`)
      return 1
  }
}

const result = main()
if (result instanceof Promise) result.then(exitCode => { if (exitCode) process.exit(exitCode) })
else if (result !== 0) process.exit(result)
