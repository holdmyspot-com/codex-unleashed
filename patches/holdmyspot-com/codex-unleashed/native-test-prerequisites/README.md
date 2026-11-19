# Native test prerequisites

Apply these two prerequisites after queue entry 95, in the order shown. They repair test compilation or scheduling prerequisites needed by the platform checks.

| Queue entry | Correction | Original source change | Exact predecessor |
| --- | --- | --- | --- |
| 96 | Construct byte streams explicitly in the Unix signal test while preserving its signal assertions. | `cf896648ede752dd665635839687edc83b9aad9b` | Queue 95, ending at `windows-command-capture/platform-guards.patch`. |
| 97 | Reserve all configured nextest slots for the macOS ARM64 deep-home daemon test while retaining external CPU stress and the existing four-minute outer bound. | `38d7c278a287c83506ae3c844b192674ce33fd49` | Entry 96. |

The source packaging for each entry was accepted at its listed source identity before the `fail_closed_fallbacks` patches and `windows-shell-snapshot-test-imports.patch` were removed from the queue; the queue numbers above are the ones after that removal, and entry 96 was regenerated on top of the reworked `windows-command-capture/partial-output-errors.patch`. The Windows native compile, Clippy, and behavior checks remain unrun. The final registered hosted macOS ARM64 stress gate and full revised queue acceptance also remain pending.
