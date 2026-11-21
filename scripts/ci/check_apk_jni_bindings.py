#!/usr/bin/env python3
"""R5-3 (docs/architecture/0006): every native method R8 kept in an APK still binds.

R8 renames or removes what only JNI reaches unless a keep rule says otherwise. An emulator on a
CI runner is x86_64, where the APK leaves out the ARM-only libraries (offline translation,
ONNX Runtime, OpenCV), and starting the app does not load them anyway. So this reads the APK
itself: for every `native` method left in its dex files it looks for the `Java_...` symbol the
JVM would bind it to in that ABI's libraries, and fails when one is missing (a renamed class or
method, or a library left out).

Usage: check_apk_jni_bindings.py APK DEXDUMP [ABI]   (ABI defaults to arm64-v8a)
"""
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# Native methods that bind without an exported `Java_*` symbol, each with the reason.
NOT_BOUND_BY_NAME = (
    # libandroidx.graphics.path.so registers its methods from JNI_OnLoad (RegisterNatives).
    "androidx.graphics.path.",
    # Declared by OpenCV's Java API but absent from libopencv_java4 upstream; OpenCVLoader calls
    # it only inside its own UnsatisfiedLinkError fallback.
    "org.opencv.android.StaticHelper.getLibraryList",
)


def defined_jni_symbols(library: bytes) -> set[str]:
    """`Java_*` functions an ELF64 little-endian shared library defines in its dynamic table."""
    if library[:4] != b"\x7fELF" or library[4] != 2 or library[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF library")
    shoff, = struct.unpack_from("<Q", library, 0x28)
    shentsize, shnum = struct.unpack_from("<HH", library, 0x3A)
    sections = [struct.unpack_from("<IIQQQQIIQQ", library, shoff + i * shentsize) for i in range(shnum)]
    names = set()
    for _, kind, _, _, offset, size, link, _, _, entsize in sections:
        if kind != 11:  # SHT_DYNSYM
            continue
        strings = sections[link]
        string_offset = strings[4]
        for at in range(offset, offset + size, entsize):
            name_at, info, _, shndx, _, _ = struct.unpack_from("<IBBHQQ", library, at)
            if shndx == 0 or info & 0xF != 2:  # undefined, or not STT_FUNC
                continue
            end = library.index(b"\0", string_offset + name_at)
            name = library[string_offset + name_at:end].decode()
            if name.startswith("Java_"):
                names.add(name)
    return names


def jni_mangle(text: str) -> str:
    """The JNI encoding of a class or method name, or of a parameter descriptor."""
    out = []
    for char in text:
        if char in "./":
            out.append("_")
        elif char == "_":
            out.append("_1")
        elif char == ";":
            out.append("_2")
        elif char == "[":
            out.append("_3")
        elif char.isascii() and char.isalnum():
            out.append(char)
        else:
            out.append("_0%04x" % ord(char))
    return "".join(out)


def native_methods(dexdump: str, dex_files: list[Path]) -> list[tuple[str, str, str]]:
    """(class, method, parameter descriptor) of every native method in [dex_files], classes dotted."""
    found = []
    for dex in dex_files:
        output = subprocess.run([dexdump, str(dex)], check=True, capture_output=True).stdout.decode("utf-8", "replace")
        current_class = None
        name = None
        parameters = None
        for line in output.splitlines():
            line = line.strip()
            match = re.match(r"Class descriptor\s*:\s*'L(.+);'", line)
            if match:
                current_class = match.group(1).replace("/", ".")
                continue
            match = re.match(r"name\s*:\s*'(.+)'", line)
            if match:
                name = match.group(1)
                parameters = None
                continue
            match = re.match(r"type\s*:\s*'\((.*)\).*'", line)
            if match:
                parameters = match.group(1)
                continue
            if line.startswith("access") and "NATIVE" in line and current_class and name and parameters is not None:
                found.append((current_class, name, parameters))
    return found


def main() -> int:
    apk, dexdump = sys.argv[1], sys.argv[2]
    abi = sys.argv[3] if len(sys.argv) > 3 else "arm64-v8a"
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
        libraries = [n for n in archive.namelist() if n.startswith(f"lib/{abi}/") and n.endswith(".so")]
        if not libraries:
            print(f"::error title=JNI bindings::{apk} has no {abi} libraries")
            return 1
        symbols = set()
        for library in libraries:
            symbols |= defined_jni_symbols(archive.read(library))
        dex_files = []
        for entry in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", entry):
                target = Path(temp) / entry
                target.write_bytes(archive.read(entry))
                dex_files.append(target)
        methods = native_methods(dexdump, dex_files)
    # Nothing inspected is not a pass: no dex, or dexdump output this parser no longer reads.
    if not dex_files or not methods:
        print(f"::error title=JNI bindings::No native methods were read from {apk} ({len(dex_files)} dex files)")
        return 1
    missing = []
    for owner, method, parameters in methods:
        if f"{owner}.{method}".startswith(NOT_BOUND_BY_NAME):
            continue
        short = f"Java_{jni_mangle(owner)}_{jni_mangle(method)}"
        # The JVM tries the short name, then the long one with this overload's own parameters.
        long = f"{short}__{jni_mangle(parameters)}"
        if short not in symbols and long not in symbols:
            missing.append(f"{owner}.{method}({parameters})")
    print(f"{len(methods)} native methods in the dex, {len(symbols)} JNI symbols in {len(libraries)} {abi} libraries")
    if missing:
        print("::error title=JNI bindings::Native methods with no JNI symbol in the APK (renamed by R8, or a library is missing):")
        for item in sorted(missing):
            print(f"  {item}")
        return 1
    print("Every native method binds to a symbol.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
