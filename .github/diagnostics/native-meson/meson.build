project('resource-probe', ['c', 'cpp'], default_options: ['cpp_std=c++20'])

cc = meson.get_compiler('c')
assert(cc.has_function('puts', prefix: '#include <stdio.h>'), 'Standard C function detection must succeed')
assert(not cc.has_function('codex_unleashed_missing_function'), 'Unavailable function detection must fail')

probe = static_library('probe', 'probe.c')
executable('resource-probe', 'main.cc', link_with: probe)
