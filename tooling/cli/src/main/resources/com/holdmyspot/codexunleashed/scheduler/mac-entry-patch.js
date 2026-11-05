/**
 * Redirects Darwin function entries to native replacements without Gum's Interceptor, for the macOS hook scripts
 * that MacEntryPatch places it before. Each patch keeps the replaced entry callable through its resume code, survives
 * fork into a child that installs its own patch, and is restored when its script unloads unless a later installation
 * has repatched the entry.
 */

/*
 * WORKAROUND: https://github.com/frida/frida-gum/issues/474
 * Interceptor.attach and Interceptor.replace keep a function's trampoline in use while any thread is inside the
 * function, and Script load, Script unload and Session detach wait until no trampoline is in use. A process whose other
 * thread is inside a hooked function when it forks, such as git fetch's sideband reader blocked in read or a rustc
 * thread inside malloc when rustc forks its linker, gives its child an in-use count that only the vanished thread could
 * release. Every later load or unload in that child then waits until it times out or forever, which held the scheduler
 * in the child's bootstrap Session detach and timed out the child's allocation hook load. replaceFast keeps no such
 * count, but Gum accepts it on Darwin syscall veneers only with forced relocation, which overwrites 16 bytes: a thread
 * already blocked in the svc then returns into the redirect's address literal and dies with SIGILL. This prelude
 * therefore overwrites only an entry's first instructions with a short branch, as Gum's default hook does, so a blocked
 * thread resumes at unchanged code and no Gum bookkeeping survives a fork. A replacement also runs for Frida's own
 * threads, which attach listeners skip.
 */
if (Process.platform !== 'darwin' || (Process.arch !== 'x64' && Process.arch !== 'arm64'))
    throw new Error('Entry patching requires Darwin ARM64 or Intel64');
/** Bytes of an entry replaced by the branch to its stub: adrp and br on ARM64, a rel32 jmp on Intel. */
const entryPatchLength = Process.arch === 'arm64' ? 8 : 5;
/** Most distance between an entry and its stub that the patched branch reaches. */
const entryPatchStubReach = Process.arch === 'arm64' ? 0xfff00000 : 0x7ff00000;
/** Stub offset of the code that runs the relocated entry instructions and resumes the function after them. */
const entryPatchResumeOffset = 0x40;
/** Stub offset of the record naming the patched entry and its original instructions. */
const entryPatchRecordOffset = 0x100;
/** Most original bytes a record holds; Intel instructions reach 15 bytes and relocation stops at the first boundary. */
const entryPatchRecordCapacity = 32;
/** Identifies a stub written by this prelude, so a fork child recognizes a patch inherited from its parent. */
const entryPatchStubMagic = 'codex-entry-patch-stub-v1';
/** Stubs of this script, retained for the image's lifetime. */
const entryPatchStubs = [];
/** Patched entries with their stubs and original bytes, restored when this script unloads. */
const entryPatches = [];
/** Never collected, so its weak callback runs only when this script unloads. */
const entryPatchOwner = {};

/**
 * Returns the stub that a patched entry branches to.
 * @param target function entry point
 * @return the branch destination, or null when the entry is not the branch this prelude writes
 */
function entryPatchDestination(target) {
    if (Process.arch === 'arm64') {
        const first = Instruction.parse(target);
        const second = Instruction.parse(first.next);
        if (!first.toString().startsWith('adrp x17, ') || second.toString() !== 'br x17')
            return null;
        const word = target.readU32();
        let pages = (((word >>> 5) & 0x7ffff) << 2) | ((word >>> 29) & 3);
        if (pages >= 0x100000)
            pages -= 0x200000;
        return target.and(ptr('0xfffffffffffff000')).add(int64(pages).shl(12));
    }
    if (target.readU8() !== 0xe9)
        return null;
    return target.add(5).add(target.add(1).readS32());
}

/*
 * A fork child inherits its parent's patch, and Frida unloads the inherited parent script there either before or after
 * the child's installation. A patched entry is therefore accepted only when it branches to a still-mapped stub of this
 * prelude that records the same entry; its original bytes go back in place before relocation, because relocated
 * PC-relative instructions must be read at their own address.
 */
/**
 * Restores an entry patched by an inherited installation and returns nothing for an unpatched one.
 * @param name function name for diagnostics
 * @param target function entry point
 * @throws Error if the entry branches somewhere other than an inherited stub of this prelude for the same entry
 */
