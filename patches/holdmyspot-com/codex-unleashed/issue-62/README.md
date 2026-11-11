# TLS errors inside I/O wrappers (dropped)

- Issue: [holdmyspot-com/codex-unleashed#62](https://github.com/holdmyspot-com/codex-unleashed/issues/62)
- Status: removed from the queue on 2026-11-11. Upstream `openai/codex` tag `rust-v0.167.0` (commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`) provides the fix.

## Intent

Codex recognizes a TLS failure when a transport or route resolver wraps it in nested I/O errors, and it does not treat certificate-like text alone as a TLS failure.

## Why it was dropped

`rust-v0.167.0` follows `io::Error::get_ref` when classifying request failures (`codex-rs/http-client/src/route_aware_client_pool.rs`), which is the fix this patch carried on the earlier base. After the rebase only the patch's regression tests remained. Upstream is assumed to test its own fix, so the tests were removed with the fix ([patch-queue rule](../../../../.cat/rules/include/patch-queue.md)).

The earlier red/green verification on the old base was run `42383772408`.
