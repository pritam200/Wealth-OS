# Diagrams

Standalone Mermaid sources. Each is also embedded inline in the document that explains it —
these copies exist so they can be rendered, exported, or edited without touching prose.

| File | Explained in |
|---|---|
| `01-system-layers.mmd` | [`../SYSTEM_ARCHITECTURE.md`](../SYSTEM_ARCHITECTURE.md) §2 |
| `02-document-lifecycle.mmd` | [`../SYSTEM_ARCHITECTURE.md`](../SYSTEM_ARCHITECTURE.md) §3.1 |
| `03-identity-resolution.mmd` | [`../SYSTEM_ARCHITECTURE.md`](../SYSTEM_ARCHITECTURE.md) §3.3 |
| `04-scoring-pipeline.mmd` | [`../SYSTEM_ARCHITECTURE.md`](../SYSTEM_ARCHITECTURE.md) §3.4 |
| `05-card-decision.mmd` | [`../SYSTEM_ARCHITECTURE.md`](../SYSTEM_ARCHITECTURE.md) §3.5 |

Render with the Mermaid CLI:

```bash
npx -y @mermaid-js/mermaid-cli -i 01-system-layers.mmd -o 01-system-layers.svg
```