function restoreInheritedEntry(name, target) {
    const inherited = entryPatchDestination(target);
    if (inherited === null)
        return;
    const record = inherited.add(entryPatchRecordOffset);
    const range = Process.findRangeByAddress(record);
    if (range === null || !range.protection.startsWith('r') || record.readUtf8String() !== entryPatchStubMagic ||
        !record.add(0x20).readPointer().equals(target))
        throw new Error(name + ' is patched by another owner: ' + Instruction.parse(target));
    const length = record.add(0x28).readU32();
    if (length < entryPatchLength || length > entryPatchRecordCapacity)
        throw new Error(name + ' inherited patch record has an invalid length');
    const original = record.add(0x30).readByteArray(length);
    Memory.patchCode(target, length, code => code.writeByteArray(original));
}

/**
 * Writes resume code that runs the entry's first instructions, relocated, and then jumps to the first one it did not
 * relocate.
 * @param name function name for diagnostics
 * @param target function entry point holding its original instructions
 * @param code writable stub address of the resume code
 * @param resume executable address of the resume code
 * @return number of entry bytes relocated, at least entryPatchLength
 * @throws Error if the entry ends, or holds an instruction that cannot be relocated, before entryPatchLength bytes
 */
function writeEntryResume(name, target, code, resume) {
    const arm64 = Process.arch === 'arm64';
    const writer = arm64 ? new Arm64Writer(code, {pc: resume}) : new X86Writer(code, {pc: resume});
    const relocator = arm64 ? new Arm64Relocator(target, writer) : new X86Relocator(target, writer);
    let consumed = 0;
    while (consumed < entryPatchLength) {
        const read = relocator.readOne();
        if (read === 0)
            throw new Error(name + ' entry is too short to patch: ' + Instruction.parse(target));
        consumed = read;
    }
    if (consumed > entryPatchRecordCapacity)
        throw new Error(name + ' entry instructions exceed the patch record');
    relocator.writeAll();
    if (arm64) {
        writer.putLdrRegAddress('x17', target.add(consumed));
        writer.putBrReg('x17');
    } else {
        writer.putJmpAddress(target.add(consumed));
    }
    writer.flush();
    relocator.dispose();
    writer.dispose();
    return consumed;
}

/**
 * Branches a function entry to a native replacement. The returned resume address calls the original function and
 * must be published to the replacement by the caller's onResume before any thread can reach the replacement.
 * @param name function name for diagnostics
 * @param target function entry point, unpatched or patched by an inherited installation of this prelude
 * @param replacement native function that callers of target reach instead
 * @param onResume receives the resume address before the entry is patched
 * @return the resume address
 * @throws Error if the entry is patched by another owner, is too short to relocate, or has no stub within reach
 */
function installEntryPatch(name, target, replacement, onResume) {
    restoreInheritedEntry(name, target);
    const stub = Memory.alloc(Process.pageSize, {near: target, maxDistance: entryPatchStubReach});
    entryPatchStubs.push(stub);
    const resume = stub.add(entryPatchResumeOffset);
    let consumed;
    Memory.protect(stub, Process.pageSize, 'r-x');
    Memory.patchCode(stub, Process.pageSize, code => {
        if (Process.arch === 'arm64') {
            const entry = new Arm64Writer(code, {pc: stub});
            entry.putLdrRegAddress('x17', replacement);
            entry.putBrReg('x17');
            entry.flush();
        } else {
            const entry = new X86Writer(code, {pc: stub});
            entry.putJmpAddress(replacement);
            entry.flush();
        }
        consumed = writeEntryResume(name, target, code.add(entryPatchResumeOffset), resume);
        const record = code.add(entryPatchRecordOffset);
        record.writeUtf8String(entryPatchStubMagic);
        record.add(0x20).writePointer(target);
        record.add(0x28).writeU32(consumed);
        record.add(0x30).writeByteArray(target.readByteArray(consumed));
    });
    const original = target.readByteArray(consumed);
    onResume(resume);
    Memory.patchCode(target, entryPatchLength, code => {
        if (Process.arch === 'arm64') {
            const branch = new Arm64Writer(code, {pc: target});
            branch.putAdrpRegAddress('x17', stub);
            branch.putBrReg('x17');
            branch.flush();
            if (branch.offset !== entryPatchLength)
                throw new Error('Unexpected Darwin entry branch length');
        } else {
            code.writeU8(0xe9);
            code.add(1).writeS32(stub.sub(target.add(5)).toInt32());
        }
    });
    if (!entryPatchDestination(target).equals(stub))
        throw new Error(name + ' entry branch does not reach its stub');
    entryPatches.push({target: target, original: original, stub: stub});
    return resume;
}

/*
 * An entry that no longer branches to this script's stub was repatched by a fork child's own installation after the
 * child inherited this script, so restoring it would remove the child's hook.
 */
Script.bindWeak(entryPatchOwner, () => {
    for (const patch of entryPatches) {
        const destination = entryPatchDestination(patch.target);
        if (destination !== null && destination.equals(patch.stub))
            Memory.patchCode(patch.target, patch.original.byteLength, code => code.writeByteArray(patch.original));
    }
});
