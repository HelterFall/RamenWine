#!/usr/bin/env python3
"""Build the first glibc + PRoot + Box64 + Wine 11 game runtime.

The build is performed on Ubuntu 24.04 x86-64. It creates an ARM64 Linux
rootfs with ARM64 Box64 and x86-64 Wine 11 WoW64, then packages it for the
Android app. This script does not build an APK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import package_runtime

UBUNTU_BASE_URL = (
    "https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/"
    "ubuntu-base-24.04.5-base-arm64.tar.gz"
)
UBUNTU_BASE_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
BOX64_REPOSITORY = "https://github.com/ptitSeb/box64.git"
BOX64_COMMIT = "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
WINE_URL = "https://dl.winehq.org/wine/source/11.0/wine-11.0.tar.xz"
WINE_SHA256 = "c07a6857933c1fc60dff5448d79f39c92481c1e9db5aa628db9d0358446e0701"
DEFAULT_SNAPSHOT = "20261109T000000Z"

SNAPSHOT_PATTERN = re.compile(r"^[0-9]{8}T[0-9]{6}Z$")
VERSION_PATTERN = re.compile(r"^[A-Za-z0-9._+-]{1,128}$")

HOST_PACKAGES = (
    "build-essential", "ca-certificates", "cmake", "curl", "flex", "bison",
    "gettext", "git", "g++-aarch64-linux-gnu", "gcc-aarch64-linux-gnu",
    "g++-mingw-w64-i686", "gcc-mingw-w64-i686",
    "g++-mingw-w64-x86-64", "gcc-mingw-w64-x86-64",
    "binutils", "coreutils", "cpio", "rpm2cpio", "unzip", "zstd", "which",
    "binutils-mingw-w64", "libasound2-dev", "libdbus-1-dev",
    "libegl-dev", "libfontconfig1-dev", "libfreetype-dev",
    "libgphoto2-dev", "libgnutls28-dev", "libgstreamer1.0-dev",
    "libgstreamer-plugins-base1.0-dev", "liblcms2-dev", "libldap2-dev",
    "libmpg123-dev", "libopenal-dev", "libpcap0.8-dev", "libpulse-dev",
    "libudev-dev", "libunwind-dev", "libusb-1.0-0-dev", "libvulkan-dev",
    "libwayland-dev", "libwayland-bin", "libxkbcommon-dev",
    "libxkbregistry-dev", "libzstd-dev", "libjpeg-dev",
    "libpng-dev", "libtiff-dev", "libwebp-dev", "libxml2-dev", "libxslt1-dev",
    "libgcrypt20-dev", "libgsm1-dev", "libavcodec-dev", "libsdl2-dev", "pkg-config",
    "wayland-protocols", "ninja-build",
)

RUNTIME_PACKAGES = (
    "libwine:amd64",
    "coreutils:arm64",
    "libc6:arm64",
    "libgcc-s1:arm64",
    "libstdc++6:arm64",
    "zlib1g:arm64",
    "libc6:amd64",
    "libegl1:amd64",
    "libgl1:amd64",
    "libvulkan1:amd64",
    "libwayland-egl1:amd64",
    "gstreamer1.0-plugins-base:amd64",
    "gstreamer1.0-plugins-good:amd64",
    "fonts-dejavu-core",
    "fonts-noto-cjk",
)

REQUIRED_TOOLS = (
    "apt-get", "cmake", "dpkg-deb", "git", "make", "tar",
    "aarch64-linux-gnu-gcc", "aarch64-linux-gnu-g++", "wayland-scanner",
    "i686-w64-mingw32-gcc", "x86_64-w64-mingw32-gcc",
    "awk", "cpio", "curl", "rpm2cpio", "sha256sum", "unzip", "which",
)

BOX64_REQUIRED_X64_LIBRARIES = (
    "libstdc++.so.6",
    "libgcc_s.so.1",
    "libunwind.so.8",
)

WINE_CONFIGURE_FLAGS = (
    "--prefix=/usr",
    "--bindir=/usr/bin",
    "--libdir=/usr/lib/x86_64-linux-gnu",
    "--enable-archs=i386,x86_64",
    "--with-wayland",
    "--without-x",
    "--disable-tests",
)


def run(command: list[str], cwd: Path | None = None, extra_env: dict[str, str] | None = None) -> None:
    print("+", " ".join(command), flush=True)
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def output(command: list[str]) -> str:
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()


def validate_arguments(snapshot: str, version: str, jobs: int) -> None:
    if not SNAPSHOT_PATTERN.fullmatch(snapshot):
        raise ValueError("snapshot must look like 20261109T000000Z")
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("version contains unsupported characters")
    if jobs < 1 or jobs > 32:
        raise ValueError("jobs must be between 1 and 32")


def require_build_host() -> None:
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        raise ValueError("The runtime builder requires an x86-64 Linux host.")
    try:
        release = platform.freedesktop_os_release()
    except (AttributeError, OSError):
        release = {}
    if release.get("ID") != "ubuntu" or release.get("VERSION_ID") != "24.04":
        raise ValueError("The runtime builder requires Ubuntu 24.04 x86-64.")


def apt_command() -> list[str]:
    if os.geteuid() == 0:
        return ["apt-get"]
    if shutil.which("sudo") is None:
        raise ValueError("Run as root or install sudo to prepare the build dependencies.")
    return ["sudo", "apt-get"]


def prepare_host_apt(apt_root: Path, keyring: Path | None = None) -> list[str]:
    apt_root.mkdir(parents=True, exist_ok=True)
    lists = apt_root / "lists"
    (lists / "partial").mkdir(parents=True, exist_ok=True)
    keyring = keyring or Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg")
    if not keyring.is_file():
        raise ValueError("Ubuntu archive signing keyring is missing from the build host.")
    sources = apt_root / "sources.list"
    entries = [
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://archive.ubuntu.com/ubuntu noble main restricted universe multiverse",
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://archive.ubuntu.com/ubuntu noble-updates main restricted universe multiverse",
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://archive.ubuntu.com/ubuntu noble-backports main restricted universe multiverse",
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://security.ubuntu.com/ubuntu noble-security main restricted universe multiverse",
    ]
    sources.write_text("\n".join(entries) + "\n", encoding="utf-8")
    return [
        "-o", "Dir::Etc::sourcelist=" + str(sources),
        "-o", "Dir::Etc::sourceparts=-",
        "-o", "Dir::State::lists=" + str(lists),
        "-o", "APT::Architecture=amd64",
        "-o", "APT::Get::List-Cleanup=0",
    ]


def install_host_packages(snapshot: str, apt_root: Path) -> None:
    apt = apt_command()
    options = prepare_host_apt(apt_root)
    run([*apt, *options, "update", "--snapshot", snapshot])
    run([
        *apt, *options, "install", "--yes", "--no-install-recommends",
        "--snapshot", snapshot, *HOST_PACKAGES,
    ])


def require_tools() -> None:
    missing = [name for name in REQUIRED_TOOLS if shutil.which(name) is None]
    if missing:
        raise ValueError("Required build tools are missing: " + ", ".join(missing))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_verified(url: str, destination: Path, expected: str) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and sha256_file(destination) == expected:
        print("Using verified source cache:", destination, flush=True)
        return destination
    temporary = destination.with_name(destination.name + ".download")
    if temporary.exists():
        temporary.unlink()
    request = urllib.request.Request(url, headers={"User-Agent": "RamenWine-runtime-builder/1"})
    digest = hashlib.sha256()
    try:
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as target:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                target.write(chunk)
        actual = digest.hexdigest()
        if actual != expected:
            raise ValueError("SHA-256 mismatch: expected " + expected + ", got " + actual)
        os.replace(temporary, destination)
    except Exception:
        if temporary.exists():
            temporary.unlink()
        raise
    return destination


def prepare_apt_root(apt_root: Path, keyring: Path | None = None) -> tuple[Path, list[str]]:
    apt_root.mkdir(parents=True, exist_ok=True)
    lists = apt_root / "lists"
    archives = apt_root / "archives"
    (lists / "partial").mkdir(parents=True, exist_ok=True)
    (archives / "partial").mkdir(parents=True, exist_ok=True)
    status = apt_root / "status"
    status.write_text("", encoding="utf-8")

    keyring = keyring or Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg")
    if not keyring.is_file():
        raise ValueError("Ubuntu archive signing keyring is missing from the build host.")
    sources = apt_root / "sources.list"
    entries = [
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://archive.ubuntu.com/ubuntu noble main restricted universe multiverse",
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://archive.ubuntu.com/ubuntu noble-updates main restricted universe multiverse",
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://archive.ubuntu.com/ubuntu noble-backports main restricted universe multiverse",
        "deb [arch=amd64 signed-by=" + str(keyring) + "] http://security.ubuntu.com/ubuntu noble-security main restricted universe multiverse",
        "deb [arch=arm64 signed-by=" + str(keyring) + "] http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse",
        "deb [arch=arm64 signed-by=" + str(keyring) + "] http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse",
        "deb [arch=arm64 signed-by=" + str(keyring) + "] http://ports.ubuntu.com/ubuntu-ports noble-backports main restricted universe multiverse",
        "deb [arch=arm64 signed-by=" + str(keyring) + "] http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse",
    ]
    sources.write_text("\n".join(entries) + "\n", encoding="utf-8")
    options = [
        "-o", "Dir::Etc::sourcelist=" + str(sources),
        "-o", "Dir::Etc::sourceparts=-",
        "-o", "Dir::State::lists=" + str(lists),
        "-o", "Dir::State::status=" + str(status),
        "-o", "Dir::Cache::archives=" + str(archives),
        "-o", "APT::Architecture=amd64",
        "-o", "APT::Architectures::=amd64",
        "-o", "APT::Architectures::=arm64",
        "-o", "APT::Get::List-Cleanup=0",
    ]
    return archives, options


def download_and_extract_guest_packages(
    rootfs: Path, apt_root: Path, snapshot: str
) -> list[dict[str, str]]:
    archives, options = prepare_apt_root(apt_root)
    run(["apt-get", *options, "update", "--snapshot", snapshot])
    run([
        "apt-get", *options, "install", "--download-only", "--yes",
        "--no-install-recommends", "--snapshot", snapshot, *RUNTIME_PACKAGES,
    ])
    debs = sorted(archives.glob("*.deb"))
    if not debs:
        raise ValueError("APT did not download any guest runtime packages.")

    provenance: list[dict[str, str]] = []
    for deb in debs:
        name = output(["dpkg-deb", "-f", str(deb), "Package"])
        version = output(["dpkg-deb", "-f", str(deb), "Version"])
        architecture = output(["dpkg-deb", "-f", str(deb), "Architecture"])
        digest = sha256_file(deb)
        if name == "libwine":
            continue
        run(["dpkg-deb", "-x", str(deb), str(rootfs)])
        provenance.append({
            "name": name,
            "version": version,
            "architecture": architecture,
            "sha256": digest,
        })
    return provenance


def checkout_box64(work_dir: Path) -> Path:
    source = work_dir / "box64-source"
    run(["git", "init", str(source)])
    run(["git", "-C", str(source), "remote", "add", "origin", BOX64_REPOSITORY])
    run(["git", "-C", str(source), "fetch", "--depth=1", "origin", BOX64_COMMIT])
    run(["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"])
    actual = output(["git", "-C", str(source), "rev-parse", "HEAD"])
    if actual != BOX64_COMMIT:
        raise ValueError("Box64 checkout does not match the pinned source commit.")
    return source


def make_box64_bundle_script_safe(source_text: str) -> str:
    cleanup_marker = "rm -rf /tmp/box64-bundle.*"
    temp_marker = "mktemp -d /tmp/box64-bundle.XXXXXX"
    if cleanup_marker not in source_text or temp_marker not in source_text:
        raise ValueError("The pinned Box64 library bundle script changed unexpectedly.")
    safe_text = source_text.replace(
        cleanup_marker,
        '[ -z "${dir_tmp:-}" ] || rm -rf "${dir_tmp}"',
    )
    safe_text = safe_text.replace(
        temp_marker,
        'mktemp -d "${TMPDIR:-/tmp}/box64-bundle.XXXXXX"',
    )
    return safe_text


def bundle_box64_x64_libraries(
    source: Path,
    rootfs: Path,
    work_dir: Path,
) -> dict[str, object]:
    source_script = source / "box64-bundle-x86-libs.sh"
    source_csv = source / "box64-bundle-x86-libs.csv"
    if not source_script.is_file() or not source_csv.is_file():
        raise ValueError("Pinned Box64 source is missing its x86 library bundle inputs.")

    original_script = source_script.read_text(encoding="utf-8")
    csv_text = source_csv.read_text(encoding="utf-8")
    safe_script = make_box64_bundle_script_safe(original_script)

    bundle_work = work_dir / "box64-x86-library-bundle"
    bundle_work.mkdir()
    local_script = bundle_work / "box64-bundle-x86-libs.sh"
    local_csv = bundle_work / "box64-bundle-x86-libs.csv"
    local_script.write_text(safe_script, encoding="utf-8")
    local_csv.write_text(csv_text, encoding="utf-8")
    local_script.chmod(0o755)

    run(
        ["sh", str(local_script)],
        cwd=bundle_work,
        extra_env={"TMPDIR": str(bundle_work)},
    )

    archive = bundle_work / "box64-bundle-x86-libs.tar.gz"
    if not archive.is_file():
        raise ValueError("Box64 did not produce its x86 library bundle archive.")
    archive_sha256 = sha256_file(archive)

    # The new Wine WoW64 path runs x86 Windows programs inside the x86-64
    # Unix Wine process. We need Box64's x86-64 Linux libraries, not its
    # separate i386 Linux library directory.
    run([
        "tar", "-xzf", str(archive), "--no-same-owner",
        "-C", str(rootfs), "./usr/lib/box64-x86_64-linux-gnu",
    ])

    library_directory = rootfs / "usr" / "lib" / "box64-x86_64-linux-gnu"
    if not library_directory.is_dir():
        raise ValueError("Box64 x86-64 emulated library directory was not extracted.")

    for name in BOX64_REQUIRED_X64_LIBRARIES:
        library = library_directory / name
        if not library.is_file():
            raise ValueError("Box64 x86-64 library bundle is missing " + name)
        package_runtime.require_elf64(
            library.resolve(strict=True),
            "Box64 bundled x86-64 library " + name,
            package_runtime.ELF_MACHINE_X86_64,
        )

    packages_archive = bundle_work / "box64-bundle-x86-pkgs.tar.gz"
    if packages_archive.exists():
        packages_archive.unlink()
    checksum_file = bundle_work / "box64-bundle-x86-libs.sha256"
    if checksum_file.exists():
        checksum_file.unlink()
    # The source archive has already been checksummed and only its x86-64
    # directory was extracted. Avoid retaining the full multi-architecture
    # archive (and its original package files) in the build workspace.
    archive.unlink()

    return {
        "sourceCommit": BOX64_COMMIT,
        "csvSha256": hashlib.sha256(csv_text.encode("utf-8")).hexdigest(),
        "scriptSha256": hashlib.sha256(original_script.encode("utf-8")).hexdigest(),
        "libraryArchiveSha256": archive_sha256,
        "installedDirectory": "rootfs/usr/lib/box64-x86_64-linux-gnu",
        "requiredLibraries": list(BOX64_REQUIRED_X64_LIBRARIES),
    }


def build_box64(source: Path, rootfs: Path, work_dir: Path, jobs: int, dynarec: bool) -> None:
    build = work_dir / "box64-build"
    build.mkdir()
    run([
        "cmake", "-S", str(source), "-B", str(build),
        "-DCMAKE_SYSTEM_NAME=Linux",
        "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
        "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
        "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
        "-DCMAKE_FIND_ROOT_PATH=/usr/aarch64-linux-gnu",
        "-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER",
        "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY",
        "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY",
        "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY",
        "-DARM64=1",
        "-DARM_DYNAREC=" + ("ON" if dynarec else "OFF"),
        "-DBAD_SIGNAL=ON",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_INSTALL_PREFIX=/usr",
    ])
    run(["cmake", "--build", str(build), "--parallel", str(jobs)])
    run(["cmake", "--install", str(build)], extra_env={"DESTDIR": str(rootfs)})


def build_wine(source_archive: Path, rootfs: Path, work_dir: Path, jobs: int) -> None:
    source = work_dir / "wine-source"
    source.mkdir()
    run(["tar", "-xf", str(source_archive), "--strip-components=1", "-C", str(source)])
    configure = source / "configure"
    if not configure.is_file():
        raise ValueError("The Wine source archive does not contain configure.")
    build = work_dir / "wine-build"
    build.mkdir()
    run([str(configure), *WINE_CONFIGURE_FLAGS], cwd=build)
    run(["make", "--jobs=" + str(jobs)], cwd=build)
    run(["make", "install"], cwd=build, extra_env={"DESTDIR": str(rootfs)})


def write_provenance(
    backend: Path,
    snapshot: str,
    packages: list[dict[str, str]],
    dynarec: bool,
    box64_bundle: dict[str, object],
) -> None:
    document = {
        "formatVersion": 1,
        "backend": "glibc-proot-box64",
        "ubuntuBase": {"url": UBUNTU_BASE_URL, "sha256": UBUNTU_BASE_SHA256, "architecture": "arm64"},
        "ubuntuArchiveSnapshot": snapshot,
        "box64": {
            "repository": BOX64_REPOSITORY,
            "commit": BOX64_COMMIT,
            "target": "aarch64-linux-gnu",
            "arm64Dynarec": dynarec,
            "badSignal": True,
        },
        "box64LibraryBundle": box64_bundle,
        "wine": {
            "version": "11.0",
            "url": WINE_URL,
            "sha256": WINE_SHA256,
            "peArchitectures": ["i386", "x86_64"],
            "configureFlags": list(WINE_CONFIGURE_FLAGS),
        },
        "runtimePackages": packages,
    }
    (backend / "BUILD-PROVENANCE.json").write_text(
        json.dumps(document, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def build(args: argparse.Namespace) -> None:
    validate_arguments(args.snapshot, args.version, args.jobs)
    require_build_host()

    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    if any(work.iterdir()):
        raise ValueError("work-dir must be empty; choose a new directory to protect existing files.")

    host = args.proot_host.resolve(strict=True)
    for relative in ("bin/proot", "libexec/proot/loader"):
        if not (host / relative).is_file():
            raise ValueError("PRoot host payload is missing " + relative)
    if not (host / "lib").is_dir():
        raise ValueError("PRoot host payload has no lib directory.")

    if not args.skip_host_dependencies:
        install_host_packages(args.snapshot, work / "apt-host")
    require_tools()

    cache = work / "cache"
    backend = work / "backend"
    rootfs = backend / "rootfs"
    backend.mkdir()
    rootfs.mkdir(parents=True)
    shutil.copytree(host, backend / "host", symlinks=True)

    ubuntu_archive = download_verified(
        UBUNTU_BASE_URL, cache / "ubuntu-base-arm64.tar.gz", UBUNTU_BASE_SHA256
    )
    run(["tar", "-xzf", str(ubuntu_archive), "--no-same-owner", "-C", str(rootfs)])

    package_provenance = download_and_extract_guest_packages(
        rootfs, work / "apt-guest", args.snapshot
    )

    box64_source = checkout_box64(work)
    box64_bundle = bundle_box64_x64_libraries(box64_source, rootfs, work)
    build_box64(box64_source, rootfs, work, args.jobs, not args.no_arm_dynarec)

    wine_archive = download_verified(
        WINE_URL, cache / "wine-11.0.tar.xz", WINE_SHA256
    )
    build_wine(wine_archive, rootfs, work, args.jobs)

    write_provenance(
        backend,
        args.snapshot,
        package_provenance,
        not args.no_arm_dynarec,
        box64_bundle,
    )
    package_runtime.validate_glibc_proot_box64(backend)

    output_path = args.output.resolve()
    if output_path == backend or backend in output_path.parents:
        raise ValueError("The output ZIP must be outside the prepared runtime directory.")
    run([
        sys.executable,
        str(Path(__file__).with_name("package_runtime.py")),
        "--root", str(backend),
        "--backend", "glibc-proot-box64",
        "--version", args.version,
        "--output", str(output_path),
    ])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proot-host", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--snapshot", default=DEFAULT_SNAPSHOT)
    parser.add_argument("--version", default="ubuntu24.04.5-wine11.0-box64-v0.4.4")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--no-arm-dynarec", action="store_true")
    parser.add_argument("--skip-host-dependencies", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        build(args)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("Runtime build failed:", error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
