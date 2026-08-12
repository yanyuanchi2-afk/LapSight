# CLAUDE.md

Project-level guidance for Claude Code.

## Agent Delegation & Token Strategy

Delegate work to external CLI agents (Codex and Antigravity) via bash commands whenever it saves Claude tokens. Treat them as subagents and route work by task type.

### Role of each agent

| Agent | Command | Strengths | Weaknesses |
|-------|---------|-----------|------------|
| **Claude Code (Opus / Fable)** | — (this agent) | Strong overall; best at planning & reasoning about intent | Expensive tokens |
| **Antigravity + Gemini** | `agy` | Frontend & reading/comprehension | Weak reasoning & coding — **unreliable** |
| **Codex + GPT** | `codex --yolo` | Reasoning & coding | Weak UX logic & aesthetic (both UI and code) |

### How to route work

- **Claude (me):** Focus on **planning & discussion**. Avoid spending tokens on implementation — hand it off.
- **Antigravity (`agy`):** Delegate **mass reads and easy tasks** — reading the codebase, documents, and images, or generating UI-SPEC documents.
  - **Only use the Gemini 3.1 pro model.** Never use flash models (e.g. Gemini 3.5 flash).
  - **Never let Antigravity or Gemini mutate any code.** They are VERY unreliable at coding — read-only tasks only.
- **Codex (`codex --yolo`):** Delegate **all other work**, especially **complex coding and research**.

### Principle

Leverage token usage deliberately: keep Claude on high-value planning/discussion and hand off execution work to the agent best suited for it.
