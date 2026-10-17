import fs from 'node:fs';
import path from 'node:path';

const checkout = process.argv[2];
if (!checkout) throw new Error('Expected the upstream checkout path');
const file = path.join(checkout, 'codex-rs/tui/tests/suite/gov_cloud_guidance_tests.rs');
const original = fs.readFileSync(file, 'utf8');
if (original.includes('GOV_PTY_PROBE')) throw new Error('Probe already applied');
const newline = original.includes('\r\n') ? '\r\n' : '\n';
let source = original.replaceAll('\r\n', '\n');
const loop = '        loop {\n            // Startup drains pending keys';
if (source.split(loop).length !== 2) throw new Error('Unexpected original interaction loop');
source = source.replace(loop,
  '        let mut write_attempt = 0;\n        loop {\n            // Startup drains pending keys');
const write = '            terminal.write_input(if action == "quit" { b"q" } else { b"\\r" })?;';
if (source.split(write).length !== 2) throw new Error('Unexpected original PTY write');
const probe = `            write_attempt += 1;
            if let Err(error) = terminal.write_input(if action == "quit" { b"q" } else { b"\\r" }) {
                eprintln!("GOV_PTY_PROBE action={action} attempt={write_attempt} write_error={error:?}");
                eprintln!("GOV_PTY_PROBE child_at_error={:?}", terminal.child.try_wait());
                eprintln!("GOV_PTY_PROBE config_unchanged={:?}",
                    std::fs::read_to_string(&path).map(|saved| saved == original));
                while Instant::now() < deadline {
                    match terminal.child.try_wait() {
                        Ok(Some(status)) => {
                            eprintln!("GOV_PTY_PROBE child_exit={status} success={}", status.success());
                            break;
                        }
                        Ok(None) => std::thread::sleep(Duration::from_millis(10)),
                        Err(wait_error) => {
                            eprintln!("GOV_PTY_PROBE child_wait_error={wait_error:?}");
                            break;
                        }
                    }
                }
                // Preserve the original failure after collecting failure-only diagnostics.
                return Err(error);
            }`;
source = source.replace(write, probe);
const restored = source.replace(probe, write).replace('        let mut write_attempt = 0;\n', '');
if (restored !== original.replaceAll('\r\n', '\n')) throw new Error('Probe changed original statements');
fs.writeFileSync(file, source.replaceAll('\n', newline));
console.log('GovCloud probe applied; original success path and assertions preserved; write errors still fail.');
