# Own semantic conflicts in the application

Status: Accepted in conversation.
Date: 2026-11-02.

Use jj-inspired draft change revisions and first-class property conflicts, with Git as storage rather than as the semantic merge engine. jj is a reference, not a required executable.

This lets a person continue independent work while an earlier property is conflicted. Resolving the origin replays local descendants and may produce new conflicts instead of silently discarding later edits. Accepted history remains append-only, while draft changes can acquire replacement revisions.

Draft sharing and accepted delivery are separate operations. Conflicted work is shareable, but only a resolved, validated snapshot can become the accepted system. The first version restricts replay to supported scalar attribute edits rather than claiming general SysML merge support.
