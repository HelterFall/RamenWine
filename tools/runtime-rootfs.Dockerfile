FROM debian:trixie-slim

RUN set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    dpkg --add-architecture amd64; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      box64:arm64=0.3.4+dfsg-1 \
      ca-certificates \
      fontconfig \
      fonts-dejavu-core \
      fonts-liberation \
      fonts-noto-cjk \
      fonts-wine \
      locales \
      libc6:amd64 \
      libgcc-s1:amd64 \
      libstdc++6:amd64 \
      libasound2t64:amd64 \
      libavcodec61:amd64 \
      libavformat61:amd64 \
      libavutil59:amd64 \
      libcapi20-3t64:amd64 \
      libfontconfig1:amd64 \
      libfreetype6:amd64 \
      libglib2.0-0t64:amd64 \
      libgphoto2-6t64:amd64 \
      libgphoto2-port12t64:amd64 \
      libgstreamer-plugins-base1.0-0:amd64 \
      libgstreamer1.0-0:amd64 \
      libpcap0.8t64:amd64 \
      libpcsclite1:amd64 \
      libpulse0:amd64 \
      libopenal1:amd64 \
      libudev1:amd64 \
      libunwind8:amd64 \
      libusb-1.0-0:amd64 \
      libwayland-client0:amd64 \
      libwayland-cursor0:amd64 \
      libwayland-egl1:amd64 \
      libx11-6:amd64 \
      libx11-xcb1:amd64 \
      libxext6:amd64 \
      libxkbcommon0:amd64 \
      libxkbregistry0:amd64 \
      libxcomposite1:amd64 \
      libxcursor1:amd64 \
      libxfixes3:amd64 \
      libxi6:amd64 \
      libxinerama1:amd64 \
      libxrandr2:amd64 \
      libxrender1:amd64 \
      libxxf86vm1:amd64 \
      ocl-icd-libopencl1:amd64 \
      gstreamer1.0-plugins-good:amd64 \
      libasound2-plugins:amd64 \
      libcups2t64:amd64 \
      libdbus-1-3:amd64 \
      libegl1:amd64 \
      libgl1:amd64 \
      libgl1-mesa-dri:amd64 \
      libgnutls30t64:amd64 \
      libgssapi-krb5-2:amd64 \
      libkrb5-3:amd64 \
      libodbc2:amd64 \
      libosmesa6:amd64 \
      libsdl2-2.0-0:amd64 \
      libv4l-0t64:amd64 \
      libvulkan1:amd64; \
    printf '%s\n' \
      'ja_JP.UTF-8 UTF-8' \
      'en_US.UTF-8 UTF-8' \
      'ru_RU.UTF-8 UTF-8' \
      'zh_CN.UTF-8 UTF-8' \
      'zh_TW.UTF-8 UTF-8' \
      'ko_KR.UTF-8 UTF-8' > /etc/locale.gen; \
    locale-gen; \
    locale -a | grep -qi 'ja_JP'; \
    locale -a | grep -qi 'zh_CN'; \
    test -d /usr/share/fonts/opentype/noto; \
    test -x /usr/bin/env; \
    test -x /usr/bin/box64; \
    test -e /lib64/ld-linux-x86-64.so.2 || test -e /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

# libcaca's optional OpenGL output plugin is shipped with the multimedia
# dependency set; keep its ELF dependencies present so the rootfs audit can
# validate every packaged plugin, not just the paths used by Wine.
RUN set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      libglu1-mesa:amd64 \
      libglut3.12:amd64; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*
