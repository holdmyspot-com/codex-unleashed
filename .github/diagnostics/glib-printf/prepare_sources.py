#!/usr/bin/env python3
"""Prepare pinned native voice sources offline; never execute archive contents."""

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tarfile

MANIFEST = Path(__file__).with_name("sources.json")
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_SOURCE_BYTES = 512 * 1024 * 1024
MAX_MEMBERS = 100_000


@dataclass(frozen=True)
class Source:
    name: str
    version: str
    role: str
    archive: str
    root: str
    url: str
    sha256: str
    provenance: str


def load_sources(manifest: bytes) -> list[Source]:
    document = json.loads(manifest)
    if document["schema_version"] != 1:
        raise ValueError("Unsupported native source manifest version")
    sources = [Source(**entry) for entry in document["sources"]]
    seen = set()
    for source in sources:
        for field in (source.name, source.archive, source.root):
            if not re.fullmatch(r"[a-zA-Z0-9_-][a-zA-Z0-9_.-]*", field):
                raise ValueError(f"Invalid native source identifier: {field!r}")
        if not re.fullmatch(r"[0-9a-f]{64}", source.sha256):
            raise ValueError(f"Invalid SHA-256 for {source.name}")
        for kind, value in (
            ("name", source.name),
            ("archive", source.archive),
            ("root", source.root),
        ):
            key = (kind, value.casefold())
            if key in seen:
                raise ValueError(f"Duplicate native source {kind}: {value}")
            seen.add(key)
    if not sources:
        raise ValueError("Native source manifest is empty")
    return sources


