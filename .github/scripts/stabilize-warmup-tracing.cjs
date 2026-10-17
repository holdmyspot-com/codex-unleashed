const fs = require('fs');
const file = process.argv[2] + '/codex-rs/core/src/session/resume_warmup_tests.rs';
const original = fs.readFileSync(file, 'utf8');
let source = original.replace(/\r\n/g, '\n');
const tests = [
    'resumed_history_does_not_discover_skills_before_restoration',
    'forked_history_does_not_discover_skills_before_restoration',
    'execution_context_still_discovers_skills',
    'session_is_available_during_skill_warmup_and_first_context_waits',
    'failed_skill_warmup_does_not_skip_execution_discovery',
];
for (const test of tests) {
    const declaration = 'async fn ' + test + '() {\n';
    if (source.split(declaration).length !== 2) throw Error('Expected one test: ' + test);
    source = source.replace(declaration, declaration +
        '    // Keep a second dispatch alive so concurrent untraced tests cannot cache shared callsites as disabled.\n' +
        '    let _interest_cache_guard =\n' +
        '        tracing::Dispatch::new(tracing::subscriber::NoSubscriber::default());\n');
}
fs.writeFileSync(file, original.includes('\r\n') ? source.replace(/\n/g, '\r\n') : source);
console.log('Added tracing interest-cache guards to five observer tests; assertions and deadlines unchanged.');
