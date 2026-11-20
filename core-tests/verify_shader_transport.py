"""Execute production Slang transport helpers on the CPU using the bundled LLVM backend.

Usage: python core-tests/verify_shader_transport.py --slangc /path/to/slangc
CAUSTICA_SLANGC or slangc on PATH can also select the compiler. Only Python's standard library
and the full Slang distribution (including slang-llvm) are required; no GPU is used.
"""

from __future__ import annotations

import argparse
import ctypes
import os
from pathlib import Path
import shutil
import sys


TESTS = {
    "testCachedPrimaryRoundTrip": "cached payload fields, active flags, exact direction and sky radiance",
    "testCachedFrostedTransmission": "authored frosted-dielectric transmission after cache round trip",
    "testTerminalCelestialMis": "terminal celestial energy and continuing MIS complement",
    "testTerminalStaticEmitterMis": "terminal static-emitter radiance and reservoir target",
}


def run_probe(source: Path, includes: list[Path], tests: dict[str, str]) -> int:
    """Compile a shader probe and run its exported uint tests (zero means success)."""
    parser = argparse.ArgumentParser(description=run_probe.__doc__)
    parser.add_argument("--slangc", default=os.environ.get("CAUSTICA_SLANGC", "slangc"))
    options = parser.parse_args()
    executable = shutil.which(options.slangc)
    if executable is None:
        parser.error(f"Slang compiler not found: {options.slangc}")
    compiler = Path(executable).resolve()
    library_names = (
        ("slang-compiler.dll", "slang.dll") if os.name == "nt"
        else (("libslang-compiler.dylib", "libslang.dylib") if sys.platform == "darwin"
              else ("libslang-compiler.so", "libslang.so"))
    )
    candidates = [directory / name for directory in (compiler.parent, compiler.parent.parent / "lib")
                  for name in library_names]
    library_path = next((path for path in candidates if path.is_file()), None)
    if library_path is None:
        parser.error("Install the full Slang distribution, including its compiler and LLVM libraries")

    # Keep the Windows dependency search path alive until the compiler and JIT code are released.
    search_path = os.add_dll_directory(str(library_path.parent)) if os.name == "nt" else None
    slang = ctypes.CDLL(str(library_path))

    def bind(name, result, *arguments):
        function = getattr(slang, name)
        function.restype = result
        function.argtypes = arguments
        return function

    pointer = ctypes.c_void_p
    create_session = bind("spCreateSession", pointer, ctypes.c_char_p)
    destroy_session = bind("spDestroySession", None, pointer)
    create_request = bind("spCreateCompileRequest", pointer, pointer)
    destroy_request = bind("spDestroyCompileRequest", None, pointer)
    process_arguments = bind("spProcessCommandLineArguments", ctypes.c_int32,
                             pointer, ctypes.POINTER(ctypes.c_char_p), ctypes.c_int)
    compile_request = bind("spCompile", ctypes.c_int32, pointer)
    diagnostics = bind("spGetDiagnosticOutput", ctypes.c_char_p, pointer)
    get_library = bind("spGetTargetHostCallable", ctypes.c_int32,
                       pointer, ctypes.c_int, ctypes.POINTER(pointer))

    arguments = [str(source),
                 *(value for directory in includes for value in ("-I", str(directory))),
                 "-target", "host-callable", "-emit-cpu-via-llvm",
                 "-matrix-layout-column-major", "-warnings-as-errors", "all",
                 "-warnings-disable", "41012"]
    encoded = (ctypes.c_char_p * len(arguments))(*(value.encode("utf-8") for value in arguments))
    session = create_session(None)
    if not session:
        raise RuntimeError("Slang session creation failed")
    request = None
    library = pointer()
    try:
        request = create_request(session)
        if not request:
            raise RuntimeError("Slang compile request creation failed")
        if process_arguments(request, encoded, len(arguments)) < 0:
            raise RuntimeError((diagnostics(request) or b"Invalid Slang arguments").decode("utf-8"))
        if compile_request(request) < 0 or get_library(request, 0, ctypes.byref(library)) < 0:
            raise RuntimeError((diagnostics(request) or b"Slang LLVM compilation failed").decode("utf-8"))
        messages = diagnostics(request)
        if messages:
            print(messages.decode("utf-8"), file=sys.stderr, end="")

        # ISlangSharedLibrary inherits IUnknown's three methods and ISlangCastable::castAs;
        # findSymbolAddressByName is the next virtual method in the public Slang ABI.
        vtable = ctypes.cast(library, ctypes.POINTER(ctypes.POINTER(pointer))).contents
        method = ctypes.WINFUNCTYPE if os.name == "nt" else ctypes.CFUNCTYPE
        find_symbol = method(pointer, pointer, ctypes.c_char_p)(vtable[4])
        failed = False
        for name, description in tests.items():
            address = find_symbol(library, name.encode("ascii"))
            if not address:
                raise RuntimeError(f"Missing shader test export: {name}")
            result = ctypes.CFUNCTYPE(ctypes.c_uint32)(address)()
            print(f"{'FAIL' if result else 'PASS'} {description}" + (f" (mask {result})" if result else ""))
            failed |= result != 0
        return 1 if failed else 0
    finally:
        if library.value:
            vtable = ctypes.cast(library, ctypes.POINTER(ctypes.POINTER(pointer))).contents
            method = ctypes.WINFUNCTYPE if os.name == "nt" else ctypes.CFUNCTYPE
            method(ctypes.c_uint32, pointer)(vtable[2])(library)
        if request:
            destroy_request(request)
        destroy_session(session)
        if search_path is not None:
            search_path.close()


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    raise SystemExit(run_probe(root / "core-tests/src/shaders/transport_regression.slang",
                               [root / "shaders/pipelines/world", root / "shaders/common"], TESTS))
