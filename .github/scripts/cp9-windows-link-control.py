#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import uuid

MACHINE = 0x8664


def digest(file):
    return hashlib.file_digest(file, "sha256").hexdigest()

def read(file, offset, size):
    file.seek(offset)
    data = file.read(size)
    assert len(data) == size
    return data

def unpack(file, offset, form):
    return struct.unpack(form, read(file, offset, struct.calcsize(form)))

def pe(file):
    assert read(file, 0, 2) == b"MZ"
    (header,) = unpack(file, 60, "<I")
    assert read(file, header, 4) == b"PE\0\0"
    machine, sections = unpack(file, header + 4, "<HH")
    assert machine == MACHINE
    (opt_size,) = unpack(file, header + 20, "<H")
    opt = header + 24
    assert unpack(file, opt, "<H") == (0x20B,)
    debug_rva, debug_size = unpack(file, opt + 112 + 6 * 8, "<II")
    mapping = []
    for index in range(sections):
        length, rva, size, offset = unpack(file, opt + opt_size + index * 40 + 8, "<IIII")
        mapping.append((rva, size, offset))
    offsets = [offset + debug_rva - rva for rva, size, offset in mapping
               if rva <= debug_rva and debug_rva + debug_size <= rva + size]
    assert len(offsets) == 1 and debug_size % 28 == 0
    codes = []
    for index in range(debug_size // 28):
        kind, length, _, offset = unpack(file, offsets[0] + 28 * index + 12, "<IIII")
        if kind != 2:
            continue
        code = read(file, offset, length)
        assert code.startswith(b"RSDS") and len(code) >= 25
        codes.append((str(uuid.UUID(bytes_le=code[4:20])), int.from_bytes(code[20:24], "little"),
                      code[24:].split(b"\0", 1)[0].decode(errors="replace").replace(chr(92), "/").split("/")[-1]))
    assert len(codes) == 1
    return codes[0]

def pdb(file):
    assert read(file, 0, 32) == b"Microsoft C/C++ MSF 7.00\r\n\x1aDS\0\0\0"
    block_size, _, num_blocks, dir_size, _, map_block = unpack(file, 32, "<IIIIII")
    assert 512 <= block_size <= 65536 and 0 < dir_size <= 16 * 1024 * 1024
    n_dir = (dir_size + block_size - 1) // block_size
    directory_blocks = unpack(file, map_block * block_size, f"<{n_dir}I")
    assert all(block < num_blocks for block in directory_blocks)
    directory = b"".join(read(file, block * block_size, block_size) for block in directory_blocks)[:dir_size]
    (streams,) = struct.unpack_from("<I", directory)
    assert 2 <= streams <= 1000000
    sizes = struct.unpack_from(f"<{streams}I", directory, 4)
    assert sizes[1] >= 28 and sizes[1] != 0xFFFFFFFF
    preceding = 0 if sizes[0] == 0xFFFFFFFF else (sizes[0] + block_size - 1) // block_size
    (info_block,) = struct.unpack_from("<I", directory, 4 * (1 + streams + preceding))
    assert info_block < num_blocks
    info = read(file, info_block * block_size, 28)
    return str(uuid.UUID(bytes_le=info[12:28])), struct.unpack_from("<I", info, 8)[0]

def pe_sections(file):
    (header,) = unpack(file, 60, "<I")
    _, sections, timestamp = unpack(file, header + 4, "<HHI")
    (opt_size,) = unpack(file, header + 20, "<H")
    opt = header + 24
    (checksum,) = unpack(file, opt + 64, "<I")
    debug_rva, debug_size = unpack(file, opt + 112 + 6 * 8, "<II")
    layout, debug_offset = {}, None
    header_hash = hashlib.sha256(read(file, 0, opt + opt_size + sections * 40)).hexdigest()
    for n in range(sections):
        i = opt + opt_size + n * 40
        name = read(file, i, 8).rstrip(b"\0").decode()
        virtual_size, rva, length, start = unpack(file, i + 8, "<IIII")
        layout[name] = (virtual_size, rva, length, start)
        if rva <= debug_rva and debug_rva + debug_size <= rva + length:
            debug_offset = start + debug_rva - rva
    assert debug_offset is not None
    ignore = [(debug_offset, debug_offset + debug_size)]
    for n in range(debug_size // 28):
        _, size, _, start = unpack(file, debug_offset + n * 28 + 12, "<IIII")
        if size and start:
            ignore.append((start, start + size))
    sections = {}
    for name, (virtual_size, rva, length, start) in layout.items():
        if not length:
            continue
        raw = bytearray(read(file, start, length))
        sha = hashlib.sha256(raw).hexdigest()
        for left, right in ignore:
            left = max(left, start)
            right = min(right, start + length)
            if left < right:
                raw[left-start:right-start] = bytes(right-left)
        sections[name] = {"raw_sha": sha, "without_debug_records_sha": hashlib.sha256(raw).hexdigest(),
                          "layout": (virtual_size, rva, length, start)}
    return {"timestamp": timestamp, "checksum": checksum, "header": header_hash, "sections": sections}

def pdb_streams(file):
    block_size, _, num_blocks, dir_size, _, map_block = unpack(file, 32, "<IIIIII")
    n_dir = (dir_size + block_size - 1) // block_size
    directory_blocks = unpack(file, map_block * block_size, f"<{n_dir}I")
    directory = b"".join(read(file, index * block_size, block_size) for index in directory_blocks)[:dir_size]
    (n_streams,) = struct.unpack_from("<I", directory)
    sizes = struct.unpack_from(f"<{n_streams}I", directory, 4)
    offset = 4 * (1 + n_streams)
    streams = {}
    for idx, size in enumerate(sizes):
        if size in (0, 0xFFFFFFFF):
            continue
        length = (size + block_size - 1) // block_size
        blocks = struct.unpack_from(f"<{length}I", directory, offset)
        offset += 4 * length
        assert all(block < num_blocks for block in blocks)
        sha = hashlib.sha256()
        for position, block in enumerate(blocks):
            sha.update(read(file, block * block_size, min(block_size, size - position * block_size)))
        streams[str(idx)] = [size, sha.hexdigest()]
    return streams

def sha(path):
    with path.open("rb") as file:
        return digest(file)


def capture(root, linker, source, natvis, basename, case):
    scratch = root / basename
    scratch.mkdir(exist_ok=True)
    obj, visualizer = scratch / "probe.obj", scratch / "probe.natvis"
    shutil.copyfile(source, obj)
    shutil.copyfile(natvis, visualizer)

    exe, symbols = root / "probe.exe", root / "probe.pdb"
    args = [str(linker), "-flavor", "link", "/nodefaultlib", "/entry:probe", "/subsystem:console",
            "/machine:x64", "/debug:full", "/Brepro", f"/out:{exe}", f"/pdb:{symbols}",
            f"/natvis:{visualizer}", str(obj)]
    subprocess.run(args, check=True, cwd=root)
    subprocess.run([str(exe)], check=True, cwd=root)
    with exe.open("rb") as file:
        pe_identity = pe(file)
        sections = pe_sections(file)
    with symbols.open("rb") as file:
        pdb_identity = pdb(file)
        streams = pdb_streams(file)
    assert pe_identity == (*pdb_identity, "probe.pdb")
    for product in (exe, symbols):
        shutil.move(product, root / "evidence" / f"{case}-{product.name}")
    return {"case": case, "basename": basename, "object_sha256": sha(obj), "natvis_sha256": sha(visualizer),
            "pe_sha256": sha(root / "evidence" / f"{case}-{exe.name}"),
            "pdb_sha256": sha(root / "evidence" / f"{case}-{symbols.name}"),
            "pe_identity": pe_identity, "pe_sections": sections, "pdb_streams": streams}


def compare(left, right):
    c, w = left["pe_sections"]["sections"], right["pe_sections"]["sections"]
    assert set(c) == set(w)
    cs, ws = left["pdb_streams"], right["pdb_streams"]
    return {
        "identical_inputs": [left[key] for key in ("object_sha256", "natvis_sha256")] ==
                            [right[key] for key in ("object_sha256", "natvis_sha256")],
        "pe_sha256_equal": left["pe_sha256"] == right["pe_sha256"],
        "pdb_sha256_equal": left["pdb_sha256"] == right["pdb_sha256"],
        "pe_headers_equal": left["pe_sections"]["header"] == right["pe_sections"]["header"],
        "pe_section_layout_equal": all(c[name]["layout"] == w[name]["layout"] for name in c),
        "changed_pe_sections": sorted(name for name in c if c[name]["raw_sha"] != w[name]["raw_sha"]),
        "changed_pe_sections_ignoring_debug_records": sorted(
            name for name in c if c[name]["without_debug_records_sha"] != w[name]["without_debug_records_sha"]),
        "changed_pdb_streams": sorted(int(name) for name in set(cs) | set(ws) if cs.get(name) != ws.get(name)),
    }


def main():
    root = Path(sys.argv[1]).resolve()
    evidence = root / "evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    compiler = ["rustc", "+1.99.1"]
    sysroot = Path(subprocess.check_output([*compiler, "--print", "sysroot"], text=True).strip())
    linker = sysroot / "lib/rustlib/x86_64-pc-windows-msvc/bin/rust-lld.exe"
    assert linker.is_file()
    version = subprocess.check_output([*compiler, "--version", "--verbose"], text=True).strip()
    assert "58cf8f9c3" in version and "host: x86_64-pc-windows-msvc" in version
    source, obj, natvis = root / "probe.rs", root / "input.obj", root / "input.natvis"
    source.write_text(
        '#![no_std]\n#![no_main]\n'
        '#[panic_handler]\nfn panic(_: &core::panic::PanicInfo) -> ! { loop {} }\n'
        '#[unsafe(no_mangle)]\npub extern "C" fn probe() -> i32 { 0 }\n',
        encoding="ascii",
    )
    natvis.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<AutoVisualizer xmlns="http://schemas.microsoft.com/vstudio/debugger/natvis/2010">\n'
        '  <Type Name="probe"><DisplayString>probe</DisplayString></Type>\n'
        '</AutoVisualizer>\n',
        encoding="ascii",
    )
    subprocess.run([*compiler, "--target", "x86_64-pc-windows-msvc", "--edition", "2024", "--crate-type", "lib",
                    "--emit=obj", "-Cpanic=abort", "-Cdebuginfo=2", "-Copt-level=3",
                    "-o", str(obj), str(source)], check=True, cwd=root)

    first, changed, replay = [capture(root, linker, obj, natvis, basename, case)
                              for basename, case in (("rustcAAAAAA", "first"), ("rustcBBBBBB", "changed"),
                                                     ("rustcAAAAAA", "replay"))]
    contrast = compare(first, changed)
    positive = compare(first, replay)
    result = {"rustc": version, "linker": str(linker), "linker_sha256": sha(linker),
              "comparison": contrast, "same_basename_replay": positive, "records": [first, changed, replay]}
    (evidence / "report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    assert contrast["identical_inputs"] and positive["identical_inputs"]
    assert contrast["changed_pdb_streams"], "Changed scratch basename did not change PDB streams"
    assert contrast["pe_section_layout_equal"] and not contrast["changed_pe_sections_ignoring_debug_records"]
    assert positive["pe_sha256_equal"] and positive["pdb_sha256_equal"], "The same scratch basename changed the output"


if __name__ == "__main__":
    main()