def extract_source(source: Source, archives: Path, destination: Path) -> None:
    # Use one immutable byte snapshot for both verification and extraction.
    with (archives / source.archive).open("rb") as archive:
        data = archive.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError(f"Archive exceeds size limit: {source.name}")
    if hashlib.sha256(data).hexdigest() != source.sha256:
        raise ValueError(f"SHA-256 mismatch: {source.name}")
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        members = []
        size = 0
        for member in archive:
            path = PurePosixPath(member.name)
            if path.parts[:1] != (source.root,) or ".." in path.parts:
                raise ValueError(f"Invalid archive path: {member.name!r}")
            size += member.size
            members.append(member)
            if size > MAX_SOURCE_BYTES or len(members) > MAX_MEMBERS:
                raise ValueError(f"Expanded source exceeds limits: {source.name}")
        if not members:
            raise ValueError(f"Empty source archive: {source.name}")
        # Bound Python's fallback copies when Windows cannot create archive links.
        links = sum(member.issym() or member.islnk() for member in members)
        if size + links * max(member.size for member in members) > MAX_SOURCE_BYTES:
            raise ValueError(f"Expanded source exceeds limits: {source.name}")
        archive.extractall(destination, members=members, filter="data")

    metadata_templates = {
        ("libffi", "3.8.0"): ("libffi.pc.in", "@prefix@"),
        ("pcre2", "10.47"): ("libpcre2-8.pc.in", "@prefix@"),
        ("zlib", "1.3.2"): ("zlib.pc.cmakein", "@CMAKE_INSTALL_PREFIX@"),
    }
    if (source.name, source.version) in metadata_templates:
        # WORKAROUND: these pinned dependencies embed the temporary install prefix.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when all three producers emit relocatable pkg-config metadata.
        relative_path, prefix_variable = metadata_templates[
            (source.name, source.version)
        ]
        metadata = destination / source.root / relative_path
        contents = metadata.read_text(encoding="utf-8")
        replacements = [(f"prefix={prefix_variable}", "prefix=${pcfiledir}/../..")]
        if source.name == "zlib":
            replacements.append(
                ("exec_prefix=@CMAKE_INSTALL_PREFIX@", "exec_prefix=${prefix}")
            )
        for original, replacement in replacements:
            contents, count = re.subn(
                "^" + re.escape(original) + "$",
                replacement,
                contents,
                flags=re.MULTILINE,
            )
            if count != 1:
                raise ValueError(
                    f"Pinned pkg-config template does not match: {source.name}"
                )
        metadata.write_text(contents, encoding="utf-8")

    if source.name == "glib" and source.version == "2.88.3":
        # WORKAROUND: https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Invalid C escapes and deprecated sprintf break the positional printf probe.
        # Remove when GLib's probe compiles with warnings treated as errors.
        meson = destination / source.root / "meson.build"
        contents = meson.read_text(encoding="utf-8")
        original = r'  sprintf (buffer, "%2\$d %3\$d %1\$d", 1, 2, 3);'
        replacement = '  snprintf (buffer, sizeof buffer, "%2$d %3$d %1$d", 1, 2, 3);'
        if contents.count(original) != 1:
            raise ValueError("Pinned GLib positional printf probe does not match")
        meson.write_text(contents.replace(original, replacement), encoding="utf-8")

        # WORKAROUND: GLib's bundled gnulib has incomplete warning portability.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when its printf fallback uses the portable annotation and casts.
        printf = destination / source.root / "glib/gnulib/vasnprintf.c"
        contents = printf.read_text(encoding="utf-8")
        replacements = (
            (
                "#ifndef FALLTHROUGH\n"
                "# if __GNUC__ < 7\n"
                "#  define FALLTHROUGH ((void) 0)\n"
                "# else\n"
                "#  define FALLTHROUGH __attribute__ ((__fallthrough__))\n"
                "# endif\n"
                "#endif",
                "#ifndef FALLTHROUGH\n# define FALLTHROUGH G_GNUC_FALLTHROUGH\n#endif",
            ),
            (
                "#if !USE_SNPRINTF\n                    if (count >= tmp_length)",
                "#if !USE_SNPRINTF\n                    if ((unsigned int) count >= tmp_length)",
            ),
            (
                "if (count > allocated - length)",
                "if ((unsigned int) count > allocated - length)",
            ),
        )
        for original, replacement in replacements:
            if contents.count(original) != 1:
                raise ValueError("Pinned GLib printf implementation does not match")
            contents = contents.replace(original, replacement)
        printf.write_text(contents, encoding="utf-8")

        # WORKAROUND: backport GLib's log-writer data cleanup.
        # https://github.com/GNOME/glib/commit/e7592a3c4ed4f3ef97f9efb804ad9637a3d4ca1c
        # Remove when the selected GLib release includes this commit.
        messages = destination / source.root / "glib/gmessages.c"
        contents = messages.read_text(encoding="utf-8")
        original = (
            "  log_writer_func = func;\n"
            "  log_writer_user_data = user_data;\n"
            "  log_writer_user_data_free = user_data_free;"
        )
        replacement = (
            "  if (log_writer_user_data_free != NULL && log_writer_user_data != NULL)\n"
            "    log_writer_user_data_free (log_writer_user_data);\n\n" + original
        )
        if contents.count(original) != 1:
            raise ValueError("Pinned GLib log-writer implementation does not match")
        messages.write_text(contents.replace(original, replacement), encoding="utf-8")

        # WORKAROUND: backport GLib's Clang header compatibility fixes.
        # https://github.com/GNOME/glib/commit/997154cecc465bd399b467cc5c6cb7c88c43559d
        # https://gitlab.gnome.org/GNOME/glib/-/issues/4038
        # Remove when the selected GLib release includes both upstream fixes.
        header_fixes = (
            (
                "glib/glib-private.h",
                "return G_UNLIKELY (__lsan_enable != NULL && __lsan_ignore_object != NULL);",
                "return !!G_UNLIKELY (__lsan_enable != NULL && __lsan_ignore_object != NULL);",
            ),
            (
                "gobject/gtype.h",
                "static gpointer type_name##_parent_class = NULL;",
                "G_GNUC_UNUSED static gpointer type_name##_parent_class = NULL;",
            ),
        )
        for relative_path, original, replacement in header_fixes:
            header = destination / source.root / relative_path
            contents = header.read_text(encoding="utf-8")
            if contents.count(original) != 1:
                raise ValueError(f"Pinned GLib header does not match: {relative_path}")
            header.write_text(contents.replace(original, replacement), encoding="utf-8")

        # WORKAROUND: GLib trace counters are optional without sysprof support.
        # https://github.com/GNOME/glib/commit/168e378526a590439faa9ce7b607203fd434ff1d
        # Remove when the selected GLib release includes this upstream fix.
        task = destination / source.root / "gio/gtask.c"
        contents = task.read_text(encoding="utf-8")
        original = (
            "static guint task_pool_max_counter;\nstatic guint tasks_running_counter;"
        )
        replacement = (
            "static guint task_pool_max_counter G_GNUC_UNUSED;\n"
            "static guint tasks_running_counter G_GNUC_UNUSED;"
        )
        if contents.count(original) != 1:
            raise ValueError("Pinned GLib task trace counters do not match")
        task.write_text(contents.replace(original, replacement), encoding="utf-8")

        # WORKAROUND: remove the unused storage identified by GLib upstream.
        # https://github.com/GNOME/glib/commit/11de1d730e58c1f501e3a5d4c65b283fb8a26e0f
        # Preserve the pinned release's accepted -s option and help text.
        # Remove when the selected GLib release includes the upstream fix.
        query = destination / source.root / "gobject/gobject-query.c"
        contents = query.read_text(encoding="utf-8")
        for original, replacement in (
            ("static guint spacing = 1;\n", ""),
            ("spacing = atoi (argv[i]);", "(void) atoi (argv[i]);"),
        ):
            if contents.count(original) != 1:
                raise ValueError(
                    "Pinned GLib query spacing implementation does not match"
                )
            contents = contents.replace(original, replacement)
        query.write_text(contents, encoding="utf-8")

    if (
        source.name in ("gst-plugins-base", "gst-plugins-good")
        and source.version == "1.28.6"
    ):
        # WORKAROUND: explicit Orc disablement selects the supported C fallback.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer distinguishes this choice from a missing Orc.
        meson = destination / source.root / "meson.build"
        contents = meson.read_text(encoding="utf-8")
        original = "  warning('Orc not found or disabled, will use backup C code')"
        replacement = (
            "  if get_option('orc').disabled()\n"
            "    message('Orc explicitly disabled; using backup C code')\n"
            "  else\n" + original + "\n  endif"
        )
        if contents.count(original) != 1:
            raise ValueError("Pinned GStreamer Orc fallback diagnostic does not match")
        meson.write_text(contents.replace(original, replacement), encoding="utf-8")

    if source.name == "gst-plugins-base" and source.version == "1.28.6":
        # WORKAROUND: performance logging shares the optional Linux allocator guard.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer guards this category with its only user.
        allocator = (
            destination / source.root / "gst-libs/gst/allocators/gstudmabufallocator.c"
        )
        contents = allocator.read_text(encoding="utf-8")
        condition = "#if defined(HAVE_MEMFD_CREATE) && defined(HAVE_LINUX_UDMABUF_H)"
        for original, replacement in (
            (
                "GST_DEBUG_CATEGORY_STATIC (GST_CAT_PERFORMANCE);",
                condition
                + "\nGST_DEBUG_CATEGORY_STATIC (GST_CAT_PERFORMANCE);\n#endif",
            ),
            (
                "#define parent_class gst_udmabuf_allocator_parent_class",
                "static void\n"
                "gst_udmabuf_allocator_init_performance_category (void)\n"
                "{\n"
                + condition
                + '\n  GST_DEBUG_CATEGORY_GET (GST_CAT_PERFORMANCE, "GST_PERFORMANCE");\n'
                "#endif\n}\n\n"
                "#define parent_class gst_udmabuf_allocator_parent_class",
            ),
            (
                '    GST_DEBUG_CATEGORY_GET (GST_CAT_PERFORMANCE, "GST_PERFORMANCE");',
                "    gst_udmabuf_allocator_init_performance_category ();",
            ),
        ):
            if contents.count(original) != 1:
                raise ValueError("Pinned GStreamer allocator category does not match")
            contents = contents.replace(original, replacement)
        allocator.write_text(contents, encoding="utf-8")

        # WORKAROUND: these media metadata quark caches have no remaining users.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer removes the unused caches upstream.
        for media in ("audio", "video"):
            tag = media.upper()
            for role in ("decoder", "encoder"):
                name = f"gst{media}{role}.c"
                implementation = (
                    destination / source.root / "gst-libs/gst" / media / name
                )
                contents = implementation.read_text(encoding="utf-8")
                for original, replacement in (
                    (f"#define META_TAG_{tag} meta_tag_{media}_quark\n", ""),
                    (f"static GQuark meta_tag_{media}_quark;\n", ""),
                    (
                        f"meta_tag_{media}_quark = g_quark_from_static_string (GST_META_TAG_{tag}_STR);",
                        f"(void) g_quark_from_static_string (GST_META_TAG_{tag}_STR);",
                    ),
                ):
                    if contents.count(original) != 1:
                        raise ValueError(
                            f"Pinned GStreamer metadata quark does not match: {name}"
                        )
                    contents = contents.replace(original, replacement)
                implementation.write_text(contents, encoding="utf-8")

        # WORKAROUND: glib-mkenums parses enum expressions one line at a time.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when the pinned generator accepts these multiline expressions.
        enum_values = (
            (
                "rtp/gstrtphdrext.h",
                "GST_RTP_HEADER_EXTENSION_DIRECTION_SENDRECV = (\n"
                "    GST_RTP_HEADER_EXTENSION_DIRECTION_SENDONLY |\n"
                "    GST_RTP_HEADER_EXTENSION_DIRECTION_RECVONLY),",
            ),
            (
                "video/navigation.h",
                "GST_NAVIGATION_MODIFIER_MASK = (\n"
                "    GST_NAVIGATION_MODIFIER_NONE          |\n"
                "    GST_NAVIGATION_MODIFIER_SHIFT_MASK    |\n"
                "    GST_NAVIGATION_MODIFIER_LOCK_MASK     |\n"
                "    GST_NAVIGATION_MODIFIER_CONTROL_MASK  |\n"
                "    GST_NAVIGATION_MODIFIER_MOD1_MASK      |\n"
                "    GST_NAVIGATION_MODIFIER_MOD2_MASK      |\n"
                "    GST_NAVIGATION_MODIFIER_MOD3_MASK      |\n"
                "    GST_NAVIGATION_MODIFIER_MOD4_MASK      |\n"
                "    GST_NAVIGATION_MODIFIER_MOD5_MASK      |\n"
                "    GST_NAVIGATION_MODIFIER_BUTTON1_MASK  |\n"
                "    GST_NAVIGATION_MODIFIER_BUTTON2_MASK  |\n"
                "    GST_NAVIGATION_MODIFIER_BUTTON3_MASK  |\n"
                "    GST_NAVIGATION_MODIFIER_BUTTON4_MASK  |\n"
                "    GST_NAVIGATION_MODIFIER_BUTTON5_MASK  |\n"
                "    GST_NAVIGATION_MODIFIER_SUPER_MASK    |\n"
                "    GST_NAVIGATION_MODIFIER_HYPER_MASK    |\n"
                "    GST_NAVIGATION_MODIFIER_META_MASK\n  )",
            ),
        )
        for relative_path, original in enum_values:
            header = destination / source.root / "gst-libs/gst" / relative_path
            contents = header.read_text(encoding="utf-8")
            if contents.count(original) != 1:
                raise ValueError(
                    f"Pinned GStreamer enum does not match: {relative_path}"
                )
            header.write_text(
                contents.replace(original, " ".join(original.split())), encoding="utf-8"
            )

        # WORKAROUND: audioconvert's performance category has no logging callers.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer removes this unused category upstream.
        converter = destination / source.root / "gst/audioconvert/gstaudioconvert.c"
        contents = converter.read_text(encoding="utf-8")
        for original in (
            "GST_DEBUG_CATEGORY_STATIC (GST_CAT_PERFORMANCE);\n",
            '  GST_DEBUG_CATEGORY_GET (GST_CAT_PERFORMANCE, "GST_PERFORMANCE");',
        ):
            if contents.count(original) != 1:
                raise ValueError(
                    "Pinned GStreamer audio converter category does not match"
                )
            contents = contents.replace(original, "")
        converter.write_text(contents, encoding="utf-8")

    if source.name == "gst-plugins-good" and source.version == "1.28.6":
        # WORKAROUND: disabled documentation does not require a cache generator.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer distinguishes this choice from failed discovery.
        documentation = destination / source.root / "docs/meson.build"
        contents = documentation.read_text(encoding="utf-8")
        original = (
            "else\n"
            "    warning('GStreamer plugin inspector for documentation not found, "
            "can\\'t update the cache')\n"
            "endif"
        )
        replacement = (
            "elif get_option('doc').disabled()\n"
            "    message('Documentation disabled; not updating plugin cache')\n"
            + original
        )
        if contents.count(original) != 1:
            raise ValueError("Pinned GStreamer documentation diagnostic does not match")
        documentation.write_text(
            contents.replace(original, replacement), encoding="utf-8"
        )

    if source.name == "gstreamer" and source.version == "1.28.6":
        # WORKAROUND: these pad quarks are interned but their storage is unused.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer no longer stores these unused probe quarks.
        pad = destination / source.root / "gst/gstpad.c"
        contents = pad.read_text(encoding="utf-8")
        for name in ("buffer_quark", "buffer_list_quark", "event_quark"):
            declaration = f"static GQuark {name};\n"
            assignment = f"{name} = g_quark_from_static_string"
            if contents.count(declaration) != 1 or contents.count(assignment) != 1:
                raise ValueError(f"Pinned GStreamer pad quark does not match: {name}")
            contents = contents.replace(declaration, "")
            contents = contents.replace(assignment, "(void) g_quark_from_static_string")
        pad.write_text(contents, encoding="utf-8")

        # WORKAROUND: the preload callback shares its caller's feature guard.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when GStreamer guards this option-parsing-only implementation.
        registry = destination / source.root / "gst/gstregistry.c"
        contents = registry.read_text(encoding="utf-8")
        for original, replacement in (
            (
                "static void\nload_plugin_func (",
                "#ifndef GST_DISABLE_OPTION_PARSING\nstatic void\nload_plugin_func (",
            ),
            (
                "}\n\nchar *\npriv_gst_get_relocated_libgstreamer",
                "}\n#endif\n\nchar *\npriv_gst_get_relocated_libgstreamer",
            ),
        ):
            if contents.count(original) != 1:
                raise ValueError("Pinned GStreamer preload callback does not match")
            contents = contents.replace(original, replacement)
        registry.write_text(contents, encoding="utf-8")

    if source.name == "ninja" and source.version == "1.13.2":
        # WORKAROUND: the pinned Ninja lacks an explicit bundled-lexer mode.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when the selected Ninja provides this build option upstream.
        cmake = destination / source.root / "CMakeLists.txt"
        contents = cmake.read_text(encoding="utf-8")
        original = (
            "set(RE2C_MAJOR_VERSION 0)\n"
            "find_program(RE2C re2c)\n"
            "if(RE2C)\n"
            '\texecute_process(COMMAND "${RE2C}" --vernum OUTPUT_VARIABLE RE2C_RAW_VERSION)\n'
            '\tmath(EXPR RE2C_MAJOR_VERSION "${RE2C_RAW_VERSION} / 10000")\n'
            "endif()\n"
            "if(${RE2C_MAJOR_VERSION} GREATER 1)\n"
        )
        replacement = (
            'option(NINJA_REGENERATE_LEXERS "Regenerate lexer sources with re2c" ON)\n'
            "set(RE2C_MAJOR_VERSION 0)\n"
            "if(NINJA_REGENERATE_LEXERS)\n"
            "\tfind_program(RE2C re2c)\n"
            "\tif(RE2C)\n"
            '\t\texecute_process(COMMAND "${RE2C}" --vernum OUTPUT_VARIABLE RE2C_RAW_VERSION)\n'
            '\t\tmath(EXPR RE2C_MAJOR_VERSION "${RE2C_RAW_VERSION} / 10000")\n'
            "\tendif()\n"
            "endif()\n"
            "if(NOT NINJA_REGENERATE_LEXERS)\n"
            "\tforeach(lexer depfile_parser lexer)\n"
            '\t\tif(NOT EXISTS "${PROJECT_SOURCE_DIR}/src/${lexer}.cc")\n'
            '\t\t\tmessage(FATAL_ERROR "Missing bundled Ninja lexer: ${lexer}")\n'
            "\t\tendif()\n"
            "\tendforeach()\n"
            '\tmessage(STATUS "Using bundled Ninja lexers; regeneration is disabled")\n'
            "\tadd_library(libninja-re2c OBJECT src/depfile_parser.cc src/lexer.cc)\n"
            "elseif(${RE2C_MAJOR_VERSION} GREATER 1)\n"
        )
        if contents.count(original) != 1:
            raise ValueError("Pinned Ninja lexer configuration does not match")
        cmake.write_text(contents.replace(original, replacement), encoding="utf-8")

        # WORKAROUND: Ninja's browse implementation requires Unix process APIs.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when Ninja excludes Windows from its Unix browse probes.
        contents = cmake.read_text(encoding="utf-8")
        original = "function(check_platform_supports_browse_mode RESULT)\n"
        replacement = (
            original
            + "\tif(WIN32)\n"
            + '\t\tset(${RESULT} "0" PARENT_SCOPE)\n'
            + "\t\treturn()\n"
            + "\tendif()\n"
        )
        if contents.count(original) != 1:
            raise ValueError("Pinned Ninja browse configuration does not match")
        cmake.write_text(contents.replace(original, replacement), encoding="utf-8")

    if source.name == "libffi" and source.version == "3.8.0":
        # WORKAROUND: libffi calls its deprecated public Java raw API internally.
        # https://github.com/holdmyspot-com/codex-unleashed/issues/61
        # Remove when libffi uses internal helpers for these entry points.
        java_raw = destination / source.root / "src/java_raw_api.c"
        contents = java_raw.read_text(encoding="utf-8")
        functions = (
            ("ffi_java_raw_size", "size_t", "ffi_cif *cif", "cif"),
            (
                "ffi_java_raw_to_ptrarray",
                "void",
                "ffi_cif *cif, ffi_java_raw *raw, void **args",
                "cif, raw, args",
            ),
            (
                "ffi_java_ptrarray_to_raw",
                "void",
                "ffi_cif *cif, void **args, ffi_java_raw *raw",
                "cif, args, raw",
            ),
            (
                "ffi_prep_java_raw_closure_loc",
                "ffi_status",
                "ffi_java_raw_closure *cl, ffi_cif *cif, "
                "void (*fun)(ffi_cif*,void*,ffi_java_raw*,void*), "
                "void *user_data, void *codeloc",
                "cl, cif, fun, user_data, codeloc",
            ),
        )
        for name, _, _, _ in functions:
            contents = contents.replace(f"{name} (", f"{name}_internal (")

        for name, return_type, parameters, arguments in functions:
            definition = f"{return_type}\n{name}_internal ("
            if contents.count(definition) != 1:
                raise ValueError(f"Pinned libffi definition does not match: {name}")
            return_statement = "" if return_type == "void" else "return "
            wrapper = (
                f"static {return_type} {name}_internal ({parameters});\n\n"
                f"{return_type}\n{name} ({parameters})\n"
                "{\n"
                f"  {return_statement}{name}_internal ({arguments});\n"
                "}\n\n"
                f"static {definition}"
            )
            contents = contents.replace(definition, wrapper)

        java_raw.write_text(contents, encoding="utf-8")


def prepare_sources(archives: Path, output: Path, manifest: bytes) -> None:
    sources = load_sources(manifest)
    # Claim a new directory exclusively; never reuse or overwrite an earlier build.
    output.mkdir()
    try:
        for source in sources:
            extract_source(source, archives, output)
        receipt = {
            "schema_version": 1,
            "manifest_sha256": hashlib.sha256(manifest).hexdigest(),
            "sources": {
                source.name: {"root": source.root, "sha256": source.sha256}
                for source in sources
            },
        }
        (output / "sources.json").write_bytes(manifest)
        # This is the completion marker; failures never leave a valid receipt.
        (output / "prepared.json").write_text(
            json.dumps(receipt, indent=2) + "\n", encoding="utf-8"
        )
    except BaseException:
        shutil.rmtree(output)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--archives",
        required=True,
        type=Path,
        help="Directory containing the pinned archives",
    )
    parser.add_argument(
        "--output", required=True, type=Path, help="New directory for verified sources"
    )
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        parser.error("Native source preparation requires Python 3.12 or newer")
    prepare_sources(args.archives, args.output, MANIFEST.read_bytes())


if __name__ == "__main__":
    main()
