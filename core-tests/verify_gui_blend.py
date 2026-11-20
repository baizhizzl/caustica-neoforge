"""Run the production HDR compositing helpers on the CPU without a game or GPU."""

from pathlib import Path

from verify_shader_transport import run_probe


if __name__ == "__main__":
    core_tests = Path(__file__).resolve().parent
    raise SystemExit(run_probe(
        core_tests / "src/shaders/gui_blend_regression.slang",
        [core_tests.parent / "shaders/pipelines/hdr_composite"],
        {
            "testVignetteAndCrosshair": "colored vignette and crosshair in SDR and HDR",
            "testOrderedClampAndMax": "additive saturation, inversion, and max preserve draw order",
            "testUiReconstructsTranslucencyAndExtremes": "premultiplied SDR UI, untouched pixels and extremes",
            "testUiReconstructsPqAfterLinearHdrCompositing": "premultiplied UI reconstructs the PQ backbuffer",
        },
    ))
