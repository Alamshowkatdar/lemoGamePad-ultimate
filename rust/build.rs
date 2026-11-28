fn main() {
    // The ViGEm client is only needed by the Windows pad backend. Building it
    // into the EXE avoids a separate ViGEmClient.dll at runtime.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }

    let root = std::path::Path::new("..").join("vendor/vigembus/client");
    for path in [
        "src/ViGEmClient.cpp",
        "src/Internal.h",
        "src/UniUtil.h",
        "include/ViGEm/Client.h",
        "include/ViGEm/Common.h",
        "include/ViGEm/km/BusShared.h",
    ] {
        println!("cargo:rerun-if-changed={}", root.join(path).display());
    }

    let mut build = cc::Build::new();
    build
        .cpp(true)
        .file(root.join("src/ViGEmClient.cpp"))
        .include(root.join("include"))
        .include(root.join("src"))
        .define("WIN32_LEAN_AND_MEAN", None)
        .define("_LIB", None)
        .flag_if_supported("/EHsc");

    let env = std::env::var("CARGO_CFG_TARGET_ENV").unwrap_or_default();
    if env == "msvc" {
        // Match Rust's dynamic CRT instead of the upstream project's /MT.
        build.flag_if_supported("/MD");
    }
    build.compile("vigemclient");

    println!("cargo:rustc-link-lib=dylib=setupapi");
    if env == "gnu" {
        println!("cargo:rustc-link-lib=dylib=stdc++");
    }
}
