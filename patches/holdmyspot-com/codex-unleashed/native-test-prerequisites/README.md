# Native test prerequisites

Apply these three prerequisites after queue entry 103, in the order shown. They repair test compilation or scheduling prerequisites needed by the platform checks.

| Queue entry | Correction | Source change | Exact predecessor |
| --- | --- | --- | --- |
| 104 | Construct byte streams explicitly in the Unix signal test while preserving its signal assertions. | `cf896648ede752dd665635839687edc83b9aad9b` | The accepted queue 103 snapshot, ending at `windows-command-capture/platform-guards.patch`. |
| 105 | Reserve all configured nextest slots for the macOS ARM64 deep-home daemon test while retaining external CPU stress and the existing four-minute outer bound. | `38d7c278a287c83506ae3c844b192674ce33fd49` | Entry 104, `cf896648ede752dd665635839687edc83b9aad9b`. |
| 106 | Restrict the shell snapshot test imports to Linux and macOS, matching the test that consumes them. | `9a74c98b7b41a2482cc3bbe71b946f17d937f0f1` | Entry 105, `38d7c278a287c83506ae3c844b192674ce33fd49`. |

The source packaging for each entry was accepted at its listed source identity. The Windows native compile, Clippy, and behavior checks remain unrun. The final registered hosted macOS ARM64 stress gate and full revised queue acceptance also remain pending.
