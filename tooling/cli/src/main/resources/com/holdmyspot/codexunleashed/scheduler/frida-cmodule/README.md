# Frida CModule runtime headers

`glib.h`, `gum/gumdefs.h` and `gum/guminterceptor.h` are unmodified copies of Frida Gum 17.24.3's
`gum/runtime/cmodule/` headers, from https://github.com/frida/frida-gum/tree/17.24.3/gum/runtime/cmodule.
Frida Gum is distributed under the wxWindows Library Licence 3.1, reproduced in `COPYING`.

The macOS distribution build compiles each `mac-*.c` CModule source against these headers into a dylib in the
runtime's `lib/codex/` directory. Each hook script then loads that dylib with `new CModule(bytes, symbols)`. Gum
binds the dylib's undefined functions to the same runtime functions it gives TinyCC-compiled sources, so a dylib
must be compiled against the headers of the Frida version that loads it. Replace these copies whenever the pinned
Frida version changes.
