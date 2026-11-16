# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-License-Identifier: GPL-3.0-or-later

add_library(eden-android SHARED
    android_env_file.cpp
    emu_window/emu_window.cpp
    emu_window/emu_window.h
    native.cpp
    native.h
    native_config.cpp
    native_freedreno.cpp
    android_settings.cpp
    game_metadata.cpp
    native_log.cpp
    android_config.cpp
    android_config.h
    native_input.cpp
    native_post_processing.cpp
)

set_property(TARGET eden-android PROPERTY IMPORTED_LOCATION ${FFmpeg_LIBRARY_DIR})

target_link_libraries(eden-android PRIVATE audio_core common core input_common frontend_common video_core)
target_link_libraries(eden-android PRIVATE android camera2ndk EGL glad jnigraphics log GLESv2)
if (ARCHITECTURE_arm64)
    target_link_libraries(eden-android PRIVATE adrenotools)
endif()

target_link_libraries(eden-android PRIVATE ${FFmpeg_LIBRARIES} OpenSSL::SSL cpp-jwt::cpp-jwt)
if (ENABLE_UPDATE_CHECKER)
    target_compile_definitions(eden-android PUBLIC ENABLE_UPDATE_CHECKER)
endif()

set(CPACK_PACKAGE_EXECUTABLES ${CPACK_PACKAGE_EXECUTABLES} eden-android)

target_link_options(eden-android PRIVATE "-Wl,-Bsymbolic")
