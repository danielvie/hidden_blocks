# Governing project intent

Read and follow C:/Users/daniel/.agents/AGENTS.md first.

This project is a DISPOSABLE proof of concept. It is not intended for production or as the foundation of a production product. Its only question is:

> Can a SysML v2 editing application use a jj-like flow to rebase different changes in a way that is easy to understand and intuitive to use?

## Decision rules

- Optimize for a short, repeatable experiment and observable user understanding, not production readiness.
- Keep one connected reference model, fixed Alice/Bob environments, and an easy Reset. Disposable demo data is intentionally discarded by Reset.
- The normal scripted demonstration is seven header actions: Reset, Alice edits, Bob edits, Alice publishes, Bob publishes, Bob adds another feature, Resolve. Recording, actor selection, rebasing and final integration happen inside those shortcuts; manual controls remain optional.
- Demonstrate real behavior: independent changes, automatic rebasing, explicit property conflicts that do not block unrelated work, and resolution propagation that preserves later edits.
- Keep source and diagrams consistent through the official local parser. Connections/specialization make the example understandable, but do not claim their structural changes are mergeable when only scalar changes are supported.
- Keep the default interface quiet. Show details in context or on demand. Do not expose storage/version-control internals unless needed to explain the experiment.
- Judge success by whether a person can complete the scenario and explain what was rebased, what conflicted, and why later work survived. Automated tests establish behavior, not intuitive UX.
- Do not add production deployment, authentication, distributed infrastructure, broad language coverage, extensibility frameworks, or hardening work unless the user explicitly asks or it is necessary to keep this experiment truthful and repeatable.
- Do not remove working infrastructure just to reduce line count. Prefer spending effort on the experiment and UX; simplify internals only when doing so reduces friction or clarifies the proof.
- Before expanding scope, state which part of the single question the proposed work will answer.

This clarification takes priority over production-like ambitions implied by older plans. Preserve basic scope safety: Reset targets only application demo data, never source/reference repositories or unrelated files.
